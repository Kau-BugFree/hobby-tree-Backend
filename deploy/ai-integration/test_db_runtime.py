import unittest
from check_db_runtime import validate_links


class DatabaseLinks(unittest.TestCase):
    def setUp(self):
        self.links = '\n'.join(f'{name} => /lib/x86_64-linux-gnu/{name} (0x1234)'
                               for name in ('libxml2.so.2', 'libssl.so.3', 'libcrypto.so.3'))

    def test_reviewed_system_links(self):
        validate_links(self.links)
        validate_links(self.links.replace('/lib/', '/usr/lib/'))

    def test_missing_foreign_or_new_crypto_fails_closed(self):
        for output in (self.links.replace('libxml2.so.2', 'libxml2.so.99'),
                       self.links.replace('/lib/', '/unreviewed/'),
                       self.links + '\nlibgnutls.so.30 => /lib/libgnutls.so.30',
                       self.links + '\nlibother.so => not found', ''):
            with self.subTest(output=output), self.assertRaises(ValueError):
                validate_links(output)
