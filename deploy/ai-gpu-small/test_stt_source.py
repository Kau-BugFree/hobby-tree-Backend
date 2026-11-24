"""The separate STT build context cannot import extra upstream files or keys."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_stt_source as source
from stt_vendor import transcribe, mp4_faststart


class SttExport(unittest.TestCase):
    def test_export_verifies_bytes_and_rejects_tampering_or_extra_files(self):
        payloads = [Path(transcribe.__file__).read_bytes(), Path(mp4_faststart.__file__).read_bytes()]
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "export"
            with patch.object(source.subprocess, "check_output", side_effect=payloads):
                source.export("synthetic-repository", target)
            source.verify(target)
            (target / "unexpected").write_text("synthetic")
            with self.assertRaises(ValueError):
                source.verify(target)
            (target / "unexpected").unlink()
            (target / "transcribe.py").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                source.verify(target)
            second = Path(directory) / "bad"
            with patch.object(source.subprocess, "check_output", return_value=b"different revision"):
                with self.assertRaises(ValueError):
                    source.export("synthetic-repository", second)
            self.assertFalse(second.exists())
