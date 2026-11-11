"""Run the real coach offline in this container; emit timings without secrets.

Mount writable disposable /state (including a COPY of the shared index), readonly
/models and /probe. Run with --network none, --read-only and --tmpfs /tmp.
Never mount an existing coach session DB or the source index writable.
"""

import json
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def memory():
    values = {}
    for name in ("memory.current", "memory.peak"):
        path = Path("/sys/fs/cgroup") / name
        if path.exists():
            values[name] = int(path.read_text())
    stat = Path("/sys/fs/cgroup/memory.stat")
    if stat.exists():
        values.update({line.split()[0]: int(line.split()[1])
                       for line in stat.read_text().splitlines()
                       if line.split()[0] in {"anon", "file", "kernel"}})
    if server.poll() is None:
        for line in Path(f"/proc/{server.pid}/status").read_text().splitlines():
            if line.startswith(("VmRSS:", "VmHWM:")):
                key, size, _unit = line.split()
                values[key.rstrip(":")] = int(size) * 1024
    return values


token = secrets.token_urlsafe(48)
environment = dict(os.environ)
environment["COACH_BACKEND_TOKEN"] = token
environment["ANTHROPIC_API_KEY"] = ""
started = time.monotonic()
server = subprocess.Popen(
    ["python", "-m", "uvicorn", "app.main:create_app", "--factory", "--host",
     "127.0.0.1", "--port", "8003", "--workers", "1", "--no-access-log"],
    env=environment,
)
result = {"mode": os.environ.get("COACH_EMBEDDING_ENABLED"), "requests": [],
          "cpuLimit": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
          "memoryLimit": Path("/sys/fs/cgroup/memory.max").read_text().strip()}


def request(path, body=None, authorized=True):
    headers = {"Content-Type": "application/json"}
    if authorized:
        headers["X-Coach-Service-Token"] = token
    req = urllib.request.Request(
        "http://127.0.0.1:8003" + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body else None,
        headers=headers,
    )
    with urllib.request.urlopen(req, timeout=90) as response:
        return json.load(response)


try:
    deadline = started + 900
    while True:
        if server.poll() is not None:
            raise RuntimeError("Coach process exited before readiness")
        try:
            health = request("/health")
            break
        except (urllib.error.URLError, TimeoutError):
            if time.monotonic() > deadline:
                raise TimeoutError("Coach readiness exceeded 900 seconds")
            time.sleep(1)
    result.update(startupSeconds=round(time.monotonic() - started, 3), health=health,
                  readyMemory=memory())
    assert not health["llm_configured"], "External model must be disabled"
    if environment.get("COACH_EMBEDDING_ENABLED") == "true":
        retrieval = health["retrieval"]
        assert retrieval["embedding_model"] and not retrieval["embedding_error"]
        assert retrieval["reranker_model"] and not retrieval["reranker_error"]
        assert {"activities", "knowledge"} <= set(retrieval["dense_index_scopes"])
    first = None
    first_body = None
    # Real two conversations and follow-up turns, using synthetic local IDs only.
    for conversation, message in [
        (810001, "오늘 20분 안에 할 요리 활동 추천해줘"),
        (810001, "두 번째 걸로 할게"),
        (810001, "현재 단계를 쉽게 설명해줘"),
        (810002, "간장 드레싱 만드는 활동 추천해줘"),
        (810002, "첫 번째 걸로 할게"),
    ]:
        body = {"conversationId": conversation, "requestId": str(uuid.uuid4()),
                "message": message, "hobbyCode": "cooking"}
        before = time.monotonic()
        reply = request("/backend/coach/chat", body)
        assert reply["conversationId"] == conversation and reply["answer"]
        result["requests"].append({"message": message,
            "seconds": round(time.monotonic() - before, 3), "response": reply,
            "memory": memory()})
        if first is None:
            first, first_body = reply, body
    repeated = request("/backend/coach/chat", first_body)
    assert repeated["replayed"] and repeated["answer"] == first["answer"]
    try:
        request("/backend/coach/chat", first_body, authorized=False)
        raise AssertionError("Missing service token was accepted")
    except urllib.error.HTTPError as error:
        assert error.code == 401
    result["replayAndAuthenticationPassed"] = True
    if os.environ.get("MEASURE_CONCURRENCY") == "2":
        def concurrent_turn(conversation):
            before = time.monotonic()
            reply = request("/backend/coach/chat", {
                "conversationId": conversation, "requestId": str(uuid.uuid4()),
                "message": "간장 드레싱 만드는 활동 추천해줘", "hobbyCode": "cooking"})
            assert reply["conversationId"] == conversation and reply["answer"]
            return {"seconds": round(time.monotonic() - before, 3), "mode": reply["mode"]}
        with ThreadPoolExecutor(max_workers=2) as executor:
            result["concurrentRequests"] = list(executor.map(concurrent_turn, [810003, 810004]))
    result["finalMemory"] = memory()
finally:
    server.terminate()
    try:
        server.wait(timeout=15)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait()
    Path(os.environ.get("MEASUREMENT_FILE", "/state/measurement.json")).write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "requests"},
                     ensure_ascii=False))
