"""Disposable Docker contracts: private DB and loopback API. No AWS/provider calls."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def docker(*args, input=None, check=True):
    p = subprocess.run(['docker', *args], input=input, capture_output=True, text=True,
                       encoding='utf8', timeout=120)
    if check and p.returncode:
        raise RuntimeError('ISOLATED_DOCKER_OPERATION_FAILED')
    return p


def wait(command):
    for _ in range(60):
        if command():
            return
        time.sleep(1)
    raise RuntimeError('ISOLATED_HEALTH_TIMEOUT')


def check(api_image, db_image, tests_image=None):
    name = 'item-test-' + uuid.uuid4().hex[:12]
    db, api = name + '-db', name + '-api'
    fixtures, data = name + '-fixtures', name + '-data'
    ingress = name + '-ingress'
    fixture = (ROOT / 'docs/examples/item-material-export-v1.json').read_bytes()
    empty = (ROOT / 'docs/examples/item-sync-v1/09-empty.json').read_bytes()
    env = ['-e', 'ADPICK_STORAGE_APPROVED=false', '-e', 'ADPICK_FILTERING_APPROVED=false']
    try:
        docker('network', 'create', '--internal', name)
        docker('network', 'create', ingress)
        for volume in (fixtures, data):
            docker('volume', 'create', volume)
        # All values below are synthetic; the volume lives only for this test.
        setup = """import os,base64
from pathlib import Path
p=Path('/fixtures')
for name,value,uid in [('item_service_token','t'*64,10001),('item_db_password','p'*64,10001),('item_db_init_password','p'*64,999),('catalog_admin_password','a'*64,999)]:
 f=p/name; f.write_text(value+'\\n'); os.chown(f,uid,uid); f.chmod(0o400)
"""
        setup += "(p/'export.json').write_bytes(base64.b64decode(" + repr(base64.b64encode(fixture).decode()) + "))\n"
        setup += "(p/'empty.json').write_bytes(base64.b64decode(" + repr(base64.b64encode(empty).decode()) + "))\n"
        docker('run', '--rm', '--network', 'none', '--user', '0:0', '-i',
               '-v', fixtures + ':/fixtures', '--entrypoint', 'python', api_image, '-', input=setup)
        docker('run', '-d', '--name', db, '--network', name, '--network-alias', 'catalog-db',
               '-e', 'POSTGRES_PASSWORD_FILE=/run/secrets/catalog_admin_password',
               '-v', fixtures + ':/run/secrets:ro', '-v', data + ':/var/lib/postgresql/data',
               db_image, 'postgres', '-c', 'shared_preload_libraries=pg_cron',
               '-c', 'cron.database_name=hobbytree_items')
        # The temporary bootstrap server is socket-only. Wait for final TCP first.
        wait(lambda: docker('exec', db, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres',
                            '-d', 'hobbytree_items', check=False).returncode == 0)
        # Also verify that initialization created the expected schema.
        wait(lambda: docker('exec', db, 'psql', '-U', 'postgres', '-d', 'hobbytree_items',
                            '-Atc', "SELECT count(*) FROM item_recommender.catalog_queries", check=False).returncode == 0)
        roles = docker('exec', db, 'psql', '-U', 'postgres', '-d', 'hobbytree_items', '-Atc',
                       "SELECT rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='item_app'").stdout.strip()
        assert roles == 'f|f|f'
        cron = docker('exec', db, 'psql', '-U', 'postgres', '-d', 'hobbytree_items', '-Atc',
                      "SELECT count(*) FROM pg_extension WHERE extname='pg_cron'").stdout.strip()
        assert cron == '0'  # No automatic schedule or cleanup at install time.
        docker('create', '--name', api, '--network', name, '--read-only', '--cap-drop', 'ALL',
               '-p', '127.0.0.1::8001',
               '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,mode=1777',
               '-v', fixtures + ':/run/secrets:ro', *env, api_image, 'api')
        docker('network', 'connect', ingress, api)
        docker('start', api)
        health = "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8001/health',timeout=2).close()"
        wait(lambda: docker('exec', api, 'python', '-c', health, check=False).returncode == 0)
        # Verify the published ingress, not just localhost inside the API container.
        published = json.loads(docker('inspect', api).stdout)[0]['NetworkSettings']['Ports']['8001/tcp'][0]
        with urllib.request.urlopen('http://127.0.0.1:' + published['HostPort'] + '/health', timeout=5) as response:
            assert response.status == 200
        request = """import json,urllib.request,urllib.error
url='http://127.0.0.1:8001/internal/v1/item-recommendations'
body=json.dumps(dict(activityId=1,activityContentVersion=1,reviewStatus='PUBLISHED',materials=[])).encode()
for token,expected in [(None,401),('wrong',401),('t'*64,200)]:
 headers={'Content-Type':'application/json'}
 if token: headers['X-Service-Token']=token
 try:
  with urllib.request.urlopen(urllib.request.Request(url,body,headers),timeout=5) as r: code=r.status
 except urllib.error.HTTPError as e: code=e.code; e.close()
 assert code==expected,(code,expected)
print('AUTH_401_401_200')
"""
        assert 'AUTH_401_401_200' in docker('exec', '-i', api, 'python', '-', input=request).stdout
        def sync(filename, raw, apply=False):
            doc = json.loads(raw)
            args = ['sync', '--file', '/exports/' + filename, '--sha256', hashlib.sha256(raw).hexdigest(),
                    '--source-id', doc['sourceId'], '--source-epoch', doc['sourceEpoch']]
            if apply:
                args += ['--apply']
            r = docker('run', '--rm', '--network', name, '--read-only', '--tmpfs', '/tmp:rw,mode=1777',
                       '-v', fixtures + ':/run/secrets:ro', '-v', fixtures + ':/exports:ro',
                       *env, api_image, *args, check=False)
            return r.returncode, json.loads(r.stdout)['status']
        assert sync('export.json', fixture) == (0, 'APPLY')
        assert sync('export.json', fixture, True) == (0, 'APPLIED')
        assert sync('export.json', fixture, True) == (0, 'NOOP_REPLAY')
        assert sync('empty.json', empty, True) == (3, 'HOLD_EMPTY')
        # Volume persists across DB restart; replay must still be a no-op.
        docker('restart', db)
        wait(lambda: docker('exec', db, 'pg_isready', '-U', 'postgres', '-d', 'hobbytree_items', check=False).returncode == 0)
        assert sync('export.json', fixture, True) == (0, 'NOOP_REPLAY')
        if tests_image:
            # The upstream suite refuses a database with any pre-existing catalog.
            # Keep the importer persistence fixture intact and use another disposable DB.
            docker('exec', db, 'psql', '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1',
                   '-c', 'CREATE DATABASE item_suite OWNER item_app')
            r = docker('run', '--rm', '--network', name, '--read-only', '--tmpfs', '/tmp:rw,mode=1777',
                       '-e', 'PGPASSWORD=' + 'p' * 64,
                       '-e', 'CATALOG_TEST_DATABASE_URL=postgresql://item_app@catalog-db:5432/item_suite',
                       '--entrypoint', 'python', tests_image, '-m', 'pytest', '-q', '-p', 'no:cacheprovider', 'tests', check=False)
            print(r.stdout)
            assert r.returncode == 0, 'PINNED_AI_POSTGRES_TESTS_FAILED'
        print('PASS: dedicated PostgreSQL, least-privilege role, no cron, auth, preview/apply/replay/empty, restart persistence')
    finally:
        # Only this run's random names are removed; never use prune or shared names.
        for container in (api, db):
            docker('rm', '-f', container, check=False)
        for volume in (fixtures, data):
            docker('volume', 'rm', volume, check=False)
        docker('network', 'rm', name, check=False)
        docker('network', 'rm', ingress, check=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--api-image', required=True)
    parser.add_argument('--db-image', required=True)
    parser.add_argument('--tests-image')
    args = parser.parse_args()
    check(args.api_image, args.db_image, args.tests_image)
