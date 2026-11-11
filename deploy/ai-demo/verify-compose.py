"""HTTP smoke check from a disposable client on the local internal network."""

import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid

token = Path("/run/secrets/coach_service_token").read_text("utf-8").strip()


def call(service, path, body=None, authenticated=True):
    headers = {"Content-Type": "application/json"}
    if service == "coach:8003" and authenticated:
        headers["X-Coach-Service-Token"] = token
    request = urllib.request.Request("http://" + service + path,
        data=json.dumps(body, ensure_ascii=False).encode() if body is not None else None,
        headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


recommendation = call("recommender:8000", "/api/v1/recommendations", {})
assert recommendation["status"] == "recommended"
health = call("coach:8003", "/health")
assert not health["llm_configured"]
assert health["retrieval"]["dense_index_scopes"] == ["activities", "knowledge"]
assert health["retrieval"]["embedding_error"] is None
assert health["retrieval"]["reranker_error"] is None
body = {"conversationId": 820001, "requestId": str(uuid.uuid4()),
        "message": "오늘 20분 안에 할 요리 활동 추천해줘", "hobbyCode": "cooking"}
first = call("coach:8003", "/backend/coach/chat", body)
assert first["conversationId"] == 820001 and first["suggestedActivities"]
again = call("coach:8003", "/backend/coach/chat", body)
assert again["replayed"] and again["answer"] == first["answer"]
try:
    call("coach:8003", "/backend/coach/chat", body, authenticated=False)
    raise AssertionError("Unauthenticated request accepted")
except urllib.error.HTTPError as error:
    assert error.code == 401
print(json.dumps({"recommenderStatus": recommendation["status"],
                  "coachMode": first["mode"], "replay": True,
                  "missingTokenRejected": True, "llmConfigured": False}))
