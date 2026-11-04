import contextlib
from copy import deepcopy
import importlib.util
import io
from itertools import permutations
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("host_release", ROOT / "scripts/deploy/ec2-release.py")
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
OLD = "sha256:" + "1" * 64
NEW = "sha256:" + "2" * 64
REVISION = "a" * 40


class ReleaseValidationTests(unittest.TestCase):
    """Validate release inputs and schema extraction without contacting AWS."""

    def test_only_digests_and_numeric_request_ids_are_allowed(self):
        """Reject tags, command fragments and missing explicit acknowledgements."""
        HOST.validate_request(NEW, OLD, REVISION, "123-1", HOST.APPROVAL)
        for arguments in (("latest", OLD, REVISION, "123-1", HOST.APPROVAL),
                          (NEW, OLD + ";id", REVISION, "123-1", HOST.APPROVAL),
                          (NEW, OLD, "main", "123-1", HOST.APPROVAL),
                          (NEW, OLD, REVISION, "../../state", HOST.APPROVAL),
                          (NEW, OLD, REVISION, "123-1", "yes")):
            with self.subTest(arguments=arguments), self.assertRaises(HOST.ReleaseError):
                HOST.validate_request(*arguments)

    def test_image_replacement_preserves_every_other_setting(self):
        """Persist digest changes without rewriting DB, Redis or auth configuration."""
        original = b"# unchanged\nBACKEND_IMAGE=old:tag\nDB_SECRET_ID=app-reference\n"
        updated = HOST.image_in_env(original, HOST.REPOSITORY + "@" + NEW)
        self.assertIn(b"DB_SECRET_ID=app-reference\n", updated)
        self.assertNotIn(b"old:tag", updated)
        for value in (b"X=1\n", b"BACKEND_IMAGE=one\nBACKEND_IMAGE=two\n"):
            with self.assertRaises(HOST.ReleaseError):
                HOST.image_in_env(value, "unused")

    def test_runtime_controls_reject_migration_and_property_overrides(self):
        """Do not trust a candidate image that can override the fixed Flyway policy."""
        good = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false", "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
        HOST.runtime_controls(good)
        for key, value in (("SPRING_FLYWAY_ENABLED", "true"), ("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"),
                           ("SPRING_APPLICATION_JSON", "hidden"), ("JDK_JAVA_OPTIONS", "hidden"),
                           ("DB_PASSWORD", "hidden"), ("AWS_ACCESS_KEY_ID", "hidden"),
                           ("JAVA_TOOL_OPTIONS", "-Dspring.flyway.enabled=true")):
            with self.subTest(key=key), self.assertRaises(HOST.ReleaseError):
                HOST.runtime_controls({**good, key: value})

    def test_sql_fingerprint_normalizes_endings_and_detects_any_migration_change(self):
        """Treat added/edited migrations as a release gate, without executing SQL."""
        with tempfile.TemporaryDirectory() as tmp:
            files = []
            for index, entries in enumerate(([("V1__init.sql", b"SELECT 1;\r\n")],
                                             [("V1__init.sql", b"SELECT 1;\n")],
                                             [("V1__init.sql", b"SELECT 2;\n")],
                                             [("V1__init.sql", b"SELECT 1;\n"), ("V2__more.sql", b"ALTER TABLE x;")])):
                path = Path(tmp) / f"{index}.jar"
                with zipfile.ZipFile(path, "w") as jar:
                    for name, content in entries:
                        jar.writestr("BOOT-INF/classes/db/migration/" + name, content)
                files.append(HOST.schema_fingerprint(path))
            self.assertEqual(files[0], files[1])
            self.assertNotEqual(files[0], files[2])
            self.assertNotEqual(files[1], files[3])

    def test_missing_and_unsupported_migrations_fail_closed(self):
        """Reject missing, nested or repeatable migration layouts instead of guessing."""
        with tempfile.TemporaryDirectory() as tmp:
            for index, name in enumerate((None, "nested/V1__init.sql", "R__repeat.sql")):
                path = Path(tmp) / f"{index}.jar"
                with zipfile.ZipFile(path, "w") as jar:
                    if name:
                        jar.writestr("BOOT-INF/classes/db/migration/" + name, "SELECT 1")
                with self.subTest(name=name), self.assertRaises(HOST.ReleaseError):
                    HOST.schema_fingerprint(path)

    def test_symlink_jar_is_rejected_before_any_archive_read(self):
        """A copied container symlink cannot cause reads of a host secret file."""
        with patch.object(Path, "lstat", return_value=Mock(st_mode=0o120777, st_size=10)), \
                patch.object(HOST.zipfile, "ZipFile") as archive, self.assertRaises(HOST.ReleaseError):
            HOST.schema_fingerprint("untrusted.jar")
        archive.assert_not_called()

    def test_command_runner_sanitizes_errors_and_uses_instance_credentials(self):
        """Never inherit caller AWS keys or expose subprocess stdout/stderr."""
        with patch.dict(os.environ, AWS_ACCESS_KEY_ID="sensitive", AWS_PROFILE="admin"):
            command = HOST.Commands("/isolated/docker")
        self.assertNotIn("AWS_ACCESS_KEY_ID", command.env)
        self.assertNotIn("AWS_PROFILE", command.env)
        self.assertEqual(command.env["AWS_SHARED_CREDENTIALS_FILE"], "/dev/null")
        with patch.object(HOST.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"sensitive", b"sensitive")):
            with self.assertRaises(HOST.ReleaseError) as error:
                command.run(["fixed"], HOST.time.monotonic() + 5)
        self.assertNotIn("sensitive", str(error.exception))

    def test_command_runner_rejects_late_success(self):
        """A command that returns after its absolute deadline cannot succeed."""
        clock = [0]

        def late(*args, **kwargs):
            """Emulate an OS-level delayed successful return."""
            clock[0] = 6
            return subprocess.CompletedProcess([], 0, b"", b"")

        with patch.object(HOST.time, "monotonic", side_effect=lambda: clock[0]), patch.object(HOST.subprocess, "run", side_effect=late):
            with self.assertRaises(HOST.ReleaseError):
                HOST.Commands("unused").run(["fixed"], 5)

    def test_host_exit_codes_distinguish_recovery_outcomes(self):
        """The SSM observer can classify recovery using integers without remote logs."""
        arguments = ["release", "--digest", NEW, "--previous", OLD, "--revision", REVISION,
                     "--request-id", "123-1", "--approval", HOST.APPROVAL]
        for error, code in ((HOST.RestoredRelease("safe"), 10), (HOST.RecoveryRequired("safe"), 20),
                            (HOST.ReleaseError("safe"), 1)):
            with self.subTest(code=code), patch.object(HOST.sys, "argv", arguments), \
                    patch.object(HOST, "release_lock", return_value=contextlib.nullcontext()), \
                    patch.object(HOST.tempfile, "TemporaryDirectory", side_effect=[contextlib.nullcontext("/private-credentials"), contextlib.nullcontext("/private-stage")]), \
                    patch.object(HOST.signal, "signal"), patch.object(HOST, "Deployment") as deployment, \
                    contextlib.redirect_stderr(io.StringIO()):
                deployment.return_value.execute.side_effect = error
                self.assertEqual(HOST.main(), code)


class TransactionTests(unittest.TestCase):
    """Exercise success and recovery with real temporary settings, no real commands."""

    def setUp(self):
        """Keep filesystem writes isolated and reject unexpected subprocess execution."""
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = self.root / "settings"
        self.original = b"BACKEND_IMAGE=old:tag\nDB_SECRET_ID=app-ref\n"
        self.env.write_bytes(self.original)
        self.compose_bytes = b"name: hobbytree\nservices: {}\n"
        (self.root / "compose.ec2.yml").write_bytes(self.compose_bytes)
        self.states, self.starts, self.verifications = [], [], []
        self.failure = None
        self.fail_rollback = False
        for name, value in (("STATE", self.root), ("ROOT", self.root), ("ENV_FILE", self.env)):
            current = patch.object(HOST, name, value)
            current.start()
            self.addCleanup(current.stop)
        writer = patch.object(HOST, "atomic_write", side_effect=lambda path, data: Path(path).write_bytes(data))
        writer.start()
        self.addCleanup(writer.stop)
        no_commands = patch.object(HOST.subprocess, "run", side_effect=AssertionError("Unmocked subprocess"))
        no_commands.start()
        self.addCleanup(no_commands.stop)
        self.release = HOST.Deployment(Mock(), self.root, NEW, OLD, REVISION, "123-1")
        self.release.verify_contract = self.contract
        self.release.prepare = self.prepare
        self.release.start_backend = self.start
        self.release.verify_running = self.verify
        journal = self.release.journal

        def record(status):
            """Capture status order while keeping durable journal behavior."""
            self.states.append(status)
            journal(status)

        self.release.journal = record

    def contract(self):
        """Supply a prevalidated isolated host snapshot."""
        self.release.original = self.env.read_bytes()
        self.release.old_image_id = OLD
        self.release.contract = {"files": {str(self.root / "compose.ec2.yml"): HOST.sha(self.compose_bytes)}}

    def prepare(self):
        """Model pre-cutover refusal for migration or configuration drift."""
        if self.failure == "schema":
            raise HOST.ReleaseError("migration difference")
        self.release.new_image_id = NEW

    def start(self):
        """Record the exact image chosen by the environment at each replacement."""
        image = self.env.read_text(encoding="utf-8").splitlines()[0].split("=", 1)[1]
        self.starts.append(image)
        self.assertTrue((self.release.directory / "settings.before").exists())
        if len(self.starts) == 1 and self.failure == "start":
            raise HOST.ReleaseError("startup failure")
        if len(self.starts) > 1 and self.fail_rollback:
            raise HOST.ReleaseError("recovery failed")

    def verify(self, image):
        """Model Docker/ALB failure or cancellation after the new image starts."""
        self.verifications.append(image)
        if image == NEW:
            if self.failure in ("health", "alb"):
                raise HOST.ReleaseError("unhealthy")
            if self.failure == "cancel":
                raise KeyboardInterrupt()
            if self.failure == "drift":
                self.env.write_bytes(b"BACKEND_IMAGE=external-change\n")
                raise HOST.ReleaseError("external edit")

    def execute(self):
        """Suppress safe progress messages while invoking the transaction."""
        with contextlib.redirect_stdout(io.StringIO()):
            return self.release.execute()

    def test_success_persists_digest_and_previous_settings(self):
        """A successful release retains the original snapshot and fixed digest."""
        self.assertTrue(self.execute())
        self.assertEqual(self.states, ["prepared", "changing", "succeeded"])
        self.assertEqual((self.root / "123-1/settings.before").read_bytes(), self.original)
        self.assertEqual((self.root / "123-1/compose.approved.yml").read_bytes(), self.compose_bytes)
        self.assertEqual(self.starts, [HOST.REPOSITORY + "@" + NEW])
        self.assertEqual(json.loads((self.root / "active.json").read_text(encoding="utf-8"))["status"], "succeeded")

    def test_predeployment_schema_refusal_never_changes_live_settings(self):
        """A migration difference cannot start or replace any service."""
        self.failure = "schema"
        with self.assertRaises(HOST.ReleaseError):
            self.execute()
        self.assertEqual(self.env.read_bytes(), self.original)
        self.assertEqual(self.starts, [])
        self.assertFalse((self.root / "active.json").exists())

    def test_failure_and_cancellation_restore_digest_and_still_fail_the_release(self):
        """Startup, health, ALB and cancellation failures all verify the old image."""
        for index, failure in enumerate(("start", "health", "alb", "cancel")):
            with self.subTest(failure=failure):
                self.env.write_bytes(self.original)
                self.release.directory = self.root / f"123-{index + 2}"
                self.starts.clear()
                self.states.clear()
                self.failure = failure
                with self.assertRaises(HOST.RestoredRelease):
                    self.execute()
                self.assertEqual(self.starts, [HOST.REPOSITORY + "@" + NEW, HOST.REPOSITORY + "@" + OLD])
                self.assertEqual(self.verifications[-1], OLD)
                self.assertEqual(self.states[-1], "rolled-back")
                self.assertIn((HOST.REPOSITORY + "@" + OLD).encode(), self.env.read_bytes())

    def test_failed_rollback_leaves_a_blocking_journal(self):
        """Recovery failure cannot be presented as success or silently retried."""
        self.failure, self.fail_rollback = "health", True
        with self.assertRaises(HOST.RecoveryRequired):
            self.execute()
        self.assertEqual(self.states[-1], "rollback-failed")
        self.assertNotIn("rollback-failed", HOST.TERMINAL)

    def test_external_settings_change_is_not_overwritten_by_rollback(self):
        """Out-of-band edits require manual recovery instead of clobbering settings."""
        self.failure = "drift"
        with self.assertRaises(HOST.RecoveryRequired):
            self.execute()
        self.assertEqual(self.env.read_bytes(), b"BACKEND_IMAGE=external-change\n")
        self.assertEqual(self.states[-1], "rollback-failed")

    def test_failure_journal_keeps_exception_types_without_foreign_secret_text(self):
        """Forward and rollback diagnostics must not persist raw third-party messages."""
        self.release.start_backend = Mock(side_effect=[ValueError("sensitive-forward"), OSError("sensitive-rollback")])
        with self.assertRaises(HOST.RecoveryRequired):
            self.execute()
        record = json.loads((self.root / "123-1/release.json").read_text(encoding="utf-8"))
        self.assertEqual(record["failure"], "ValueError")
        self.assertEqual(record["rollback_failure"], "OSError")
        self.assertEqual(record["failed_stage"], "start-backend")
        self.assertEqual(record["rollback_stage"], "start-previous-backend")
        self.assertNotIn("sensitive", json.dumps(record))
        self.assertEqual(HOST.failure_summary(HOST.ReleaseError("Fixed safe diagnostic.")), "Fixed safe diagnostic.")

    def test_duplicate_request_is_rejected(self):
        """An existing request journal cannot submit a second replacement."""
        self.execute()
        with self.assertRaises(HOST.ReleaseError):
            self.execute()
        self.assertEqual(len(self.starts), 1)

    def test_snapshot_drift_cannot_reach_the_mutation_phase(self):
        """A file changed between verification and snapshot is not deployed."""
        original_prepare = self.release.prepare

        def changed():
            """Emulate an unrelated root editor after the normal prepare step."""
            original_prepare()
            (self.root / "compose.ec2.yml").write_bytes(b"unreviewed")

        self.release.prepare = changed
        with self.assertRaises(HOST.ReleaseError):
            self.execute()
        self.assertEqual(self.starts, [])
        self.assertEqual(self.env.read_bytes(), self.original)

    def test_same_digest_is_a_verified_no_op(self):
        """An already-running digest is checked without replacing the backend."""
        self.release.image = self.release.previous
        self.assertTrue(self.execute())
        self.assertEqual(self.starts, [])
        self.assertEqual(self.states, ["no-op"])


class RedisMountTests(unittest.TestCase):
    """Ignore inspect mount ordering while preserving every field and duplicate."""

    def setUp(self):
        """Provide a healthy Redis inspect snapshot without external commands."""
        mounts = [
            {"Type": "volume", "Name": "hobbytree_redis_data", "Driver": "local",
             "Source": "/var/lib/docker/volumes/hobbytree_redis_data/_data", "Destination": "/data",
             "Mode": "rw", "RW": True, "Propagation": ""},
            {"Type": "bind", "Source": "/opt/hobbytree/deploy/redis.conf", "Destination": "/etc/redis/redis.conf",
             "Mode": "ro", "RW": False, "Propagation": "rprivate"},
            {"Type": "bind", "Source": "/run/hobbytree/redis-secrets", "Destination": "/run/secrets/redis",
             "Mode": "ro", "RW": False, "Propagation": "rprivate"},
        ]
        self.release = HOST.Deployment(Mock(), "/isolated", NEW, OLD, REVISION, "123-1")
        self.release.redis = {"Id": "same-redis", "Mounts": mounts}
        self.current = deepcopy(self.release.redis)
        self.release.health = Mock(return_value=self.current)

    def test_identical_mounts_allow_every_array_order(self):
        """All six inspect permutations retain the same Redis mounts."""
        for mounts in permutations(self.release.redis["Mounts"]):
            with self.subTest(order=[mount["Destination"] for mount in mounts]):
                self.current["Mounts"] = [dict(reversed(list(mount.items()))) for mount in mounts]
                self.release.unchanged_redis()

    def test_every_existing_mount_field_change_is_rejected(self):
        """Source, Destination, RW and all other mount fields remain significant."""
        baseline = self.release.redis["Mounts"][0]
        for field, original in baseline.items():
            with self.subTest(field=field):
                self.current["Mounts"] = deepcopy(self.release.redis["Mounts"])
                self.current["Mounts"][0][field] = not original if isinstance(original, bool) else original + "-changed"
                with self.assertRaises(HOST.ReleaseError):
                    self.release.unchanged_redis()

    def test_mount_membership_duplicates_and_field_presence_are_preserved(self):
        """Do not turn the mount list into a set or project a subset of fields."""
        baseline = self.release.redis["Mounts"]
        variants = [baseline[:-1], baseline + [baseline[0]],
                    [{**baseline[0], "Extra": "new-field"}, *baseline[1:]],
                    [{key: value for key, value in baseline[0].items() if key != "Driver"}, *baseline[1:]]]
        for mounts in variants:
            with self.subTest(mounts=mounts):
                self.current["Mounts"] = deepcopy(mounts)
                with self.assertRaises(HOST.ReleaseError):
                    self.release.unchanged_redis()

    def test_nested_values_keep_their_own_array_order(self):
        """Only the outer Mounts array is unordered, including future JSON fields."""
        self.release.redis["Mounts"][0]["Extra"] = {"flags": ["first", "second"], "enabled": True}
        self.current["Mounts"] = deepcopy(list(reversed(self.release.redis["Mounts"])))
        self.release.unchanged_redis()
        self.current["Mounts"][-1]["Extra"]["flags"].reverse()
        with self.assertRaises(HOST.ReleaseError):
            self.release.unchanged_redis()

    def test_container_id_change_is_rejected_with_identical_mounts(self):
        """A replacement Redis container still fails the identity guard."""
        self.current["Id"] = "replacement-redis"
        with self.assertRaises(HOST.ReleaseError):
            self.release.unchanged_redis()


class HostOperationTests(unittest.TestCase):
    """Inspect bounded Docker/ALB behavior without starting real containers."""

    def setUp(self):
        """Replace clock and all external execution for deterministic safety tests."""
        self.clock = 0
        self.commands = Mock()
        self.release = HOST.Deployment(self.commands, "/isolated", NEW, OLD, REVISION, "123-1")
        self.release.deadline = 1000
        timer = patch.object(HOST.time, "monotonic", side_effect=lambda: self.clock)
        sleeper = patch.object(HOST.time, "sleep", side_effect=self.advance)
        timer.start()
        sleeper.start()
        self.addCleanup(timer.stop)
        self.addCleanup(sleeper.stop)

    def advance(self, value):
        """Accumulate virtual verification time."""
        self.clock += value

    def test_compose_replaces_only_backend_and_never_pulls_dependencies(self):
        """The production mutation command cannot recreate Redis or delete volumes."""
        self.release.start_backend()
        arguments = self.commands.run.call_args.args[0]
        self.assertEqual(arguments[-1], "backend")
        self.assertIn("--no-deps", arguments)
        self.assertIn("--no-build", arguments)
        self.assertIn("never", arguments)
        self.assertNotIn("redis", arguments)
        self.assertNotIn("down", arguments)
        self.assertNotIn("--volumes", arguments)

    def test_alb_must_remain_healthy_for_fresh_observation_window(self):
        """A single cached healthy response cannot immediately complete a release."""
        self.release.health = Mock()
        self.release.unchanged_redis = Mock()
        self.release.verify_target_contract = Mock()
        self.release.target_state = Mock(return_value="healthy")
        self.release.verify_running(NEW)
        self.assertEqual(self.clock, 150)
        self.assertEqual(self.release.target_state.call_count, 16)

    def test_unhealthy_alb_is_bounded(self):
        """A persistent unhealthy target times out without any ALB write operation."""
        self.release.health = Mock()
        self.release.unchanged_redis = Mock()
        self.release.verify_target_contract = Mock()
        self.release.target_state = Mock(return_value="unhealthy")
        with self.assertRaises(HOST.ReleaseError):
            self.release.verify_running(NEW)
        self.assertEqual(self.clock, 420)

    def test_redis_replacement_is_rejected(self):
        """Detect any Redis container or data-mount identity change."""
        self.release.redis = {"Id": "old", "Mounts": [{"Name": "persistent"}]}
        self.release.health = Mock(return_value={"Id": "new", "Mounts": [{"Name": "persistent"}]})
        with self.assertRaises(HOST.ReleaseError):
            self.release.unchanged_redis()

    def test_image_contract_rejects_architecture_user_command_volume_and_size_drift(self):
        """Unreviewed image execution contracts cannot reach cutover."""
        good = {"Os": "linux", "Architecture": "amd64", "Size": 1024,
                "Config": {"User": "10001:10001", "Entrypoint": ["/app/docker-entrypoint.sh"]}}
        self.commands.json.return_value = good
        self.release.image_info("fixed")
        variants = [{**good, "Architecture": "arm64"}, {**good, "Size": 0},
                    {**good, "Config": {**good["Config"], "User": "root"}},
                    {**good, "Config": {**good["Config"], "Cmd": ["--override"]}},
                    {**good, "Config": {**good["Config"], "Volumes": {"/data": {}}}}]
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(HOST.ReleaseError):
                self.commands.json.return_value = variant
                self.release.image_info("fixed")

    def test_live_backend_privilege_and_mount_drift_is_rejected(self):
        """Do not silently replace a host whose running settings differ from Compose."""
        self.release.backend_config = {"volumes": [{"type": "bind", "target": "/secrets", "source": "/protected", "read_only": True}],
                                       "ports": [{"target": 8080, "published": "8080", "host_ip": "0.0.0.0"}]}
        info = {"HostConfig": {"Memory": 1024 ** 3, "MemorySwap": 1024 ** 3, "ReadonlyRootfs": True,
                                "Privileged": False, "PortBindings": {"8080/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8080"}]}},
                "Config": {"User": "10001:10001", "Entrypoint": ["/app/docker-entrypoint.sh"]},
                "Mounts": [{"Type": "bind", "Destination": "/secrets", "Source": "/protected", "RW": False}]}
        self.release.backend_runtime(info)
        for key, value in (("Privileged", True), ("Memory", 0), ("ReadonlyRootfs", False)):
            with self.subTest(key=key), self.assertRaises(HOST.ReleaseError):
                self.release.backend_runtime({**info, "HostConfig": {**info["HostConfig"], key: value}})
        with self.assertRaises(HOST.ReleaseError):
            self.release.backend_runtime({**info, "Mounts": [{**info["Mounts"][0], "RW": True}]})

    def prepare_candidate(self, revision=REVISION):
        """Provide a pulled candidate while preserving the actual preparation logic."""
        self.release.old_image_id = OLD
        self.release.compose_env = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                                    "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
        self.commands.run.return_value = b"private-token\n"
        self.release.image_info = Mock(return_value={"Id": NEW, "RepoDigests": [self.release.image],
                                                    "Config": {"Labels": {"org.opencontainers.image.revision": revision}, "Env": []}})

    def test_actual_preparation_rejects_schema_change_before_any_settings_write(self):
        """A changed migration set must abort before the real transaction starts."""
        self.prepare_candidate()
        self.release.extract_schema = Mock(side_effect=["old-schema", "new-schema"])
        with patch.object(HOST, "atomic_write") as write, self.assertRaises(HOST.ReleaseError):
            self.release.prepare()
        write.assert_not_called()
        self.assertFalse(any("compose" in call.args[0] for call in self.commands.run.call_args_list))

    def test_revision_mismatch_stops_before_jar_extraction(self):
        """A digest cannot impersonate the requested source commit through its tag."""
        self.prepare_candidate(revision="b" * 40)
        self.release.extract_schema = Mock()
        with self.assertRaises(HOST.ReleaseError):
            self.release.prepare()
        self.release.extract_schema.assert_not_called()

    def test_candidate_runtime_override_stops_before_jar_extraction(self):
        """An image-provided high-priority Spring JSON override is rejected."""
        self.prepare_candidate()
        self.release.image_info.return_value["Config"]["Env"] = ["SPRING_APPLICATION_JSON=private"]
        self.release.extract_schema = Mock()
        with self.assertRaises(HOST.ReleaseError):
            self.release.prepare()
        self.release.extract_schema.assert_not_called()

    def test_registry_password_is_only_sent_on_stdin(self):
        """The ECR credential never appears in a command argument or log."""
        self.prepare_candidate()
        self.release.extract_schema = Mock(side_effect=HOST.ReleaseError("stop before mutation"))
        with self.assertRaises(HOST.ReleaseError):
            self.release.prepare()
        login = self.commands.run.call_args_list[1]
        self.assertIn("--password-stdin", login.args[0])
        self.assertEqual(login.kwargs["data"], b"private-token\n")
        self.assertNotIn("private-token", " ".join(login.args[0]))

    def test_target_group_drift_is_rejected(self):
        """Do not reuse a deployment timeout after a health-check contract change."""
        self.commands.json.return_value = {"TargetGroups": [{"TargetGroupArn": HOST.TARGET_GROUP,
            "TargetType": "instance", "Port": 8080, "HealthCheckPath": "/actuator/health",
            "HealthCheckIntervalSeconds": 60, "HealthyThresholdCount": 5}]}
        with self.assertRaises(HOST.ReleaseError):
            self.release.verify_target_contract()

    def test_alb_success_after_its_window_is_rejected(self):
        """A late target-health response cannot defeat the 420-second observation cap."""
        self.release.health = Mock()
        self.release.unchanged_redis = Mock()
        self.release.verify_target_contract = Mock()
        calls = []

        def response():
            """Keep the first response on time and delay the second past the window."""
            if calls:
                self.advance(421)
            calls.append(1)
            return "healthy"

        self.release.target_state = response
        with self.assertRaises(HOST.ReleaseError):
            self.release.verify_running(NEW)


@unittest.skipUnless(sys.platform == "linux" and hasattr(os, "geteuid") and os.geteuid() == 0, "Linux root filesystem checks run in existing CI")
class LinuxLockTests(unittest.TestCase):
    """Verify real POSIX lock/permissions only inside root-owned temporary paths."""

    def test_lock_contention_and_incomplete_journal_block_new_work(self):
        """A second lock or abandoned replacement cannot enter the critical section."""
        with tempfile.TemporaryDirectory(dir=Path.home()) as tmp, patch.object(HOST, "STATE", Path(tmp) / "state"):
            with HOST.release_lock():
                with self.assertRaises(HOST.ReleaseError), HOST.release_lock():
                    pass
            HOST.atomic_write(HOST.STATE / "active.json", b'{"status":"changing"}')
            with self.assertRaises(HOST.ReleaseError), HOST.release_lock():
                pass

    def test_protected_files_reject_symlinks_and_world_writes(self):
        """The privileged entry point cannot trust redirected or writable files."""
        with tempfile.TemporaryDirectory(dir=Path.home()) as tmp:
            target = Path(tmp) / "file"
            target.write_text("safe")
            target.chmod(0o600)
            HOST.protected(target)
            alias = Path(tmp) / "alias"
            alias.symlink_to(target)
            with self.assertRaises(HOST.ReleaseError):
                HOST.protected(alias)
            target.chmod(0o666)
            with self.assertRaises(HOST.ReleaseError):
                HOST.protected(target)


if __name__ == "__main__":
    unittest.main()
