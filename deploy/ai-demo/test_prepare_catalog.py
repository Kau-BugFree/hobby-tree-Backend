"""Reject changed content instead of silently accepting a stale model index."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare-catalog.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class CatalogFingerprintTests(unittest.TestCase):
    """Cover portable line endings without allowing changed catalog content."""

    def test_both_line_endings_preserve_the_exact_expected_bytes(self):
        """Accept LF and CRLF input only when the result has the expected digest."""
        lf = '{"title":"계량"}\n'.encode()
        crlf = lf.replace(b"\n", b"\r\n")
        for expected in (lf, crlf):
            fingerprint = hashlib.sha256(b"hobbytree-fts-v2.0" + expected).hexdigest()
            for original in (lf, crlf):
                self.assertEqual(prepare.matching_bytes(original, fingerprint), expected)

    def test_content_change_fails_instead_of_relabeling_the_index(self):
        """Reject a stale index instead of inventing a replacement fingerprint."""
        fingerprint = hashlib.sha256(b"hobbytree-fts-v2.0" + b"original\n").hexdigest()
        with self.assertRaises(ValueError):
            prepare.matching_bytes(b"modified\r\n", fingerprint)

    def test_second_catalog_failure_preserves_both_original_files(self):
        """Never rewrite the first catalog if the second hash or version is invalid."""
        for invalid_version in (False, True):
            with self.subTest(invalid_version=invalid_version), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                first = root / "activities.jsonl"
                second = root / "coaching_knowledge.jsonl"
                first.write_bytes(b"first\r\n")
                second.write_bytes(b"second\n")
                before = (first.read_bytes(), second.read_bytes())
                for scope, content in (("activities", b"first\n"), ("knowledge", b"different\n")):
                    (root / f"{scope}.dense.json").write_text(json.dumps({
                        "index_version": "unsupported" if invalid_version and scope == "knowledge"
                        else "hobbytree-fts-v2.0",
                        "version": hashlib.sha256(b"hobbytree-fts-v2.0" + content).hexdigest(),
                    }), encoding="utf-8")
                with patch("sys.argv", ["prepare", "--data", str(root), "--index", str(root)]):
                    with self.assertRaises(ValueError):
                        prepare.main()
                self.assertEqual((first.read_bytes(), second.read_bytes()), before)


if __name__ == "__main__":
    unittest.main()
