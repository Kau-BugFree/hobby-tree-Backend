"""Safety checks run without AWS, model downloads or database access."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install-assets.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class AssetPathsTest(unittest.TestCase):
    def test_only_models_and_indexes(self):
        for name in ['state/coach.sqlite', '.env', '../x', '/models/a',
                     'models/../state/a', 'models\\a', 'models//a']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                installer.destination(name)
        self.assertEqual(installer.destination('backend/var/index/retrieval.sqlite'),
                         installer.ROOT / 'state/index/retrieval.sqlite')

    def test_pinned_manifest(self):
        self.assertEqual(installer.digest(Path(__file__).with_name('assets-manifest.json')),
                         installer.MANIFEST_SHA)

    def test_symlink_is_rejected(self):
        previous = installer.ROOT
        try:
            with tempfile.TemporaryDirectory() as directory:
                installer.ROOT = Path(directory)
                (installer.ROOT / 'models').symlink_to('/tmp', target_is_directory=True)
                with self.assertRaises(ValueError):
                    installer.destination('models/test')
        finally:
            installer.ROOT = previous


if __name__ == '__main__':
    unittest.main()
