"""Run the host transaction against a disposable Docker project with no AWS access."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("cors_release", ROOT / "scripts/deploy/ec2-cors-release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
CORE, CORS = RELEASE.CORE, RELEASE.CORS
ORIGIN = "https://frontend.example"


def command(*args):
    """Run only local CI commands and suppress raw output on failure."""
    return subprocess.run(args, capture_output=True, check=True, timeout=220).stdout


class Rehearsal(RELEASE.CorsRelease):
    """Use CI config instead of EC2/systemd and resolve the two verified local images."""

    def compose(self, *args, timeout=30):
        """Keep the production Compose file and add only the isolated DB fixture."""
        return command("docker", "compose", "-p", "hobbytree", "--project-directory", str(ROOT),
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
                                        (self.image, self.new_image_id, RELEASE.REVISION)):
            info, = json.loads(command("docker", "image", "inspect", tag))
            assert info["Id"] == image_id
            assert info["Config"]["Labels"]["org.opencontainers.image.revision"] == revision
            assert self.extract_schema(tag, revision[:8]) == RELEASE.SCHEMA

    def verify_running(self, image_id):
        """Use real CORS and Redis checks, then force rollback after Hotfix passes."""
        super().verify_running(image_id)
        if image_id == self.new_image_id:
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


def database_state(release):
    """Read the isolated rollback barrier and verify Flyway never created history."""
    return release.compose("exec", "-T", "postgres", "psql", "-XqAt",
                           "-U", "ci_migration", "-d", "hobbytree_ci", "-v", "ON_ERROR_STOP=1", "-c",
                           "SELECT pg_get_constraintdef(oid), convalidated FROM pg_constraint "
                           "WHERE conrelid='public.experiences'::regclass "
                           "AND conname='hobbytree_v45_rollback_guard'; "
                           "SELECT to_regclass('public.flyway_schema_history') IS NULL;")


def main():
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
    with tempfile.TemporaryDirectory(dir=CORE.STATE) as scratch:
        release = Rehearsal(CORE.Commands(scratch), scratch, "1-1", ORIGIN)
        release.image = "hobbytree/backend:cors-rehearsal-new"
        release.previous = "hobbytree/backend:cors-rehearsal-old"
        release.old_image_id = previous_id
        release.new_image_id = candidate_id
        state = database_state(release)
        assert b"COMING_SOON" in state and state.endswith(b"|t\nt\n")
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
        assert CORE.ENV_FILE.read_bytes() == original
        assert CORS.persistent_value() == previous_setting
        assert (release.directory / "cors.before").read_bytes() == previous_setting
        journal = json.loads((CORE.STATE / "active.json").read_bytes())
        assert journal["status"] == "rolled-back"
        assert journal["previous"] == release.previous and journal["image"] == release.image
        assert release.health("backend", previous_id)
        assert release.preflight(ORIGIN) == ("401", [])
        assert database_state(release) == state
        release.unchanged_redis()
    print("PASS: exact old/new V5 images, allowed CORS, rehydration, forced failure, "
          "old image/settings restoration, unchanged Redis and CHECK, Flyway never invoked.")


if __name__ == "__main__":
    main()
