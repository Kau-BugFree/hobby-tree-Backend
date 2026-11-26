"""Offline checks of deployed image contents, before starting disposable services."""
import argparse
import subprocess


def run(image, executable, *args):
    result = subprocess.run(
        ['docker', 'run', '--rm', '--network', 'none', '--read-only',
         '--entrypoint', executable, image, *args],
        capture_output=True, text=True, timeout=120, check=True)
    return result.stdout.strip()


def check(api, db):
    # Metadata still records source-package CVEs after individual CLI removal.
    for image in (api, db):
        output = run(image, 'sh', '-ec',
                     'test ! -e /usr/bin/infocmp; '
                     'test ! -e /usr/bin/dpkg-source; '
                     'test ! -e /usr/lib/systemd/systemd-homed; '
                     'test ! -e /usr/lib/gnupg/tpm2daemon; '
                     'find /usr /bin /sbin -xdev -type f -perm /6000 -print')
        if output:
            raise ValueError('PRIVILEGED_EXECUTABLE_REMAINS')
        if run(image, 'sh', '-ec', '. /etc/os-release; printf %s "$VERSION_ID"') != '13':
            raise ValueError('UNREVIEWED_OS')
    run(api, 'python', '-c', '''import importlib.util, os, sqlite3, sys, ssl, pathlib, psycopg
if os.getuid()!=10001 or sys.version_info[:2]!=(3,11): raise RuntimeError('API_IDENTITY')
if sqlite3.sqlite_version_info < (3,46,1): raise RuntimeError('SQLITE_BASELINE')
for module in ('setuptools','wheel','pip','pkg_resources','psycopg_binary'):
 if importlib.util.find_spec(module) is not None: raise RuntimeError('BUILD_TOOL_REMAINS')
if psycopg.__version__ != '3.2.13' or psycopg.pq.__impl__ != 'c': raise RuntimeError('DRIVER_BASELINE')
if not ssl.OPENSSL_VERSION.startswith('OpenSSL 3.5.9 '): raise RuntimeError('OPENSSL_REVIEW_REQUIRED')
ctx=ssl.create_default_context()
if ctx.verify_flags & ssl.VERIFY_CRL_CHECK_CHAIN: raise RuntimeError('CRL_REVIEW_REQUIRED')
if not ctx.check_hostname or ctx.verify_mode != ssl.CERT_REQUIRED: raise RuntimeError('TLS_VERIFY_REQUIRED')
loaded=[line.split()[-1] for line in pathlib.Path('/proc/self/maps').read_text().splitlines() if 'libssl' in line or 'libcrypto' in line or 'libpq.so' in line]
if not loaded or any(not path.startswith('/usr/lib/x86_64-linux-gnu/') for path in loaded): raise RuntimeError('NON_SYSTEM_CRYPTO')
if not any('libpq.so' in path for path in loaded): raise RuntimeError('SYSTEM_LIBPQ_MISSING')
''')
    run(api, 'sh', '-ec', 'test ! -e /usr/bin/gcc; test ! -e /usr/local/lib/python3.11/site-packages/psycopg_binary.libs')
    if run(db, 'gosu', '--version') != '1.20 (go1.27.3 on linux/amd64; gc)':
        raise ValueError('UNREVIEWED_GOSU')
    if run(db, 'gosu', 'postgres', 'id', '-u') != '999':
        raise ValueError('GOSU_IDENTITY')
    if not run(db, 'postgres', '--version').startswith('postgres (PostgreSQL) 16.'):
        raise ValueError('POSTGRES_MAJOR')
    run(db, 'sh', '-ec', "dpkg --compare-versions \"$(dpkg-query -W -f='${Version}' libzstd1)\" ge '1.5.7+dfsg-1+deb13u1'")
    print('PASS: offline image hardening, runtime identity and patched toolchain')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--api-image', required=True)
    parser.add_argument('--db-image', required=True)
    args = parser.parse_args()
    check(args.api_image, args.db_image)
