"""Offline contract and actual CLI entry-point regressions; no DB or secrets."""
import contextlib
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("item_export", ROOT / "scripts/export-item-materials.py")
E = importlib.util.module_from_spec(spec)
spec.loader.exec_module(E)
NOW = datetime(2026, 11, 25, 0, 0, tzinfo=timezone.utc)
EPOCH = "11111111-1111-4111-8111-111111111111"
EXPORT_ID = "22222222-2222-4222-8222-222222222222"
ROWS = [(101, 11, 1, 2, '계량 "스푼"'), (102, 12, 2, 1, "  Ｓtraße  "),
        (103, 13, 3, 1, "책갈피"), (104, 14, 1, 1, '계량 "스푼"')]


def document(rows=ROWS, **kwargs):
    return E.build_document(rows, NOW, "synthetic-hobbytree", EPOCH, 1,
                            export_id=EXPORT_ID, **kwargs)


class ContractTests(unittest.TestCase):
    def test_example_and_hash_and_reference_preservation(self):
        raw = document()
        self.assertEqual(raw, (ROOT / "docs/examples/item-material-export-v1.json").read_bytes())
        value = json.loads(raw)
        self.assertEqual(value["materialCount"], 4)
        self.assertEqual(value["activityCount"], 4)
        self.assertEqual(value["payload"]["materials"][1]["name"], "  Ｓtraße  ")
        self.assertEqual(value["payloadSha256"], hashlib.sha256(E.canonical(value["payload"])).hexdigest())
        self.assertNotIn(b"quantity", raw)

    def test_empty_is_complete_but_requires_importer_review(self):
        value = json.loads(document([]))
        self.assertTrue(value["complete"])
        self.assertEqual(value["materialCount"], 0)
        self.assertEqual(value["activityCount"], 0)
        self.assertEqual(value["payload"]["materials"], [])

    def test_bigint_ids_are_decimal_strings(self):
        value = json.loads(document([(2**63 - 1, 12, 1, 1, "책")]))
        self.assertEqual(value["payload"]["materials"][0]["materialId"], str(2**63 - 1))

    def test_160_unicode_codepoints_and_raw_name_preserved(self):
        name = "😀" * 160
        value = json.loads(document([(1, 1, 1, 1, name)]))
        self.assertEqual(value["payload"]["materials"][0]["name"], name)

    def test_invalid_names_fail_whole_export(self):
        for name in (None, "", "  ", "a" * 161, "a\nb", "a\x00b", "a\x7fb"):
            with self.subTest(name=repr(name)), self.assertRaises(E.ExportError):
                document([(1, 1, 1, 1, name)])

    def test_invalid_ids_order_and_activity_consistency(self):
        for rows in ([(0, 1, 1, 1, "책")], [(True, 1, 1, 1, "책")],
                     [(1, 1, 1, 0, "책")], [ROWS[1], ROWS[0]], [ROWS[0], ROWS[0]],
                     [(1, 1, 1, 1, "책"), (2, 1, 1, 2, "스푼")],
                     [(1, 1, 1, 1, "책"), (2, 1, 2, 1, "스푼")]):
            with self.subTest(rows=rows), self.assertRaises(E.ExportError):
                document(rows)

    def test_row_and_byte_caps_do_not_truncate(self):
        with self.assertRaisesRegex(E.ExportError, "ROW_LIMIT"):
            document([ROWS[0]] * (E.MAX_ROWS + 1))
        rows = [(i, i, 1, 1, "😀" * 160) for i in range(1, 2001)]
        with self.assertRaisesRegex(E.ExportError, "BYTE_LIMIT"):
            document(rows)
        size = len(document())
        with patch.object(E, "MAX_BYTES", size):
            self.assertEqual(len(document()), size)
        with patch.object(E, "MAX_BYTES", size - 1), self.assertRaises(E.ExportError):
            document()

    def test_source_identity_and_sequence_validation(self):
        for identity in (("bad id", EPOCH, 1), ("good", "bad", 1),
                         ("good", EPOCH, 0), ("good", EPOCH, 2**63)):
            with self.subTest(identity=identity), self.assertRaises(E.ExportError):
                E.build_document([], NOW, *identity)

    def test_payload_hash_is_independent_of_delivery_metadata(self):
        first = json.loads(document())
        second = json.loads(E.build_document(ROWS, NOW, "synthetic-hobbytree", EPOCH, 2))
        self.assertEqual(first["payloadSha256"], second["payloadSha256"])
        self.assertNotEqual(first["exportId"], second["exportId"])


class CliTests(unittest.TestCase):
    def args(self, path):
        return ["--service", "synthetic", "--source-id", "synthetic-hobbytree",
                "--source-epoch", EPOCH, "--source-sequence", "1", "--output", str(path)]

    def test_entrypoint_readonly_and_atomic_success(self):
        connection = MagicMock(autocommit=True)
        connection.__enter__.return_value = connection
        connection.execute.return_value.fetchone.return_value = (NOW,)
        connection.execute.return_value.fetchall.return_value = ROWS
        with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service", return_value=connection), \
                contextlib.redirect_stdout(io.StringIO()):
            path = Path(tmp) / "export.json"
            self.assertEqual(E.main(self.args(path)), 0)
            self.assertTrue(json.loads(path.read_bytes())["complete"])
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ["export.json"])
        calls = connection.execute.call_args_list
        self.assertIn("REPEATABLE READ READ ONLY", calls[0].args[0])
        self.assertEqual(calls[-1].args, (E.READ_SQL, (E.MAX_ROWS + 1,)))

    def test_db_error_is_redacted_and_no_file_published(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service", side_effect=RuntimeError("secret-host-password")), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            path = Path(tmp) / "export.json"
            self.assertEqual(E.main(self.args(path)), 1)
            self.assertFalse(path.exists())
            self.assertNotIn("secret", err.getvalue())

    def test_existing_output_does_not_connect_or_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service") as connect, \
                contextlib.redirect_stderr(io.StringIO()):
            path = Path(tmp) / "export.json"
            path.write_bytes(b"preserved")
            self.assertEqual(E.main(self.args(path)), 1)
            connect.assert_not_called()
            self.assertEqual(path.read_bytes(), b"preserved")

    def test_invalid_service_is_rejected_before_connect(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service") as connect, \
                contextlib.redirect_stderr(io.StringIO()):
            args = self.args(Path(tmp) / "export.json")
            args[1] = "postgresql://not-allowed"
            self.assertEqual(E.main(args), 1)
            connect.assert_not_called()

    def test_limit_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(E, "connect_service"), \
                patch.object(E, "read_snapshot", return_value=(NOW, ROWS)), \
                patch.object(E, "MAX_BYTES", 1), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(E.main(self.args(Path(tmp) / "export.json")), 1)
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_racing_file_and_link_failure_preserve_destination_cleanup_staging(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "export.json"
            path.write_bytes(b"preserved")
            with self.assertRaises(FileExistsError):
                E.publish_exclusive(path, document())
            self.assertEqual(path.read_bytes(), b"preserved")
            with patch.object(E.os, "link", side_effect=OSError("unsupported")), self.assertRaises(OSError):
                E.publish_exclusive(Path(tmp) / "other.json", document())
            self.assertEqual([p.name for p in Path(tmp).iterdir()], ["export.json"])

    def test_fsync_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(E.os, "fsync", side_effect=OSError("disk")):
            with self.assertRaises(OSError):
                E.publish_exclusive(Path(tmp) / "export.json", document())
            self.assertEqual(list(Path(tmp).iterdir()), [])

    def test_preexisting_transaction_rejected(self):
        connection = MagicMock(autocommit=False)
        with self.assertRaises(E.ExportError):
            E.read_snapshot(connection)
        connection.execute.assert_not_called()


if __name__ == "__main__":
    unittest.main()
