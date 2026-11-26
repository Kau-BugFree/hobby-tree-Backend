"""Fail before loading a vulnerable native library; no AWS or application state."""
import subprocess
import unittest
from unittest.mock import Mock, patch

from check_image_security import MIN_ZSTD, check_zstd


class ZstdSecurityTests(unittest.TestCase):
    def test_unpatched_debian_revision_is_rejected_before_loading_library(self):
        with patch("check_image_security.subprocess.check_output", return_value="1.5.7+dfsg-1\n"), \
                patch("check_image_security.subprocess.run", return_value=Mock(returncode=1)) as compare, \
                patch("check_image_security.ctypes.CDLL") as loader:
            with self.assertRaisesRegex(RuntimeError, "Unpatched zstd package"):
                check_zstd()
            compare.assert_called_once_with(
                ["dpkg", "--compare-versions", "1.5.7+dfsg-1", "ge", MIN_ZSTD])
            loader.assert_not_called()

    def test_missing_package_fails_closed(self):
        with patch("check_image_security.subprocess.check_output",
                   side_effect=subprocess.CalledProcessError(1, "dpkg-query")), \
                patch("check_image_security.ctypes.CDLL") as loader:
            with self.assertRaises(subprocess.CalledProcessError):
                check_zstd()
            loader.assert_not_called()

    def test_version_comparison_error_is_not_success(self):
        with patch("check_image_security.subprocess.check_output", return_value="invalid"), \
                patch("check_image_security.subprocess.run", return_value=Mock(returncode=2)), \
                patch("check_image_security.ctypes.CDLL") as loader:
            with self.assertRaisesRegex(RuntimeError, "Unpatched zstd package"):
                check_zstd()
            loader.assert_not_called()


if __name__ == "__main__":
    unittest.main()
