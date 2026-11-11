#!/bin/bash
# Run only on the approved NEW Amazon Linux 2023 AI instance, via SSM.
set -euo pipefail
umask 077
test "$(id -u)" = 0
test ! -e /opt/hobbytree-ai/state/coach.sqlite
dnf install -y docker python3 iptables
command -v aws >/dev/null
systemctl enable --now docker
install -d -m 0755 /opt/hobbytree-ai/release /usr/local/lib/docker/cli-plugins
install -d -m 0700 /opt/hobbytree-ai/secrets
# SSM downloads use umask 077. Only these non-secret files must be readable by
# the container's unprivileged UID; keep runtime settings and token restricted.
chmod 0644 /opt/hobbytree-ai/release/coach-entrypoint.py /opt/hobbytree-ai/release/probe.py /opt/hobbytree-ai/release/recommender-request.json
# The Compose binary is downloaded separately and verified against the GitHub release digest.
echo '7758048ad38e580f22559603312874fec8bb638d021a9e64182385fc17cc12a2  /opt/hobbytree-ai/release/docker-compose-linux-x86_64' | sha256sum -c -
install -m 0755 /opt/hobbytree-ai/release/docker-compose-linux-x86_64 /usr/local/lib/docker/cli-plugins/docker-compose
python3 /opt/hobbytree-ai/release/install-assets.py
python3 - <<'PY'
import os
from pathlib import Path
import secrets
path = Path('/opt/hobbytree-ai/secrets/coach_service_token')
if not path.exists():
    with path.open('x') as stream:
        stream.write(secrets.token_urlsafe(48))
os.chown(path, 10001, 10001)
path.chmod(0o400)
PY
install -m 0644 /opt/hobbytree-ai/release/hobbytree-ai.service /etc/systemd/system/hobbytree-ai.service
systemctl daemon-reload
echo 'Host and assets prepared; images must be digest-verified before starting'
