#!/usr/bin/env python3
"""Dedicated local/CI namespace: no published ports, host credentials, AWS, or external DB."""
import json
import re
import subprocess
import time
import uuid


def run(args, timeout=60):
    """Capture a bounded local Docker command; never mount host credentials."""
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=timeout).stdout


def main():
    """Run and clean a unique no-network PostgreSQL namespace with mandatory test counts."""
    name='hobbytree-v24-lab-'+uuid.uuid4().hex
    container=None
    try:
        container=run(['docker','run','-d','--name',name,'--network','none','--memory','512m',
            '--tmpfs','/var/lib/postgresql/data:rw,size=256m','-e','POSTGRES_HOST_AUTH_METHOD=trust',
            'postgres:17.11-bookworm@sha256:858bb5e98eed02eb8cafbcdc007f9a65d1b613972028944a8ec2133ccaf80d27']).strip()
        deadline=time.monotonic()+45
        while True:
            check=subprocess.run(['docker','exec',container,'pg_isready','-U','postgres'],capture_output=True)
            if check.returncode==0:
                break
            if time.monotonic()>deadline:
                raise TimeoutError('Isolated PostgreSQL readiness')
            time.sleep(1)
        result=run(['docker','run','--rm','--name',name+'-tests','--network','container:'+container,
            '--memory','2g','-e','V24_TRANSITION_TEST=true','-e','AUTH_INTEGRATION_TEST=false',
            '--entrypoint','sh','hobbytree/auth-tests:ci','-c',
            'chmod +x gradlew && ./gradlew --offline --no-daemon --max-workers=2 test --tests com.hobbytree.hobbytree.deployment.V24MigrationCliIsolatedTests && '
            'grep "<testsuite " build/test-results/test/TEST-com.hobbytree.hobbytree.deployment.V24MigrationCliIsolatedTests.xml'],900)
        summary=re.search(r'<testsuite [^>]+>',result)
        if not summary:
            raise ValueError('Missing isolated test results')
        counts={key:int(value) for key,value in re.findall(r'(tests|skipped|failures|errors)="([0-9]+)"',summary[0])}
        if counts.get('tests',0)-counts.get('skipped',0)<6 or counts.get('failures')!=0 or counts.get('errors')!=0:
            raise ValueError('Isolated checks did not execute successfully')
        print(result)
        print(json.dumps({'v24_isolated_postgresql':'PASS','external_network':False,'published_ports':0,'tests':counts}))
    except subprocess.CalledProcessError as error:
        # All inputs are generated locally. Keep Gradle failures useful without any host secrets.
        print((error.stdout or '')[-12000:])
        print((error.stderr or '')[-2000:])
        raise SystemExit(1)
    finally:
        subprocess.run(['docker','rm','-f',name+'-tests'],capture_output=True)
        if container:
            subprocess.run(['docker','rm','-f',container],capture_output=True)


if __name__=='__main__':
    main()
