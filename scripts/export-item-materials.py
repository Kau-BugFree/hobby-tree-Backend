#!/usr/bin/env python3
"""Explicit, read-only BE catalog export. Never imported by the web application."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from datetime import datetime, timezone
from uuid import UUID, uuid4

SCHEMA = "hobbytree.item-material-export.v1"
SCOPE = "all-supported-published-activity-materials"
MAX_ROWS = 10_000
MAX_BYTES = 1024 * 1024
MAX_BIGINT = 2**63 - 1
READ_SQL = """
SELECT m.activity_material_id, m.activity_id, h.hobby_id,
       a.content_version, m.material_name
FROM public.activity_materials m
JOIN public.activities a ON a.activity_id = m.activity_id
JOIN public.hobby_stages hs ON hs.hobby_stage_id = a.hobby_stage_id
JOIN public.hobbies h ON h.hobby_id = hs.hobby_id
WHERE h.is_supported = TRUE AND a.review_status = 'PUBLISHED'
ORDER BY m.activity_material_id ASC
LIMIT %s
"""


class ExportError(Exception):
    """Contains only stable codes, never source data or connection details."""


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def positive_integer(value):
    if type(value) is not int or not 1 <= value <= MAX_BIGINT:
        raise ExportError("INVALID_SOURCE_INTEGER")
    return str(value)


def validate_identity(source_id, source_epoch, source_sequence):
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", source_id):
        raise ExportError("INVALID_SOURCE_ID")
    try:
        if str(UUID(source_epoch)) != source_epoch:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise ExportError("INVALID_SOURCE_EPOCH") from None
    positive_integer(source_sequence)


def read_snapshot(connection):
    # The caller creates a fresh autocommit connection: the mode is set before
    # any data read. A query/permission/schema failure aborts the whole export.
    if not connection.autocommit:
        raise ExportError("FRESH_AUTOCOMMIT_CONNECTION_REQUIRED")
    with connection.transaction():
        connection.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        connection.execute("SET LOCAL statement_timeout = '30s'")
        connection.execute("SET LOCAL lock_timeout = '5s'")
        connection.execute("SET LOCAL idle_in_transaction_session_timeout = '30s'")
        # Incomplete RLS visibility must fail rather than masquerade as a full list.
        connection.execute("SET LOCAL row_security = off")
        generated_at = connection.execute("SELECT transaction_timestamp()").fetchone()[0]
        rows = connection.execute(READ_SQL, (MAX_ROWS + 1,)).fetchall()
    return generated_at, rows


def build_document(rows, generated_at, source_id, source_epoch, source_sequence,
                   export_id=None):
    validate_identity(source_id, source_epoch, source_sequence)
    if len(rows) > MAX_ROWS:
        raise ExportError("EXPORT_ROW_LIMIT")
    if not isinstance(generated_at, datetime) or generated_at.utcoffset() is None:
        raise ExportError("INVALID_SNAPSHOT_TIME")
    materials = []
    last_id = 0
    activity_versions = {}
    activity_hobbies = {}
    for material_id, activity_id, hobby_id, version, name in rows:
        ids = [positive_integer(v) for v in (material_id, activity_id, hobby_id, version)]
        if material_id <= last_id:
            raise ExportError("UNORDERED_OR_DUPLICATE_MATERIAL")
        last_id = material_id
        if (not isinstance(name, str) or not name.strip() or len(name) > 160
                or any(ord(c) < 32 or ord(c) == 127 for c in name)):
            raise ExportError("INVALID_MATERIAL_NAME")
        # Preserve the original name, including whitespace and casing. The AI
        # owns query_key normalization and must not collapse material references.
        if (activity_id in activity_versions and
                (activity_versions[activity_id] != version or
                 activity_hobbies[activity_id] != hobby_id)):
            raise ExportError("INCONSISTENT_ACTIVITY")
        activity_versions[activity_id] = version
        activity_hobbies[activity_id] = hobby_id
        materials.append(dict(zip(
            ("materialId", "activityId", "hobbyId", "activityContentVersion", "name"),
            (*ids, name))))
    payload = {"schemaVersion": SCHEMA, "scope": SCOPE, "materials": materials}
    document = {
        "exportId": str(UUID(export_id)) if export_id else str(uuid4()),
        "sourceId": source_id,
        "sourceEpoch": source_epoch,
        "sourceSequence": str(source_sequence),
        "generatedAt": generated_at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "complete": True,
        "materialCount": len(materials),
        "activityCount": len(activity_versions),
        "payloadSha256": hashlib.sha256(canonical(payload)).hexdigest(),
        "payload": payload,
    }
    data = canonical(document) + b"\n"
    if len(data) > MAX_BYTES:
        raise ExportError("EXPORT_BYTE_LIMIT")
    return data


def publish_exclusive(output, data):
    """Publish complete bytes atomically, without replacing any existing path."""
    output = Path(output)
    # Same-directory staging + link: os.replace would overwrite a racing file.
    # Unsupported filesystems fail closed. No non-atomic fallback.
    fd, staging = tempfile.mkstemp(prefix=".material-export-", dir=output.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(staging, output)
    finally:
        os.unlink(staging)


def connect_service(service):
    import psycopg  # Optional CLI dependency, not loaded by unit tests.
    return psycopg.connect(
        service=service, autocommit=True, connect_timeout=5,
        application_name="hobbytree-material-export",
        options="-c default_transaction_read_only=on",
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description="BE 공개 준비물 전체 export (DB 읽기 전용)")
    parser.add_argument("--service", required=True, help="명시적 libpq service 이름; DSN/암호 금지")
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--source-epoch", required=True, help="환경에 고정된 UUID; 실행마다 변경 금지")
    parser.add_argument("--source-sequence", required=True, type=int,
                        help="단일 실행자가 발급한 증가 순번; DB contentVersion이 아님")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        validate_identity(args.source_id, args.source_epoch, args.source_sequence)
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.service):
            raise ExportError("INVALID_SERVICE_NAME")
        if os.path.lexists(args.output):
            raise ExportError("OUTPUT_EXISTS")
        with connect_service(args.service) as connection:
            generated_at, rows = read_snapshot(connection)
        data = build_document(rows, generated_at, args.source_id,
                              args.source_epoch, args.source_sequence)
        publish_exclusive(args.output, data)
        print(json.dumps({"status": "EXPORTED", "bytes": len(data), "materialCount": len(rows)}))
        return 0
    except ExportError as exc:
        print(json.dumps({"status": str(exc)}), file=sys.stderr)
    except Exception:
        # Driver messages may contain credentials, hostnames or source names.
        print('{"status":"EXPORT_FAILED"}', file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
