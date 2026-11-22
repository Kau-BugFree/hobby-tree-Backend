"""Render and validate a candidate only; never start containers or contact AWS."""
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess


def validate(config):
    services = config["services"]
    assert set(services) == {"coach", "recommender"}
    for name, service in services.items():
        assert re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", service["image"])
        assert service["pull_policy"] == "never" and service["read_only"] is True
        assert service["cap_drop"] == ["ALL"] and service["security_opt"] == ["no-new-privileges:true"]
        assert not service.get("privileged") and not service.get("network_mode")
        assert not any(service.get(field) for field in
                       ("entrypoint", "command", "build", "cap_add", "pid", "ipc", "devices", "gpus"))
        assert service["restart"] == "unless-stopped"
        assert len(service["ports"]) == 1
        port = service["ports"][0]
        address = ipaddress.IPv4Address(port["host_ip"])
        assert any(address in ipaddress.IPv4Network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        expected = 8003 if name == "coach" else 8000
        assert int(port["published"]) == port["target"] == expected
    coach = services["coach"]
    recommender = services["recommender"]
    assert not any(recommender.get(field) for field in ("environment", "secrets", "volumes"))
    assert coach["user"] == "10001:10001"
    assert float(coach["cpus"]) == 1.5 and float(services["recommender"]["cpus"]) == .5
    assert int(coach["mem_limit"]) == int(coach["memswap_limit"]) == 6 * 1024**3
    assert int(services["recommender"]["mem_limit"]) == 512 * 1024**2
    env = coach["environment"]
    assert set(env) == {"COACH_ALLOWED_HOSTS", "COACH_DEVICE", "COACH_EMBEDDING_ENABLED", "COACH_RERANKER_ENABLED", "COACH_LLM_ENABLED", "OMP_NUM_THREADS", "MKL_NUM_THREADS"}
    assert env["COACH_DEVICE"] == "cuda" and env["COACH_LLM_ENABLED"] == "false"
    assert env["COACH_EMBEDDING_ENABLED"] == env["COACH_RERANKER_ENABLED"] == "true"
    assert env["OMP_NUM_THREADS"] == env["MKL_NUM_THREADS"] == "1"
    devices = coach["deploy"]["resources"]["reservations"]["devices"]
    assert devices == [{"capabilities": ["gpu"], "count": 1, "driver": "nvidia"}]
    volumes = {row["target"]: row for row in coach["volumes"]}
    assert set(volumes) == {"/state", "/models"}
    assert volumes["/models"]["read_only"] is True
    assert all(row["type"] == "bind" and row["bind"]["create_host_path"] is False for row in volumes.values())
    assert volumes["/state"]["source"] != volumes["/models"]["source"]
    assert coach["secrets"] == [{"source": "coach_service_token", "target": "/run/secrets/coach_service_token"}]
    assert set(config["secrets"]) == {"coach_service_token"}
    return True


def render():
    env = dict(os.environ)
    env.update(AI_PRIVATE_IP="10.0.1.2", GPU_COACH_IMAGE="example.invalid/coach@sha256:" + "a" * 64,
        RECOMMENDER_IMAGE="example.invalid/recommender@sha256:" + "b" * 64,
        GPU_TRIAL_STATE_DIR="/disposable/gpu-state", GPU_TRIAL_MODELS_DIR="/readonly/models",
        GPU_TRIAL_TOKEN_FILE="/synthetic/secrets/service-token")
    raw = subprocess.check_output(["docker", "compose", "-f", str(Path(__file__).with_name("compose.candidate.yml")),
        "config", "--format", "json"], env=env)
    return json.loads(raw)


if __name__ == "__main__":
    assert validate(render())
    print("Small GPU candidate Compose validated; nothing started")
