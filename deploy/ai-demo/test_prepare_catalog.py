"""Reject changed content instead of silently accepting a stale model index."""

import hashlib
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
