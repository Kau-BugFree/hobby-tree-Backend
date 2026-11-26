"""Evidence boundary for CVE-2026-107161; not a vendor patch or deployment approval."""
from pathlib import Path

VERSION = '2.1.28+dfsg1-9'
DIRECTORY = '/usr/lib/x86_64-linux-gnu/sasl2/'
FILES = {DIRECTORY + name for name in ('libsasldb.so', 'libsasldb.so.2', 'libsasldb.so.2.0.25')}
HASHES = {
    '/usr/lib/x86_64-linux-gnu/libsasl2.so.2': '3f93968591811eb3972c3467c69c2968dd9cb2b51f7b7bb48de22ba8e17d13fd',
    DIRECTORY + 'libsasldb.so.2': '2cb155db2279fbc84ca8536b80be8e06f34ef9854c1b223e182279cea773b503',
}


def validate(version, files, hashes):
    if version != VERSION:
        raise ValueError('SASL_VERSION_REVIEW_REQUIRED')
    if set(files) != FILES or len(files) != len(FILES):
        raise ValueError('SASL_PLUGIN_REVIEW_REQUIRED')
    if hashes != HASHES:
        raise ValueError('SASL_BINARY_REVIEW_REQUIRED')


def check(api, db, run):
    for image in (api, db):
        version = run(image, 'dpkg-query', '-W', '-f=${Version}', 'libsasl2-2')
        files = run(image, 'find', '/usr/lib', '/usr/local/lib', '-path', '*/sasl2/*',
                    '(', '-type', 'f', '-o', '-type', 'l', ')', '-print').splitlines()
        rows = run(image, 'sha256sum', *HASHES).splitlines()
        hashes = {row.split()[1]: row.split()[0] for row in rows}
        validate(version, files, hashes)
    # Same reviewed Cyrus binaries and plugin inventory on both images. Execute
    # the library probe in the Python API, where libpq is actually used by callers.
    result = run(api, 'python', '-c', Path(__file__).with_name('probe_sasl.py').read_text(encoding='utf8'))
    print(result)
