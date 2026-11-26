import unittest
from check_sasl_runtime import DIRECTORY, FILES, HASHES, VERSION, validate


class SaslEvidence(unittest.TestCase):
    def test_reviewed_binaries_and_no_digest_plugin(self):
        validate(VERSION, list(FILES), dict(HASHES))

    def test_new_missing_or_relocated_plugin_blocks(self):
        variants = [list(FILES) + [DIRECTORY + 'libdigestmd5.so'],
                    list(FILES) + ['/usr/local/lib/sasl2/libdigestmd5.so'],
                    [], list(FILES)[:-1], list(FILES) + [next(iter(FILES))]]
        for files in variants:
            with self.subTest(files=files), self.assertRaisesRegex(ValueError, 'PLUGIN'):
                validate(VERSION, files, HASHES)

    def test_package_or_binary_update_requires_new_review(self):
        with self.assertRaisesRegex(ValueError, 'VERSION'):
            validate('2.1.28+dfsg1-12', list(FILES), HASHES)
        for hashes in ({}, {**HASHES, next(iter(HASHES)): '0' * 64}):
            with self.assertRaisesRegex(ValueError, 'BINARY'):
                validate(VERSION, list(FILES), hashes)


if __name__ == '__main__':
    unittest.main()
