"""DB-specific residual review boundaries; not a claim that XML is patched."""
import re


def validate_links(output):
    if 'not found' in output or 'libgnutls' in output.lower():
        raise ValueError('DB_CRYPTO_LINK_REVIEW_REQUIRED')
    for library in ('libxml2.so.2', 'libssl.so.3', 'libcrypto.so.3'):
        if not re.search(r'^\s*' + re.escape(library) + r'\s+=>\s+/(?:usr/)?lib/x86_64-linux-gnu/', output, re.M):
            raise ValueError('DB_LIBRARY_BOUNDARY_CHANGED')


def check(db, run):
    run(db, 'sh', '-ec',
        'test ! -e /usr/bin/gpgsm; test ! -e /usr/bin/gpgtar; '
        'test ! -e /usr/bin/dirmngr')
    # libxml2 is genuinely linked: app inputs must remain bound text/JSON.
    # This check detects GnuTLS entering the PostgreSQL transitive link graph.
    validate_links(run(db, 'ldd', '/usr/lib/postgresql/16/bin/postgres'))
