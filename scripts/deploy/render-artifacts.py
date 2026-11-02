#!/usr/bin/env python3
"""Render reviewable SSM/config artifacts offline; never call AWS or install files."""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    """Pin canonical Git LF bytes; host installation must preserve these bytes."""
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def artifacts():
    """Generate a fixed command document with ENV_VAR interpolation and strict inputs."""
    host_script = "/opt/hobbytree/scripts/deploy/ec2-release.py"
    script_hash = digest(ROOT / "scripts/deploy/ec2-release.py")
    approval = "no-migrations-backward-compatible-downtime-approved"
    parameters = {}
    for name, pattern in {
        "ImageDigest": "^sha256:[0-9a-f]{64}$", "ExpectedCurrentDigest": "^sha256:[0-9a-f]{64}$",
        "ImageRevision": "^[0-9a-f]{40}$", "RequestId": "^[0-9]{1,20}-[0-9]{1,5}$", "Approval": "^" + approval + "$",
    }.items():
        parameters[name] = {"type": "String", "interpolationType": "ENV_VAR", "allowedPattern": pattern}
    commands = [
        "set -eu", 'test "$(id -u)" = 0',
        'for path in /opt /opt/hobbytree /opt/hobbytree/scripts /opt/hobbytree/scripts/deploy; do',
        '  test ! -L "$path"',
        '  case "$(stat -c %u:%g:%a "$path")" in 0:0:755|0:0:700) ;; *) exit 1 ;; esac',
        'done',
        'test ! -L ' + host_script,
        'test "$(stat -c %u:%g:%a ' + host_script + ')" = 0:0:500',
        r"printf '%s  %s\n' '" + script_hash + "' '" + host_script + "' | sha256sum --check --status",
        ': "${SSM_ImageDigest:?ENV_VAR support required}" "${SSM_ExpectedCurrentDigest:?}" "${SSM_ImageRevision:?}" "${SSM_RequestId:?}" "${SSM_Approval:?}"',
        'exec env -i PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin HOME=/root LANG=C.UTF-8 /usr/bin/python3 -I ' + host_script +
        ' --digest "$SSM_ImageDigest" --previous "$SSM_ExpectedCurrentDigest" --revision "$SSM_ImageRevision" --request-id "$SSM_RequestId" --approval "$SSM_Approval"',
    ]
    document = {"schemaVersion": "2.2", "description": "Reviewed fixed backend-only digest release; no migration or Redis restart.",
                "parameters": parameters, "mainSteps": [{"action": "aws:runShellScript", "name": "deployBackend",
                "precondition": {"StringEquals": ["platformType", "Linux"]},
                "inputs": {"timeoutSeconds": "2100", "runCommand": commands}}]}
    files = {"/opt/hobbytree/compose.ec2.yml": "compose.ec2.yml",
             "/opt/hobbytree/scripts/validate-ec2-config.py": "scripts/validate-ec2-config.py",
             "/etc/systemd/system/hobbytree-backend.service": "deploy/hobbytree-backend.service"}
    contract = {"files": {target: digest(ROOT / source) for target, source in files.items()}}
    return {"deploy/ssm/HobbyTree-Dev-DeployBackend.json": document, "deploy/ec2-deployment-contract.json": contract}


def main():
    """Write generated files or fail when --check finds an unreviewed drift."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, value in artifacts().items():
        path = ROOT / name
        expected = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != expected:
                raise SystemExit("Deployment artifacts stale; render and review before committing.")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8", newline="\n")
    print("Deployment artifacts checked." if args.check else "Deployment artifacts rendered; nothing installed or uploaded.")


if __name__ == "__main__":
    main()
