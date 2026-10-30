import base64
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "ci_diagnostics", Path(__file__).resolve().parents[1] / "ci" / "diagnose-runtime.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DiagnosticsTests(unittest.TestCase):
    def test_masks_jwt_without_assignment_prefix(self):
        header = base64.urlsafe_b64encode(b'{"alg":"HS256"}').decode().rstrip("=")
        payload = base64.urlsafe_b64encode(b'{"sub":"1"}').decode().rstrip("=")
        token = ".".join((header, payload, "syntheticSignature"))
        self.assertNotIn(token, MODULE.redact("Exception contains " + token, []))

    def test_masks_known_secrets_even_inside_exception_messages(self):
        output = MODULE.redact("Binding failed for secret-value and app-password", ["secret-value", "app-password"])
        self.assertNotIn("secret-value", output)
        self.assertNotIn("app-password", output)
        self.assertEqual(output.count("[REDACTED]"), 2)

    def test_masks_sensitive_assignments_but_keeps_health_errors(self):
        output = MODULE.redact("password=private JWT_SECRET=private2 connection refused HTTP 401", [])
        self.assertNotIn("private", output)
        self.assertIn("connection refused HTTP 401", output)
