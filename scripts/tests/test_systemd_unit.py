"""Parse the unit in a throwaway root; never install or start a service."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix" and shutil.which("systemd-analyze"), "Linux systemd parser required")
class SystemdSyntaxTests(unittest.TestCase):
    def test_unit_parses_with_isolated_dependencies_and_executables(self):
        with tempfile.TemporaryDirectory(prefix="hobbytree-unit-") as directory:
            root = Path(directory)
            units = root / "etc/systemd/system"
            units.mkdir(parents=True)
            source = Path(__file__).resolve().parents[2] / "deploy/hobbytree-backend.service"
            (units / source.name).write_text(source.read_text(), encoding="utf-8")
            for target in ("basic", "sysinit", "shutdown", "network-online"):
                (units / (target + ".target")).write_text("[Unit]\nDefaultDependencies=no\n")
            (units / "docker.service").write_text(
                "[Unit]\nDefaultDependencies=no\n[Service]\nType=oneshot\nExecStart=/usr/bin/docker\n")
            for name in ("docker", "python3"):
                executable = root / "usr/bin" / name
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_text("#!/bin/sh\nexit 0\n")
                executable.chmod(0o755)
            result = subprocess.run(["systemd-analyze", "--root", str(root), "verify", source.name],
                                    capture_output=True, encoding="utf-8", timeout=30)
            self.assertEqual(result.returncode, 0, "systemd unit syntax validation failed: " + result.stderr)
