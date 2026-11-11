"""Real offline HTTP checks for the pinned recommender image (no DB or LLM)."""

import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request


def call(path, body=None):
    req = urllib.request.Request("http://127.0.0.1:8000" + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


started = time.monotonic()
server = subprocess.Popen(["python", "-m", "uvicorn", "app.main:app",
                           "--host", "127.0.0.1", "--port", "8000", "--no-access-log"])
result = {"cases": []}
try:
    deadline = started + 30
    while True:
        if server.poll() is not None:
            raise RuntimeError("Recommender exited before readiness")
        try:
            result["health"] = call("/health")
            break
        except urllib.error.URLError:
            if time.monotonic() > deadline:
                raise TimeoutError("Recommender readiness exceeded 30 seconds")
            time.sleep(0.2)
    result["startupSeconds"] = round(time.monotonic() - started, 3)
    questionnaire = call("/api/v1/questionnaire")
    catalog = call("/api/v1/catalog")
    assert questionnaire and catalog
    example = json.loads(Path("/example/request.json").read_text("utf-8"))
    for expected, body in [
        ("recommended", example),
        ("browse", {"answers": {"q11": "안내 없이 직접 찾기"}}),
        ("needs_review", {"context_text": "알레르기 조건 확인이 필요해요"}),
        ("no_match", {"answers": {"q06": "직접 시간 입력"},
                      "custom_values": {"q06_minutes": 1}}),
    ]:
        before = time.monotonic()
        reply = call("/api/v1/recommendations", body)
        assert reply["status"] == expected, reply
        result["cases"].append({"expected": expected,
            "seconds": round(time.monotonic() - before, 4), "response": reply})
    try:
        call("/api/v1/recommendations", {"unknown": True})
        raise AssertionError("Unknown field accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 422
    result["validationPassed"] = True
    result["memory"] = {name: int((Path("/sys/fs/cgroup") / name).read_text())
                        for name in ("memory.current", "memory.peak")}
finally:
    server.terminate()
    try:
        server.wait(timeout=10)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()
    Path("/state/measurement.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "cases"}, ensure_ascii=False))
