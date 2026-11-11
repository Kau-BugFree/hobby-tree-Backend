"""Safety checks run without AWS, model downloads or database access."""

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install-assets.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class AssetPathsTest(unittest.TestCase):
    """Reject unsafe destinations while preserving the pinned asset identity."""

    def test_only_models_and_indexes(self):
        """Only canonical model/index relative paths are accepted."""
        for name in ['state/coach.sqlite', '.env', '../x', '/models/a',
                     'models/../state/a', 'models\\a', 'models//a']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                installer.destination(name)
        self.assertEqual(installer.destination('backend/var/index/retrieval.sqlite'),
                         installer.ROOT / 'state/index/retrieval.sqlite')

    def test_pinned_manifest(self):
        """Git checkout must retain the canonical manifest bytes."""
        self.assertEqual(installer.digest(Path(__file__).with_name('assets-manifest.json')),
                         installer.MANIFEST_SHA)

    def test_symlink_is_rejected(self):
        """An existing destination symlink cannot redirect installation."""
        previous = installer.ROOT
        try:
            with tempfile.TemporaryDirectory() as directory:
                installer.ROOT = Path(directory)
                (installer.ROOT / 'models').symlink_to('/tmp', target_is_directory=True)
                with self.assertRaises(ValueError):
                    installer.destination('models/test')
        finally:
            installer.ROOT = previous


token_spec = importlib.util.spec_from_file_location('token_preparer', Path(__file__).with_name('prepare-token.py'))
token_preparer = importlib.util.module_from_spec(token_spec)
token_spec.loader.exec_module(token_preparer)


class TokenRecoveryTest(unittest.TestCase):
    """Check interrupted first-install recovery without using real secrets."""

    def test_empty_and_short_tokens_are_recovered_and_valid_token_preserved(self):
        """Recover interrupted writes, then retain the generated token unchanged."""
        for initial in ['', 'short', ' ' * 40]:
            with self.subTest(initial=initial), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'token'
                path.write_text(initial)
                token_preparer.ensure_token(path, os.getuid())
                generated = path.read_text()
                self.assertGreaterEqual(len(generated), 32)
                self.assertEqual(path.stat().st_mode & 0o777, 0o400)
                token_preparer.ensure_token(path, os.getuid())
                self.assertEqual(path.read_text(), generated)
                self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_new_token_and_symlink_rejection(self):
        """Create a missing token but never replace a symlink destination."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'token'
            token_preparer.ensure_token(path, os.getuid())
            link = Path(directory) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                token_preparer.ensure_token(link, os.getuid())

    def test_interrupted_publish_preserves_file_and_can_retry(self):
        """A failed atomic replacement leaves no partial published credential."""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'token'
            path.write_text('')
            with patch.object(token_preparer.os, 'replace', side_effect=OSError('interrupted')):
                with self.assertRaises(OSError):
                    token_preparer.ensure_token(path, os.getuid())
            self.assertEqual(path.read_text(), '')
            self.assertEqual(list(Path(directory).iterdir()), [path])
            token_preparer.ensure_token(path, os.getuid())
            self.assertGreaterEqual(len(path.read_text()), 32)


if __name__ == '__main__':
    unittest.main()
