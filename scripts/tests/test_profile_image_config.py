"""Check public profile-image configuration with synthetic identifiers."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("validator", Path(__file__).resolve().parents[1] / "validate-ec2-config.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

class ProfileImageConfigTests(unittest.TestCase):
    def test_optional_pair_and_valid_identifiers(self):
        for settings in ({}, {"PROFILE_IMAGE_S3_BUCKET": "", "PROFILE_IMAGE_S3_REGION": ""},
                         {"PROFILE_IMAGE_S3_BUCKET": "test-profile-bucket", "PROFILE_IMAGE_S3_REGION": "ap-northeast-2"}):
            validator.validate_profile_image_settings(settings)

    def test_reject_partial_invalid_and_injected_values(self):
        for bucket, region in [("test-profile-bucket", ""), ("", "ap-northeast-2"),
                               ("x", "ap-northeast-2"), ("a..b", "ap-northeast-2"),
                               ("127.0.0.1", "ap-northeast-2"), ("Test-Bucket", "ap-northeast-2"),
                               ("test-profile-bucket", "ap-northeast-2\n"),
                               ("test-profile-bucket", "invalid")]:
            with self.subTest(bucket=bucket, region=region), self.assertRaises(ValueError):
                validator.validate_profile_image_settings({"PROFILE_IMAGE_S3_BUCKET": bucket, "PROFILE_IMAGE_S3_REGION": region})


    def test_runtime_validation_rejects_partial_pair(self):
        settings = {"SPRING_PROFILES_ACTIVE": "ec2", "SPRING_FLYWAY_ENABLED": "false",
                    "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate", "PROFILE_IMAGE_S3_BUCKET": "test-profile-bucket"}
        with self.assertRaises(ValueError):
            validator.validate_runtime(settings)
        with self.assertRaises(ValueError):
            validator.validate_auth_settings(settings)
