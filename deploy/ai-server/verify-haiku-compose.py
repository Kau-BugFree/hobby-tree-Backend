"""Validate effective Compose configuration without starting services or reading keys."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent


def config(paid):
    command = ["docker", "compose", "-f", str(root / "compose.yml")]
    if paid:
        command += ["-f", str(root / "compose.haiku.yml")]
    command += ["config", "--format", "json"]
    return json.loads(subprocess.run(command, check=True, capture_output=True, text=True).stdout)


base, paid = config(False), config(True)
assert base["services"]["recommender"] == paid["services"]["recommender"]
old = base["services"]["coach"]
new = paid["services"]["coach"]
assert old["environment"].get("COACH_LLM_ENABLED", "false") == "false"
assert new["environment"]["COACH_LLM_ENABLED"] == "true"
assert "ANTHROPIC_API_KEY" not in new["environment"]
assert {x["source"] for x in old["secrets"]} == {"coach_service_token"}
assert {x["source"] for x in new["secrets"]} == {"coach_service_token", "anthropic_api_key"}
for key in set(old) | set(new):
    if key not in {"environment", "secrets"}:
        assert old.get(key) == new.get(key), key
assert {key: value for key, value in new["environment"].items() if key != "COACH_LLM_ENABLED"} == old["environment"]
assert paid["secrets"]["anthropic_api_key"]["file"] == "/opt/hobbytree-ai/secrets/anthropic_api_key"
print("PASS: explicit Haiku opt-in; recommender, images, ports, state and service token unchanged")
