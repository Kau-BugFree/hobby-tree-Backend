#!/usr/bin/env python3
"""Deploy one reviewed image without restarting systemd, Redis or migrations."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import zipfile

REGION = "ap-northeast-2"
REGISTRY = "494915081306.dkr.ecr.ap-northeast-2.amazonaws.com"
REPOSITORY = REGISTRY + "/hobbytree/backend"
INSTANCE = "i-0d71fcf646b7210f2"
TARGET_GROUP = "arn:aws:elasticloadbalancing:ap-northeast-2:494915081306:targetgroup/hobbytree-backend-tg/07f8122d999a820d"
ROOT = Path("/opt/hobbytree")
ENV_FILE = ROOT / ".env.ec2"
CONTRACT = Path("/etc/hobbytree/deployment-contract.json")
STATE = Path("/var/lib/hobbytree/deployments")
UNIT = "hobbytree-backend.service"
APPROVAL = "no-migrations-backward-compatible-downtime-approved"
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
FORWARD_SECONDS = 1200
ROLLBACK_SECONDS = 720
TERMINAL = {"succeeded", "rolled-back", "no-op"}


class ReleaseError(Exception):
    """A sanitized operator-facing failure, never a raw subprocess response."""


class RestoredRelease(ReleaseError):
    """The requested release failed but the previous image was verified healthy."""


class RecoveryRequired(ReleaseError):
    """Recovery could not be fully verified or durably recorded."""


def failure_summary(error):
    """Retain fixed internal diagnostics or an exception class, never foreign text."""
    return str(error) if isinstance(error, ReleaseError) else type(error).__name__


def require(condition, message):
    """Fail closed with a fixed diagnostic when a deployment invariant fails."""
    if not condition:
        raise ReleaseError(message)


def validate_request(digest, previous, revision, request_id, approval):
    """Validate every SSM argument before paths or command arguments are built."""
    require(bool(DIGEST.fullmatch(digest) and DIGEST.fullmatch(previous)), "Invalid digest.")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "Full source SHA required.")
    require(bool(re.fullmatch(r"[0-9]{1,20}-[0-9]{1,5}", request_id)), "Invalid request ID.")
    require(approval == APPROVAL, "Explicit deployment acknowledgements required.")


def sha(value):
    """Hash non-secret configuration or schema bytes for drift detection."""
    return hashlib.sha256(value).hexdigest()


def runtime_controls(settings):
    """Prevent alternate Spring/JVM sources from enabling DDL or migrations."""
    expected = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate"}
    require(all(settings.get(k) == v for k, v in expected.items()), "Flyway off, validate and ec2 profile required.")
    require(not any(k.startswith("SPRING_") and k not in expected for k in settings), "Alternate Spring property sources disabled.")
    require(not {"JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}.intersection(settings), "Alternate JVM property sources disabled.")
    require(not {"DB_PASSWORD", "DB_USERNAME", "JWT_SECRET", "REDIS_PASSWORD", "AWS_ACCESS_KEY_ID",
                 "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE"}.intersection(settings),
            "Credential values must not be image or container environment variables.")
    require(not re.search(r"(^|\s)-D", settings.get("JAVA_TOOL_OPTIONS", "")), "JVM system property overrides disabled.")


def protected(path, directory=False):
    """Require root-owned, non-writable-by-others paths without any symlinks."""
    path = Path(path)
    for item in (path, *path.parents):
        require(not item.is_symlink(), "Symlink in protected path.")
        info = item.stat()
        require(info.st_uid == 0 and not info.st_mode & 0o022, "Untrusted path ownership or mode.")
    require(path.is_dir() if directory else path.is_file(), "Required protected path missing.")


def atomic_write(path, data):
    """Persist a root-only file atomically, including a directory fsync."""
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".release-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        parent = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextmanager
def release_lock():
    """Serialize deployments and reject abandoned or failed recovery journals."""
    import fcntl

    require(sys.platform == "linux" and os.geteuid() == 0, "Linux root required.")
    # Inspect existing ancestors before creating directories as root.
    for parent in reversed((STATE, *STATE.parents)):
        if parent.exists() or parent.is_symlink():
            protected(parent, directory=True)
        else:
            parent.mkdir(mode=0o700)
    descriptor = os.open(STATE / "lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "a+b") as lock:
        os.chmod(STATE / "lock", 0o600)
        protected(STATE / "lock")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ReleaseError("Another deployment holds the host lock.") from error
        active = STATE / "active.json"
        if active.exists():
            protected(active)
            require(json.loads(active.read_text(encoding="utf-8"))["status"] in TERMINAL, "Incomplete release: manual recovery required.")
        yield


def image_in_env(original, image):
    """Replace exactly one literal image line while retaining other settings."""
    lines = original.decode("utf-8").splitlines(keepends=True)
    indices = [i for i, line in enumerate(lines) if line.startswith("BACKEND_IMAGE=")]
    require(len(indices) == 1, "Exactly one BACKEND_IMAGE entry required.")
    lines[indices[0]] = "BACKEND_IMAGE=" + image + "\n"
    return "".join(lines).encode("utf-8")


def schema_fingerprint(jar):
    """Compare bounded Flyway SQL payloads without executing an image or SQL."""
    info = Path(jar).lstat()
    require(stat.S_ISREG(info.st_mode), "Regular application jar required; symlinks are disabled.")
    require(info.st_size <= 256 * 1024 * 1024, "Application jar too large.")
    with zipfile.ZipFile(jar) as archive:
        files = [x for x in archive.infolist() if x.filename.startswith("BOOT-INF/classes/db/migration/") and not x.is_dir()]
        require(0 < len(files) <= 1000, "Missing or oversized migration set.")
        require(len({x.filename for x in files}) == len(files), "Duplicate migration paths.")
        require(sum(x.file_size for x in files) <= 2 * 1024 * 1024, "Migration data too large.")
        digest = hashlib.sha256()
        for item in sorted(files, key=lambda x: x.filename):
            name = item.filename.removeprefix("BOOT-INF/classes/db/migration/")
            require(bool(re.fullmatch(r"V[0-9]+(?:[._][0-9]+)*__[A-Za-z0-9_]+\.sql", name)), "Unsupported migration layout.")
            content = archive.read(item).replace(b"\r\n", b"\n").replace(b"\r", b"\n")
            digest.update(name.encode() + b"\0" + hashlib.sha256(content).digest())
        return digest.hexdigest()


class Commands:
    """Use instance credentials only; capture and discard all raw command logs."""

    def __init__(self, docker_config):
        """Avoid inherited AWS profiles, exported keys and Compose overrides."""
        self.env = {"PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin", "HOME": "/root",
                    "LANG": "C.UTF-8", "DOCKER_CONFIG": str(docker_config), "AWS_PAGER": "",
                    "AWS_CLI_AUTO_PROMPT": "off", "AWS_MAX_ATTEMPTS": "2",
                    "AWS_SHARED_CREDENTIALS_FILE": "/dev/null", "AWS_CONFIG_FILE": "/dev/null",
                    "AWS_EC2_METADATA_V1_DISABLED": "true"}

    def run(self, arguments, deadline, timeout=30, data=None):
        """Bound requests by an absolute phase deadline and sanitize every failure."""
        remaining = deadline - time.monotonic()
        require(remaining > 0, "Deployment phase deadline exceeded.")
        try:
            result = subprocess.run(arguments, input=data, capture_output=True, timeout=min(timeout, remaining), env=self.env)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ReleaseError("Deployment command unavailable or timed out.") from error
        require(time.monotonic() < deadline, "Deployment phase deadline exceeded.")
        require(result.returncode == 0, "Deployment command failed; raw output withheld.")
        return result.stdout

    def json(self, arguments, deadline, timeout=30):
        """Decode controlled command results without propagating parser snippets."""
        try:
            return json.loads(self.run(arguments, deadline, timeout))
        except (ValueError, TypeError) as error:
            raise ReleaseError("Invalid deployment command response.") from error


class Deployment:
    """Perform a journaled backend-only replacement with bounded rollback."""

    def __init__(self, commands, temporary, digest, previous, revision, request_id):
        """Keep only public release identifiers in journal metadata."""
        self.cmd, self.temporary = commands, Path(temporary)
        self.image, self.previous = REPOSITORY + "@" + digest, REPOSITORY + "@" + previous
        self.revision, self.request_id = revision, request_id
        self.deadline = time.monotonic() + FORWARD_SECONDS
        self.record = {"request": request_id, "image": self.image, "previous": self.previous,
                       "revision": revision, "status": "preparing"}
        self.directory = STATE / request_id
        self.compose_file = ROOT / "compose.ec2.yml"

    def compose(self, *arguments, timeout=30):
        """Use the existing project and explicit files, never dependency updates."""
        return self.cmd.run(["docker", "compose", "--project-name", "hobbytree", "--project-directory", str(ROOT),
                             "--env-file", str(ENV_FILE), "-f", str(self.compose_file), *arguments],
                            self.deadline, timeout)

    def container(self, service):
        """Inspect a single expected Compose service without printing its environment."""
        info = self.cmd.json(["docker", "inspect", "--format", "{{json .}}", "hobbytree-" + service + "-1"], self.deadline)
        labels = info["Config"].get("Labels") or {}
        require(labels.get("com.docker.compose.project") == "hobbytree" and
                labels.get("com.docker.compose.service") == service, "Unexpected Compose container.")
        return info

    def health(self, service, image_id=None):
        """Require Docker running/healthy and, for backend, the exact image ID."""
        info = self.container(service)
        require(info["State"]["Running"] and info["State"].get("Health", {}).get("Status") == "healthy", "Container not healthy.")
        if service == "backend" and hasattr(self, "backend_config"):
            self.backend_runtime(info)
        if image_id:
            require(info["Image"] == image_id, "Running backend image differs from the requested image.")
        return info

    def backend_runtime(self, info):
        """Check live privilege, memory, mount and port settings against approved Compose."""
        host = info["HostConfig"]
        require(host.get("Memory") == 1024 ** 3 and host.get("MemorySwap") == 1024 ** 3
                and host.get("ReadonlyRootfs") is True and host.get("Privileged") is False,
                "Backend resource or privilege configuration drift.")
        config = info["Config"]
        require(config.get("User") == "10001:10001" and config.get("Entrypoint") == ["/app/docker-entrypoint.sh"]
                and not config.get("Cmd"), "Backend execution configuration drift.")
        expected = {v["target"]: (v["source"], not v.get("read_only", False))
                    for v in self.backend_config["volumes"] if v["type"] == "bind"}
        actual = {v["Destination"]: (v["Source"], v["RW"]) for v in info["Mounts"] if v["Type"] == "bind"}
        require(actual == expected and not any(v["Type"] == "volume" for v in info["Mounts"]), "Backend mount drift.")
        ports = self.backend_config.get("ports", [])
        require(len(ports) == 1 and ports[0]["target"] == 8080, "Unsupported backend port contract.")
        expected_port = {"HostIp": ports[0].get("host_ip", "0.0.0.0"), "HostPort": str(ports[0]["published"])}
        require(host.get("PortBindings") == {"8080/tcp": [expected_port]}, "Backend published port drift.")

    def image_info(self, reference):
        """Allow only the existing non-root amd64 application image contract."""
        info = self.cmd.json(["docker", "image", "inspect", "--format", "{{json .}}", reference], self.deadline)
        require(info["Os"] == "linux" and info["Architecture"] == "amd64", "linux/amd64 image required.")
        require(type(info.get("Size")) is int and 0 < info["Size"] <= 1024 ** 3, "Image exceeds the reviewed size budget.")
        config = info["Config"]
        require(config.get("User") == "10001:10001" and config.get("Entrypoint") == ["/app/docker-entrypoint.sh"]
                and not config.get("Cmd") and not config.get("Volumes"), "Unsupported image runtime contract.")
        return info

    def target_state(self):
        """Inspect only the fixed EC2/port target; no ALB writes are permitted."""
        value = self.cmd.json(["aws", "elbv2", "describe-target-health", "--region", REGION,
                               "--target-group-arn", TARGET_GROUP, "--targets", "Id=" + INSTANCE + ",Port=8080",
                               "--query", "TargetHealthDescriptions[].TargetHealth.State", "--output", "json"], self.deadline)
        require(isinstance(value, list) and len(value) == 1, "ALB target registration missing.")
        return value[0]

    def verify_target_contract(self):
        """Reject target-group health-setting drift from the reviewed deployment budget."""
        result = self.cmd.json(["aws", "elbv2", "describe-target-groups", "--region", REGION,
                                "--target-group-arns", TARGET_GROUP, "--output", "json"], self.deadline)
        groups = result.get("TargetGroups", [])
        require(len(groups) == 1, "Unexpected target-group response.")
        group = groups[0]
        expected = {"TargetGroupArn": TARGET_GROUP, "TargetType": "instance", "Port": 8080,
                    "HealthCheckPath": "/actuator/health", "HealthCheckIntervalSeconds": 30, "HealthyThresholdCount": 5,
                    "UnhealthyThresholdCount": 2, "HealthCheckTimeoutSeconds": 5, "HealthCheckProtocol": "HTTP",
                    "HealthCheckPort": "traffic-port", "Matcher": {"HttpCode": "200"}}
        require(all(group.get(key) == value for key, value in expected.items()), "ALB contract changed; review timing before deployment.")

    def verify_contract(self):
        """Check reviewed host files, current unit and existing protected secrets."""
        protected(CONTRACT)
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        self.contract = contract
        expected_paths = {str(ROOT / "compose.ec2.yml"), str(ROOT / "scripts/validate-ec2-config.py"),
                          "/etc/systemd/system/" + UNIT}
        require(set(contract["files"]) == expected_paths, "Unexpected deployment contract.")
        for name, expected in contract["files"].items():
            protected(name)
            require(sha(Path(name).read_bytes()) == expected, "Reviewed deployment file changed.")
        protected(ENV_FILE)
        require(stat.S_IMODE(ENV_FILE.stat().st_mode) == 0o600, "Deployment settings must be root-only.")
        self.original = ENV_FILE.read_bytes()
        require(len(self.original) <= 65536, "Deployment settings too large.")
        allowed = {"AWS_REGION", "BACKEND_IMAGE", "DB_SECRET_ID", "AUTH_SECRET_ID", "AUTH_TRUSTED_PROXY_CIDRS",
                   "LOGGING_LEVEL_HOBBYTREE_AUTH_PROXY", "KAKAO_APP_ID", "GOOGLE_CLIENT_ID", "REDIS_HOST",
                   "REDIS_PORT", "REDIS_SECRETS_DIR", "DB_APP_USERNAME", "DB_URL", "APP_SECRETS_DIR", "RDS_CA_BUNDLE", "BACKEND_BIND_IP"}
        keys = {line.strip().partition("=")[0] for line in self.original.decode().splitlines() if line.strip() and not line.lstrip().startswith("#")}
        require(keys <= allowed, "Unreviewed deployment environment keys.")
        require(not re.search(rb"(?mi)^[A-Z0-9_]*(?:PASSWORD|TOKEN|ACCESS_KEY|JWT_SECRET)[A-Z0-9_]*=", self.original), "Credential values do not belong in deployment settings.")
        self.cmd.run(["python3", str(ROOT / "scripts/validate-ec2-config.py"), "--env-file", str(ENV_FILE), "--check-secrets"], self.deadline)
        docker_root = self.cmd.run(["docker", "info", "--format", "{{.DockerRootDir}}"], self.deadline).decode().strip()
        require(Path(docker_root).is_absolute(), "Invalid Docker data path.")
        for directory in (Path(docker_root), STATE):
            require(shutil.disk_usage(directory).free >= 3 * 1024 ** 3, "At least 3 GiB free deployment disk space required.")
        properties = self.cmd.run(["systemctl", "show", UNIT, "--no-pager", "--property=Type,RemainAfterExit,ActiveState,SubState,FragmentPath"], self.deadline).decode()
        values = dict(line.split("=", 1) for line in properties.splitlines() if "=" in line)
        require(all(values.get(k) == v for k, v in {"Type": "oneshot", "RemainAfterExit": "yes", "ActiveState": "active", "SubState": "exited", "FragmentPath": "/etc/systemd/system/" + UNIT}.items()), "Expected active oneshot systemd unit required.")
        config = json.loads(self.compose("config", "--format", "json"))
        require(config.get("name") == "hobbytree" and set(config["services"]) == {"backend", "redis"}, "Unexpected Compose project/services.")
        backend = config["services"]["backend"]
        self.backend_config = backend
        env = backend["environment"]
        runtime_controls(env)
        self.compose_env = env
        require(not backend.get("build") and not backend.get("command") and not backend.get("entrypoint"), "Compose image/command override not supported.")
        current = self.health("backend")
        self.current_id = current["Id"]
        self.old_image_id = current["Image"]
        require(current["Config"]["Image"] == backend["image"], "Running and configured image references differ.")
        actual_env = dict(x.split("=", 1) for x in current["Config"].get("Env", []) if "=" in x)
        runtime_controls(actual_env)
        require(all(actual_env.get(k) == str(v) for k, v in env.items()), "Running backend configuration drift.")
        self.redis = self.health("redis")
        old = self.image_info(self.old_image_id)
        digests = [x for x in old.get("RepoDigests", []) if x.startswith(REPOSITORY + "@")]
        require(self.previous in digests, "Expected current digest does not match the running image.")
        self.verify_target_contract()
        require(self.target_state() == "healthy", "Current ALB target must be healthy.")

    def extract_schema(self, image, name):
        """Copy only the jar from an unstarted, isolated temporary container."""
        container = self.cmd.run(["docker", "create", "--pull", "never", "--network", "none", "--label", "hobbytree.release.staging=" + self.request_id,
                                  "--entrypoint", "/bin/true", image], self.deadline).decode().strip()
        require(bool(re.fullmatch(r"[0-9a-f]{64}", container)), "Invalid staging container ID.")
        try:
            jar = self.temporary / (name + ".jar")
            self.cmd.run(["docker", "cp", container + ":/app/hobbytree.jar", str(jar)], self.deadline, 60)
            return schema_fingerprint(jar)
        finally:
            # Remove only the ID returned by this create, never services or volumes.
            self.cmd.run(["docker", "rm", container], time.monotonic() + 30, 15)

    def prepare(self):
        """Authenticate privately, pre-pull and reject migration differences before downtime."""
        password = self.cmd.run(["aws", "ecr", "get-login-password", "--region", REGION], self.deadline)
        self.cmd.run(["docker", "login", "--username", "AWS", "--password-stdin", REGISTRY], self.deadline, data=password)
        self.cmd.run(["docker", "pull", "--platform", "linux/amd64", self.image], self.deadline, 300)
        candidate = self.image_info(self.image)
        effective = dict(x.split("=", 1) for x in candidate["Config"].get("Env", []) if "=" in x)
        effective.update(self.compose_env)
        runtime_controls(effective)
        require((candidate["Config"].get("Labels") or {}).get("org.opencontainers.image.revision") == self.revision, "Image source revision mismatch.")
        self.new_image_id = candidate["Id"]
        require(self.image in candidate.get("RepoDigests", []), "Pulled digest mismatch.")
        old_schema = self.extract_schema(self.old_image_id, "previous")
        new_schema = self.extract_schema(self.image, "candidate")
        if hasattr(self, "migration_gate"):
            self.migration_gate.prepare(self, old_schema, new_schema)
        else:
            require(old_schema == new_schema, "Migration set changed: separate explicit migration approval/release required.")
        require(ENV_FILE.read_bytes() == self.original and self.health("backend")["Id"] == self.current_id, "Host changed during preparation.")
        self.unchanged_redis()
        for name, expected in self.contract["files"].items():
            protected(name)
            require(sha(Path(name).read_bytes()) == expected, "Host contract changed during preparation.")
        self.verify_target_contract()
        self.record.update(previous_image_id=self.old_image_id, image_id=self.new_image_id,
                           settings_sha256=sha(self.original), schema_sha256=old_schema)

    def journal(self, status):
        """Durably record public metadata and retain the original non-secret settings."""
        self.record["status"] = status
        atomic_write(self.directory / "release.json", json.dumps(self.record, sort_keys=True).encode())
        atomic_write(STATE / "active.json", json.dumps(self.record, sort_keys=True).encode())
        print("Release state: " + status, flush=True)

    def unchanged_redis(self):
        """Compare every Redis mount field without depending on inspect array order."""
        now = self.health("redis")
        current_mounts = sorted(json.dumps(mount, sort_keys=True) for mount in now["Mounts"])
        previous_mounts = sorted(json.dumps(mount, sort_keys=True) for mount in self.redis["Mounts"])
        require(now["Id"] == self.redis["Id"] and current_mounts == previous_mounts, "Redis identity or mounts changed.")

    def start_backend(self):
        """Recreate only backend, using pre-pulled images and existing secrets."""
        if hasattr(self, "migration_gate"):
            self.migration_gate.check()
        self.compose("up", "-d", "--no-build", "--pull", "never", "--no-deps", "--force-recreate",
                     "--wait", "--wait-timeout", "180", "backend", timeout=210)

    def verify_running(self, image_id):
        """Require exact Docker image health and a fresh stable ALB observation window."""
        if hasattr(self, "migration_gate"):
            self.migration_gate.check()
        self.health("backend", image_id)
        self.unchanged_redis()
        self.verify_target_contract()
        until = min(self.deadline, time.monotonic() + 420)
        healthy_since = None
        while time.monotonic() < until:
            self.health("backend", image_id)
            self.unchanged_redis()
            state = self.target_state()
            now = time.monotonic()
            if now >= until:
                break
            healthy_since = (healthy_since if healthy_since is not None else now) if state == "healthy" else None
            if healthy_since is not None and now - healthy_since >= 150:
                if hasattr(self, "migration_gate"):
                    self.migration_gate.check()
                return
            time.sleep(min(10, max(0, until - time.monotonic())))
        raise ReleaseError("ALB did not remain healthy for the required observation window.")

    def execute(self):
        """Snapshot before mutation; rollback any failed or interrupted replacement."""
        require(not self.directory.exists(), "Request already recorded; do not resubmit blindly.")
        self.verify_contract()
        self.prepare()
        candidate_settings = image_in_env(self.original, self.image)
        self.directory.mkdir(mode=0o700)
        atomic_write(self.directory / "settings.before", self.original)
        compose_bytes = (ROOT / "compose.ec2.yml").read_bytes()
        require(sha(compose_bytes) == self.contract["files"][str(ROOT / "compose.ec2.yml")], "Compose changed before snapshot.")
        self.compose_file = self.directory / "compose.approved.yml"
        atomic_write(self.compose_file, compose_bytes)
        if self.image == self.previous:
            self.verify_running(self.old_image_id)
            self.journal("no-op")
            return True
        self.journal("prepared")
        try:
            # Journal first: a killed process leaves a blocking recovery record.
            self.journal("changing")
            atomic_write(ENV_FILE, candidate_settings)
            self.record["stage"] = "start-backend"
            self.start_backend()
            self.record["stage"] = "verify-backend-and-alb"
            self.verify_running(self.new_image_id)
            self.journal("succeeded")
            return True
        except (Exception, KeyboardInterrupt) as error:
            self.record["failed_stage"] = self.record.get("stage", "change-settings")
            self.record["failure"] = failure_summary(error)
            try:
                self.journal("rolling-back")
            except Exception:
                # A broken audit disk must not prevent the best-effort restore.
                pass
            self.deadline = time.monotonic() + ROLLBACK_SECONDS
            try:
                self.record["rollback_stage"] = "restore-settings"
                if hasattr(self, "migration_gate"):
                    self.migration_gate.check()
                require(ENV_FILE.read_bytes() in (self.original, candidate_settings), "Settings changed externally; manual recovery required.")
                atomic_write(ENV_FILE, image_in_env(self.original, self.previous))
                self.record["rollback_stage"] = "start-previous-backend"
                self.start_backend()
                self.record["rollback_stage"] = "verify-previous-backend-and-alb"
                self.verify_running(self.old_image_id)
                self.journal("rolled-back")
            except (Exception, KeyboardInterrupt) as rollback_error:
                self.record["rollback_failure"] = failure_summary(rollback_error)
                try:
                    self.journal("rollback-failed")
                except Exception:
                    pass
                raise RecoveryRequired("Rollback failed: stop further deployments and recover manually.") from None
            raise RestoredRelease("Deployment failed; previous image restored and verified.") from None


def main():
    """Run only on the approved host under lock; never echo exception internals."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("digest", "previous", "revision", "request-id", "approval"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--v45-approval", default=None)
    args = parser.parse_args()
    try:
        validate_request(args.digest, args.previous, args.revision, args.request_id, args.approval)
        with release_lock(), tempfile.TemporaryDirectory(prefix="hobbytree-ecr-", dir="/run") as credentials, \
                tempfile.TemporaryDirectory(prefix=".staging-", dir=STATE) as temporary:
            def interrupted(signum, frame):
                """Unwind on a first termination request so normal rollback can run."""
                raise KeyboardInterrupt()

            signal.signal(signal.SIGTERM, interrupted)
            deployment = Deployment(Commands(credentials), temporary, args.digest, args.previous, args.revision, args.request_id)
            gate = None
            try:
                if args.v45_approval is not None:
                    import importlib.util
                    helper = Path(__file__).with_name("v45_gate.py")
                    protected(helper)
                    spec = importlib.util.spec_from_file_location("v45_gate", helper)
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)
                    gate = module.Gate(args.v45_approval, sys.modules[__name__])
                    deployment.migration_gate = gate
                deployment.execute()
            finally:
                if gate is not None:
                    gate.close()
        print("PASS: requested release is healthy; values and remote output withheld.")
        return 0
    except RestoredRelease:
        print("FAIL: requested deployment failed; previous image restored and verified.", file=sys.stderr)
        return 10
    except RecoveryRequired:
        print("FAIL: rollback failed or could not be recorded; manual recovery required.", file=sys.stderr)
        return 20
    except ReleaseError as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    except BaseException:
        # Do not print subprocess exceptions, secrets, parsed configuration or tracebacks.
        print("FAIL: deployment did not complete. Inspect the protected release journal before retrying.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
