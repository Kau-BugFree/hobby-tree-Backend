"""Opt-in disposable PostgreSQL 17 tests; never accept an operational DSN."""
import contextlib
from datetime import datetime
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import unittest
from uuid import uuid4
from unittest.mock import patch

from test_item_material_export import E, EPOCH, ROOT


@unittest.skipUnless(os.environ.get("ITEM_EXPORT_TEST_DSN"), "isolated PostgreSQL not configured")
class DatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import psycopg
        cls.psycopg = psycopg
        dsn = os.environ["ITEM_EXPORT_TEST_DSN"]
        if not re.fullmatch(r"postgresql://127\.0\.0\.1:[0-9]+/hobbytree_migration_ci", dsn):
            raise RuntimeError("Only disposable localhost migration CI database is allowed")
        cls.admin = psycopg.connect(dsn, autocommit=True)
        cls.name = "item_export_test_" + uuid4().hex
        cls.role = "item_export_reader_" + uuid4().hex
        cls.password = secrets.token_hex(32)
        cls.admin.execute("CREATE DATABASE " + cls.name)
        cls.db_dsn = dsn.replace("/hobbytree_migration_ci", "/" + cls.name)
        try:
            cls.writer = psycopg.connect(cls.db_dsn, autocommit=True)
            if cls.writer.info.server_version // 10000 != 17:
                raise RuntimeError("PostgreSQL 17 required")
            for path in sorted((ROOT / "src/main/resources/db/migration").glob("V*.sql"),
                               key=lambda p: int(re.match(r"V(\d+)", p.name).group(1))):
                cls.writer.execute(path.read_text(encoding="utf-8"))
            # Only this random database and role are mutated. No real account fixtures.
            from psycopg import sql
            cls.admin.execute(sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                sql.Identifier(cls.role), sql.Literal(cls.password)))
            cls.writer.execute("GRANT USAGE ON SCHEMA public TO " + cls.role)
            cls.writer.execute("GRANT SELECT ON public.hobbies, public.hobby_stages, "
                               "public.activities, public.activity_materials TO " + cls.role)
        except Exception:
            if hasattr(cls, "writer"):
                cls.writer.close()
            cls.admin.execute("DROP DATABASE " + cls.name)
            cls.admin.execute("DROP ROLE IF EXISTS " + cls.role)
            cls.admin.close()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.writer.close()
        cls.admin.execute("DROP DATABASE " + cls.name)
        cls.admin.execute("DROP ROLE " + cls.role)
        cls.admin.close()

    def setUp(self):
        self.writer.execute("DELETE FROM activity_materials; DELETE FROM activities; "
                            "DELETE FROM hobby_stages; DELETE FROM hobbies")
        self.writer.execute("""
            INSERT INTO hobbies(hobby_id,hobby_code,hobby_name,is_supported) VALUES
              (99001,'export-enabled','합성 취미',true), (99002,'export-disabled','합성 미지원',false);
            INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) VALUES
              (99001,99001,0,'합성'),(99002,99002,0,'합성');
            INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name,review_status) VALUES
              (99001,99001,'export-public','합성 공개','PUBLISHED'),
              (99002,99001,'export-draft','합성 비공개','DRAFT'),
              (99003,99002,'export-unsupported','합성 미지원','PUBLISHED'),
              (99004,99001,'export-shared','합성 공유','PUBLISHED');
            INSERT INTO activity_materials(activity_material_id,activity_id,material_name) VALUES
              (99001,99001,'계량스푼'),(99002,99002,'비공개 준비물'),
              (99003,99003,'미지원 준비물'),(99004,99004,'계량스푼');
        """)

    def reader(self):
        return self.psycopg.connect(self.db_dsn, user=self.role, password=self.password,
                                    autocommit=True)

    def test_scope_sharing_raw_names_and_versions_on_migrated_schema(self):
        before = self.writer.execute("SELECT activity_id,content_version FROM activities ORDER BY 1").fetchall()
        with self.reader() as reader:
            stamp, rows = E.read_snapshot(reader)
        doc = json.loads(E.build_document(rows, stamp, "synthetic-db", EPOCH, 1))
        self.assertEqual([m["materialId"] for m in doc["payload"]["materials"]], ["99001", "99004"])
        self.assertEqual([r[4] for r in rows], ["계량스푼", "계량스푼"])
        self.assertEqual(before, self.writer.execute("SELECT activity_id,content_version FROM activities ORDER BY 1").fetchall())

    def test_repeatable_snapshot_during_concurrent_name_and_visibility_change(self):
        with self.reader() as reader:
            owner = self
            class ConcurrentEdit:
                autocommit = True
                def transaction(self):
                    return reader.transaction()
                def execute(self, query, *args):
                    if query == E.READ_SQL:
                        owner.writer.execute("UPDATE activity_materials SET material_name='변경됨' WHERE activity_material_id=99001")
                        owner.writer.execute("UPDATE activities SET review_status='DRAFT' WHERE activity_id=99004")
                    return reader.execute(query, *args)
            _, rows = E.read_snapshot(ConcurrentEdit())
            self.assertEqual([r[0] for r in rows], [99001, 99004])
            self.assertEqual(rows[0][4], "계량스푼")
            _, later = E.read_snapshot(reader)
            self.assertEqual([r[0] for r in later], [99001])
            self.assertEqual(later[0][4], "변경됨")
            self.assertGreater(later[0][3], rows[0][3])

    def test_more_than_twenty_materials_not_truncated_and_empty_scope_is_explicit(self):
        self.writer.execute("INSERT INTO activity_materials(activity_id,material_name) "
                            "SELECT 99001, '합성 추가 ' || n FROM generate_series(1,21) n")
        with self.reader() as reader:
            stamp, rows = E.read_snapshot(reader)
            self.assertEqual(len(rows), 23)
            self.assertEqual(json.loads(E.build_document(rows, stamp, "synthetic-db", EPOCH, 1))["materialCount"], 23)
            self.writer.execute("UPDATE hobbies SET is_supported=false")
            stamp, rows = E.read_snapshot(reader)
            value = json.loads(E.build_document(rows, stamp, "synthetic-db", EPOCH, 2))
            self.assertEqual(value["materialCount"], 0)
            self.assertTrue(value["complete"])

    def test_readonly_transaction_is_database_enforced(self):
        with self.reader() as reader:
            class WriteProbe:
                autocommit = True
                def transaction(self):
                    return reader.transaction()
                def execute(self, query, *args):
                    if query == E.READ_SQL:
                        reader.execute("DELETE FROM public.activity_materials")
                    return reader.execute(query, *args)
            with self.assertRaises(self.psycopg.errors.ReadOnlySqlTransaction):
                E.read_snapshot(WriteProbe())
        self.assertEqual(self.writer.execute("SELECT count(*) FROM activity_materials").fetchone()[0], 4)

    def test_rls_partial_visibility_fails_instead_of_exporting_partial_list(self):
        self.writer.execute("ALTER TABLE activity_materials ENABLE ROW LEVEL SECURITY")
        try:
            with self.reader() as reader, self.assertRaises(self.psycopg.errors.InsufficientPrivilege):
                E.read_snapshot(reader)
        finally:
            self.writer.execute("ALTER TABLE activity_materials DISABLE ROW LEVEL SECURITY")

    def test_missing_permission_fails_without_output(self):
        self.writer.execute("REVOKE SELECT ON activities FROM " + self.role)
        try:
            with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service", side_effect=lambda _: self.reader()), \
                    contextlib.redirect_stderr(io.StringIO()):
                target = Path(tmp) / "export.json"
                code = E.main(["--service", "test", "--source-id", "synthetic-db", "--source-epoch", EPOCH,
                               "--source-sequence", "1", "--output", str(target)])
                self.assertEqual(code, 1)
                self.assertFalse(target.exists())
        finally:
            self.writer.execute("GRANT SELECT ON activities TO " + self.role)

    def test_actual_cli_service_connection_does_not_need_write_permissions(self):
        with tempfile.TemporaryDirectory() as tmp:
            service = Path(tmp) / "pg_service.conf"
            port = self.admin.info.port
            service.write_text(f"[export-test]\nhost=127.0.0.1\nport={port}\ndbname={self.name}\n"
                               f"user={self.role}\npassword={self.password}\nsslmode=disable\n", encoding="utf-8")
            service.chmod(0o600)
            env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
            env["PGSERVICEFILE"] = str(service)
            target = Path(tmp) / "export.json"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/export-item-materials.py"),
                                     "--service", "export-test", "--source-id", "synthetic-db",
                                     "--source-epoch", EPOCH, "--source-sequence", "1", "--output", str(target)],
                                    env=env, capture_output=True, text=True, timeout=45)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(target.read_bytes())["materialCount"], 2)
            self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_shared_source_scenarios_match_real_export_with_content_version_triggers(self):
        directory = ROOT / "docs/examples/item-sync-v1"
        manifest = json.loads((directory / "scenarios.json").read_bytes())
        source = manifest["initialSource"]
        # All seed rows belong to one transaction, matching the fixture's V18 version=1.
        with self.writer.transaction():
            self.writer.execute("DELETE FROM activity_materials; DELETE FROM activities; "
                                "DELETE FROM hobby_stages; DELETE FROM hobbies")
            for id_, supported in source["hobbies"]:
                self.writer.execute("INSERT INTO hobbies(hobby_id,hobby_code,hobby_name,is_supported) "
                                    "VALUES(%s,%s,'합성 취미',%s)", (id_, f"synthetic-{id_}", supported))
                self.writer.execute("INSERT INTO hobby_stages(hobby_stage_id,hobby_id,stage_number,stage_name) "
                                    "VALUES(%s,%s,0,'합성 단계')", (id_, id_))
            for id_, hobby_id, status in source["activities"]:
                self.writer.execute("INSERT INTO activities(activity_id,hobby_stage_id,activity_code,activity_name,review_status) "
                                    "VALUES(%s,%s,%s,'합성 활동',%s)", (id_, hobby_id, f"synthetic-{id_}", status))
            for id_, activity_id, name in source["materials"]:
                self.writer.execute("INSERT INTO activity_materials(activity_material_id,activity_id,material_name) "
                                    "VALUES(%s,%s,%s)", (id_, activity_id, name))
        with self.reader() as reader:
            for case in manifest["sourceCases"]:
                with self.subTest(case=case["id"]):
                    change = case["sourceChange"]
                    if change:
                        with self.writer.transaction():
                            operation = change["operation"]
                            if operation == "addMaterial":
                                self.writer.execute("INSERT INTO activity_materials(activity_material_id,activity_id,material_name) "
                                                    "VALUES(%s,%s,%s)", (change["id"], change["activityId"], change["name"]))
                            elif operation == "renameMaterial":
                                self.writer.execute("UPDATE activity_materials SET material_name=%s WHERE activity_material_id=%s",
                                                    (change["name"], change["id"]))
                            elif operation == "setReviewStatus":
                                self.writer.execute("UPDATE activities SET review_status=%s WHERE activity_id=%s",
                                                    (change["status"], change["id"]))
                            elif operation == "setSupported":
                                self.writer.execute("UPDATE hobbies SET is_supported=%s WHERE hobby_id=%s",
                                                    (change["supported"], change["id"]))
                            elif operation == "disableAllHobbies":
                                self.writer.execute("UPDATE hobbies SET is_supported=false")
                            else:
                                self.fail("Unknown synthetic operation")
                    expected_bytes = (directory / case["file"]).read_bytes()
                    expected = json.loads(expected_bytes)
                    stamp, rows = E.read_snapshot(reader)
                    self.assertIsNotNone(stamp.utcoffset())
                    # Compare full output while fixing only delivery metadata/time to the fixture.
                    actual = E.build_document(rows, datetime.fromisoformat(expected["generatedAt"].replace("Z", "+00:00")),
                                              expected["sourceId"], expected["sourceEpoch"], int(expected["sourceSequence"]),
                                              export_id=expected["exportId"])
                    self.assertEqual(actual, expected_bytes)


if __name__ == "__main__":
    unittest.main()
