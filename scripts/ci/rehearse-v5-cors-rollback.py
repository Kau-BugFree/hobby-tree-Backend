"""Run the host transaction against a disposable Docker project with no AWS access."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "deploy/evidence/v5-cors-security"
SPEC = importlib.util.spec_from_file_location("cors_release", ROOT / "scripts/deploy/ec2-cors-release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
CORE, CORS = RELEASE.CORE, RELEASE.CORS
ORIGIN = "https://frontend.example"


def command(*args):
    """Run only local CI commands and suppress raw output on failure."""
    return subprocess.run(args, capture_output=True, check=True, timeout=220).stdout


def verify_manifest(name, digest, image_id):
    """Bind a verified local image to the original manifest retrieved from ECR."""
    raw = (EVIDENCE / (name + "-manifest.json")).read_bytes()
    assert "sha256:" + CORE.sha(raw) == digest
    manifest = json.loads(raw)
    assert manifest["mediaType"] == "application/vnd.docker.distribution.manifest.v2+json"
    assert manifest["config"]["digest"] == image_id


class Rehearsal(RELEASE.CorsRelease):
    """Use CI config instead of EC2/systemd and resolve the two verified local images."""

    def compose(self, *args, timeout=30):
        """Keep the production Compose file and add only the isolated DB fixture."""
        compose = [os.environ["CI_COMPOSE"]] if "CI_COMPOSE" in os.environ else ["docker", "compose"]
        return command(*compose, "-p", "hobbytree", "--project-directory", str(ROOT),
                       "--env-file", str(CORE.ENV_FILE), "-f", str(self.compose_file), "-f",
                       str(ROOT / "compose.ci.yml"), "-f", os.environ["CI_SCHEMA_OVERLAY"], *args)

    def verify_contract(self):
        """Substitute the host checks with fixture assertions against the same Compose."""
        self.original = CORE.ENV_FILE.read_bytes()
        assert CORE.image_in_env(self.original, self.previous) == self.original
        base = ROOT / "compose.ec2.yml"
        self.contract = {"files": {str(base): CORE.sha(base.read_bytes())}}
        current = self.health("backend")
        assert current["Image"] == self.old_image_id
        self.redis = self.health("redis")
        self.before = CORS.persistent_value()
        self.check_container_origins(current, self.before)
        assert self.request() == ("401", [])

    def prepare(self):
        """Read both actual JARs through the core SQL fingerprint without contacting ECR."""
        for tag, image_id, revision in ((self.previous, self.old_image_id, RELEASE.PREVIOUS_REVISION),
                                        (self.image, self.new_image_id, self.revision)):
            info, = json.loads(command("docker", "image", "inspect", tag))
            assert info["Id"] == image_id
            assert info["Config"]["Labels"]["org.opencontainers.image.revision"] == revision
            assert self.extract_schema(tag, revision[:8]) == RELEASE.SCHEMA
        if hasattr(self, "database"):
            self.database.open({
                "database": {"host": "postgres", "port": 5432, "dbname": "hobbytree_ci",
                             "identity": "v5-cors-ci-identity"},
                "reader": "ci_release_reader",
                "password_file": str(CORE.ENV_FILE.with_name("reader-password")),
                "ca_file": str(CORE.ENV_FILE.with_name("db-ca.pem")),
            })
            self.migration_gate = self.database
            identity = self.database.database["identity"]
            self.database.database["identity"] = "incorrect-fixture-identity"
            try:
                self.database.check()
            except CORE.ReleaseError as error:
                assert str(error) == "Actual DB identity mismatch."
            else:
                raise AssertionError("DB identity mismatch was accepted")
            finally:
                self.database.database["identity"] = identity
            try:
                self.compose("exec", "-T", "postgres", "psql", "-XqAt", "-U", "ci_migration", "-d", "hobbytree_ci",
                             "-v", "ON_ERROR_STOP=1", "-c",
                             "SET lock_timeout='250ms'; ALTER TABLE public.experiences "
                             "DROP CONSTRAINT hobbytree_v45_rollback_guard;")
            except subprocess.CalledProcessError as error:
                assert b"lock timeout" in error.stderr
            else:
                raise AssertionError("Rollback barrier DDL was not blocked")
            self.database.check()

    def verify_running(self, image_id):
        """Use real CORS and Redis checks, then force rollback after Hotfix passes."""
        super().verify_running(image_id)
        if image_id == self.new_image_id:
            if not self.origins:
                assert self.preflight(ORIGIN) == ("403", [])
                assert CORS.persistent_value() is None
            self.compose("up", "-d", "--no-build", "--pull", "never", "--no-deps", "--force-recreate",
                         "--wait", "--wait-timeout", "180", "backend")
            super().verify_running(image_id)
            raise CORE.ReleaseError("Intentional failure after Hotfix verification.")


def docker_health_without_alb(self, image_id):
    """Substitute the unavailable ALB with Docker health and Redis checks."""
    running = self.health("backend", image_id)
    env = dict(item.split("=", 1) for item in running["Config"]["Env"])
    CORE.runtime_controls(env)
    self.unchanged_redis()
    if hasattr(self, "migration_gate"):
        self.migration_gate.check()


def database_state(release, with_history):
    """Read the rollback barrier and either snapshot fixture history or require its absence."""
    history = ("SELECT version, checksum, success, installed_rank, installed_on "
               "FROM public.flyway_schema_history ORDER BY installed_rank;" if with_history else
               "SELECT to_regclass('public.flyway_schema_history') IS NULL;")
    return release.compose("exec", "-T", "postgres", "psql", "-XqAt",
                           "-U", "ci_migration", "-d", "hobbytree_ci", "-v", "ON_ERROR_STOP=1", "-c",
                           "SELECT pg_get_constraintdef(oid), convalidated FROM pg_constraint "
                           "WHERE conrelid='public.experiences'::regclass "
                           "AND conname='hobbytree_v45_rollback_guard'; " + history)


def main(candidate_revision=None, mode=None):
    """Force a failure and verify the real old container and exact settings return."""
    assert os.environ.get("HOBBYTREE_CI") == "1" and os.environ.get("GITHUB_ACTIONS") == "true"
    assert os.geteuid() == 0
    CORE.ROOT = ROOT
    CORE.ENV_FILE = Path(os.environ["CI_RELEASE_ENV"])
    CORE.STATE = CORE.ENV_FILE.parent / "state"
    CORE.STATE.mkdir(mode=0o700)
    CORE.Deployment.verify_running = docker_health_without_alb
    original = CORE.ENV_FILE.read_bytes()
    previous_id = os.environ["CI_OLD_IMAGE_ID"]
    candidate_id = os.environ["CI_NEW_IMAGE_ID"]
    assert mode in (None, "published")
    published = mode == "published"
    if published:
        assert candidate_revision == RELEASE.REVISION
        verify_manifest("previous", RELEASE.PREVIOUS, previous_id)
        verify_manifest("candidate", RELEASE.CANDIDATE, candidate_id)
    with tempfile.TemporaryDirectory(dir=CORE.STATE) as scratch:
        release = Rehearsal(CORE.Commands(scratch), scratch, "1-1", "" if published else ORIGIN)
        release.image = "hobbytree/backend:cors-rehearsal-new"
        release.previous = "hobbytree/backend:cors-rehearsal-old"
        release.old_image_id = previous_id
        release.new_image_id = candidate_id
        with_history = candidate_revision is not None
        if with_history:
            # Local aliases resolve the verified Image IDs, with no invented ECR candidate digest.
            release.revision = candidate_revision
            release.record.update(image=release.image, revision=candidate_revision)
        if published:
            release.database = RELEASE.DATABASE.Database(CORE, RELEASE.V45)
            release.record.update(ecr_candidate=CORE.REPOSITORY + "@" + RELEASE.CANDIDATE)
        state = database_state(release, with_history)
        assert b"COMING_SOON" in state and state.splitlines()[0].endswith(b"|t")
        if with_history:
            rows = [row.split(b"|") for row in state.splitlines()[1:]]
            assert [row[:1] + row[2:4] for row in rows] == [
                [str(version).encode(), b"t", str(version).encode()] for version in range(1, 6)]
        else:
            assert state.endswith(b"|t\nt\n")
        # Prove the previous V5 ignores this environment setting without weakening JWT.
        previous_setting = CORS.setting("https://previous.example")
        CORS.write(previous_setting)
        release.compose("up", "-d", "--no-build", "--pull", "never", "--no-deps", "--wait",
                        "--wait-timeout", "180", "backend")
        assert release.preflight("https://previous.example") == ("401", [])
        try:
            release.execute()
        except CORE.RestoredRelease:
            pass
        else:
            raise AssertionError("The forced failure did not roll back")
        finally:
            if published:
                release.database.close()
        assert CORE.ENV_FILE.read_bytes() == original
        assert CORS.persistent_value() == previous_setting
        assert (release.directory / "cors.before").read_bytes() == previous_setting
        journal = json.loads((CORE.STATE / "active.json").read_bytes())
        assert journal["status"] == "rolled-back"
        # The journal keeps the reviewed ECR references even though Docker uses local aliases here.
        assert journal["previous"] == CORE.REPOSITORY + "@" + RELEASE.PREVIOUS
        assert journal["image"] == (release.image if with_history else CORE.REPOSITORY + "@" + RELEASE.CANDIDATE)
        assert journal["revision"] == release.revision
        if published:
            assert journal["ecr_candidate"] == CORE.REPOSITORY + "@" + RELEASE.CANDIDATE
        assert journal["failed_stage"] == "verify-backend-and-alb"
        assert journal["failure"] == "Intentional failure after Hotfix verification."
        assert release.health("backend", previous_id)
        assert release.preflight(ORIGIN) == ("401", [])
        assert database_state(release, with_history) == state
        release.unchanged_redis()
    print("PASS: exact old/new V5 images, CORS policy, rehydration, forced failure, "
          "old image/settings restoration, unchanged Redis, CHECK and Flyway state.")


if __name__ == "__main__":
    main(*sys.argv[1:])
