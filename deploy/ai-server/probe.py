"""Synthetic server acceptance checks; run inside each new AI container.

Print only readiness, timing and contract results, never tokens or conversations.
Coach checks persist synthetic turns in the NEW AI state, never in the app DB.
"""

import json
from pathlib import Path
import sqlite3
import sys
import time
import urllib.error
import urllib.request
import uuid

service = sys.argv[1]
port = 8003 if service == 'coach' else 8000
token = Path('/run/secrets/coach_service_token').read_text().strip() if service == 'coach' else ''


def call(path, body=None, authorized=True):
    """Send a request only to this container's loopback server."""
    headers = {'Content-Type': 'application/json'}
    if authorized and token:
        headers['X-Coach-Service-Token'] = token
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + path,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


health = call('/health')
report = {'service': service, 'health': health, 'checks': []}
if service == 'coach':
    assert not health['llm_configured']
    retrieval = health['retrieval']
    assert retrieval['embedding_model'] and not retrieval['embedding_error']
    assert retrieval['reranker_model'] and not retrieval['reranker_error']
    assert {'activities', 'knowledge'} <= set(retrieval['dense_index_scopes'])
    conversation = 900000000000 + int(time.time())
    first_body = None
    first_reply = None
    for offset, message in [(0, '오늘 20분 안에 할 요리 활동 추천해줘'),
                            (0, '두 번째 걸로 할게'),
                            (1, '간장 드레싱 만드는 활동 추천해줘')]:
        body = {'conversationId': conversation + offset, 'requestId': str(uuid.uuid4()),
                'message': message, 'hobbyCode': 'cooking'}
        started = time.monotonic()
        reply = call('/backend/coach/chat', body)
        assert reply['conversationId'] == body['conversationId'] and reply['answer']
        report['checks'].append({'conversationOffset': offset, 'mode': reply['mode'],
                                 'seconds': round(time.monotonic() - started, 3)})
        if first_body is None:
            first_body, first_reply = body, reply
    replay = call('/backend/coach/chat', first_body)
    assert replay['replayed'] and replay['answer'] == first_reply['answer']
    try:
        call('/backend/coach/chat', first_body, authorized=False)
        raise AssertionError('Unauthenticated request accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    report['replayAnd401'] = True
    with sqlite3.connect('file:/state/coach.sqlite?mode=ro', uri=True) as database:
        session_ids = []
        for offset, expected_requests in [(0, 2), (1, 1)]:
            rows = database.execute(
                "SELECT id FROM sessions WHERE json_extract(payload,'$.origin_request_id')=?",
                ('backend-conversation:' + str(conversation + offset),)).fetchall()
            assert len(rows) == 1
            session_ids.append(rows[0][0])
            count = database.execute('SELECT COUNT(*) FROM requests WHERE session_id=?',
                                     (rows[0][0],)).fetchone()[0]
            assert count == expected_requests
        assert session_ids[0] != session_ids[1]
    report['separatePersistentSessions'] = True
else:
    assert call('/api/v1/questionnaire') and call('/api/v1/catalog')
    example = json.loads(Path('/tmp/recommender-request.json').read_text())
    for expected, body in [
        ('recommended', example),
        ('browse', {'answers': {'q11': '안내 없이 직접 찾기'}}),
        ('needs_review', {'context_text': '알레르기 조건 확인이 필요해요'}),
        ('no_match', {'answers': {'q06': '직접 시간 입력'}, 'custom_values': {'q06_minutes': 1}}),
    ]:
        reply = call('/api/v1/recommendations', body)
        assert reply['status'] == expected
        report['checks'].append(expected)
    try:
        call('/api/v1/recommendations', {'unknown': True})
        raise AssertionError('Invalid request accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 422
    report['validation422'] = True
print(json.dumps(report, ensure_ascii=False))
