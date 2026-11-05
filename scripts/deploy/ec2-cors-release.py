#!/usr/bin/env python3
"""Deploy the fixed V5 CORS hotfix using the existing same-schema release engine."""

import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import signal
import stat
import sys
import tempfile


PREVIOUS = "sha256:11c492cc17e3fe781479bfa126bc1d6df2ec2ef3df074c4de5bd5f8d68dde608"
CANDIDATE = "sha256:24c487f35348c34b6e2046f111f3c4457ebe68d4dde532b3b40e6d0e5b74481c"
PREVIOUS_REVISION = "058c1410a9bbb4805157f6b659064440267032b3"
REVISION = "50f2fcd198241e8d7b6ab594057652bfc22a0d37"
SCHEMA = "84bbd4049e3e926c5b725d3c3213b2d6980202973aaba31299f1a4999dee0026"
HERE = Path(__file__).absolute().parent
INSTALL = Path("/opt/hobbytree/scripts/deploy-cors")


def load(name, path):
    """Load a local release dependency. The production CLI checks pins first."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify_installation():
    """Check pinned root-owned inputs before importing release code as root."""
    if HERE != INSTALL or sys.platform != "linux" or os.geteuid() != 0:
        raise PermissionError("Run the installed Linux root release command.")
    pins = {
        HERE / "ec2-release.py": "87c56667002ac8bc85e90b9a11953f5a06c4845913f86f9b8d14610c35e81cbd",
        HERE.parent / "ec2-cors-config.py": "8df5fda57de7428aa062692c0908d72717382c93bd46fcb3b2cf22e350c773e1",
        INSTALL.parents[1] / "compose.ec2.yml": "baf1ee80a7fec36cded7b275d2d9b527dfbc1289c103508bf04fccb101cdd8f0",
    }
    for path, expected in pins.items():
        for item in (path, *path.parents):
            info = item.lstat()
            required = stat.S_ISREG if item == path else stat.S_ISDIR
            if not required(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                raise PermissionError("Unprotected release input.")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise PermissionError("Release input hash mismatch.")


if __name__ == "__main__":
    try:
        verify_installation()
    except (OSError, PermissionError):
        sys.exit("FAIL: the reviewed CORS release files are missing or changed.")

CORE = load("ec2_release", HERE / "ec2-release.py")
CORS = load("ec2_cors_config", HERE.parent / "ec2-cors-config.py")


class CorsRelease(CORE.Deployment):
    """Add one journaled setting to the unchanged image and ALB transaction."""

    def __init__(self, commands, temporary, request_id, allowed):
        super().__init__(commands, temporary, CANDIDATE, PREVIOUS, REVISION, request_id)
        self.origins = CORS.origins(allowed)
        self.desired = CORS.setting(allowed)

    def verify_contract(self):
        """Require the old container and Compose to agree with the reviewed settings."""
        self.before = CORS.persistent_value()
        super().verify_contract()
        CORE.require(CORE.image_in_env(self.original, self.previous) == self.original,
                     "Previous image settings must be exactly restorable.")
        CORE.require(self.compose_env.get(CORS.KEY) == CORS.value_of(self.before),
                     "Compose CORS settings drift.")
        self.check_container_origins(self.health("backend"), self.before)

    def check_container_origins(self, container, expected):
        """Reject missing, extra or duplicate CORS environment settings."""
        values = [entry.split("=", 1)[1] for entry in container["Config"].get("Env", [])
                  if entry.startswith(CORS.KEY + "=")]
        value = CORS.value_of(expected)
        CORE.require(values == ([] if value is None else [value]), "Backend CORS environment differs.")

    def prepare(self):
        """Require the reviewed V5 pair in addition to the core equal-schema check."""
        super().prepare()
        previous = self.image_info(self.old_image_id)
        CORE.require((previous["Config"].get("Labels") or {}).get("org.opencontainers.image.revision") ==
                     PREVIOUS_REVISION and self.record["schema_sha256"] == SCHEMA,
                     "The reviewed V5 rollback image and schema are required.")

    def journal(self, status):
        """Snapshot the exact prior CORS state before the core can mutate the host."""
        if status == "prepared":
            self.record["cors_before_exists"] = self.before is not None
            self.record["cors_before_sha256"] = CORE.sha(self.before) if self.before is not None else None
            self.record["cors_after_sha256"] = CORE.sha(self.desired) if self.desired is not None else None
            if self.before is not None:
                CORE.atomic_write(self.directory / "cors.before", self.before)
        super().journal(status)

    def start_backend(self):
        """Set or restore CORS before starting only the requested Backend image."""
        rollback = self.record.get("rollback_stage") == "start-previous-backend"
        current = CORS.persistent_value()
        if rollback:
            CORE.require(current in (self.before, self.desired),
                         "CORS settings changed externally; manual recovery required.")
        else:
            CORE.require(current == self.before, "CORS settings changed during preparation.")
        desired = self.before if rollback else self.desired
        if current != desired:
            CORS.write(desired)
        super().start_backend()

    def request(self, *headers, method="GET"):
        """Probe the running container without printing responses or settings."""
        result = self.cmd.run(
            ["docker", "exec", "hobbytree-backend-1", "curl", "--silent", "--show-error", "--max-time", "4",
             "--request", method, "--dump-header", "-", "--output", "/dev/null", "--write-out", "\n%{http_code}",
             *headers, "http://127.0.0.1:8080/api/v1/users/me"],
            self.deadline, 8).decode("ascii")
        head, code = result.rsplit("\n", 1)
        allow = re.findall(r"(?im)^access-control-allow-origin:[ \t]*([^\r\n]+)", head)
        return code, allow

    def verify_running(self, image_id):
        """Require healthy ALB, exact persisted config and the intended HTTP behavior."""
        super().verify_running(image_id)
        rollback = image_id == self.old_image_id
        expected = self.before if rollback else self.desired
        CORE.require(CORS.persistent_value() == expected,
                     "CORS settings did not reach the approved state.")
        self.check_container_origins(self.health("backend", image_id), expected)
        CORE.require(self.request() == ("401", []), "Protected API authentication changed.")
        if rollback:
            return
        for origin in self.origins:
            CORE.require(self.preflight(origin) == ("200", [origin]), "Approved CORS preflight failed.")
        # Local development Origin must stay outside the production allowlist.
        CORE.require(self.preflight("http://localhost:5173") == ("403", []),
                     "Unapproved CORS origin was not rejected.")

    def preflight(self, origin):
        """Issue the browser's token-free preflight for the protected GET."""
        return self.request("-H", "Origin: " + origin, "-H", "Access-Control-Request-Method: GET",
                            "-H", "Access-Control-Request-Headers: authorization", method="OPTIONS")


def main():
    """Require explicit acknowledgement and use the shared deployment lock and journal."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request-id", required=True)
    parser.add_argument("--approval", required=True)
    parser.add_argument("--origins", default="")
    args = parser.parse_args()
    try:
        CORE.validate_request(CANDIDATE, PREVIOUS, REVISION, args.request_id, args.approval)
        with CORE.release_lock(), tempfile.TemporaryDirectory(prefix="hobbytree-ecr-", dir="/run") as credentials, \
                tempfile.TemporaryDirectory(prefix=".staging-", dir=CORE.STATE) as temporary:
            def interrupted(_signum, _frame):
                """Unwind into the core's rollback handler on termination."""
                raise KeyboardInterrupt

            signal.signal(signal.SIGTERM, interrupted)
            CorsRelease(CORE.Commands(credentials), temporary, args.request_id, args.origins).execute()
        print("PASS: V5 CORS release is healthy.")
        return 0
    except CORE.RestoredRelease:
        print("FAIL: release failed; previous image and CORS settings restored.", file=sys.stderr)
        return 10
    except CORE.RecoveryRequired:
        print("FAIL: rollback requires manual recovery; inspect the protected journal.", file=sys.stderr)
        return 20
    except BaseException:
        print("FAIL: release stopped; inspect the protected journal before retrying.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
