#!/usr/bin/env python3
"""Dedicated local/CI namespace: no published ports, host credentials, AWS, or external DB."""
import json
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PREFIX = 'com.hobbytree.hobbytree.deployment.'
GENERIC = PREFIX + 'ReleaseMigrationCliIsolatedTests'
LEGACY = PREFIX + 'V24MigrationCliIsolatedTests'


def suites_for(directory):
    versions = []
    for path in Path(directory).iterdir():
        match = re.fullmatch(r'V([1-9][0-9]*)__[A-Za-z0-9_]+\.sql', path.name)
        if not path.is_file() or not match:
            raise ValueError('Unexpected migration resource')
        versions.append(int(match[1]))
    if len(versions) < 24 or sorted(versions) != list(range(1, len(versions) + 1)):
        raise ValueError('Incomplete migration history')
    # The retired CLI requires exactly V24 and its then-current JPA schema.
    # Future releases must exercise the current-schema generic CLI instead.
    return [LEGACY, GENERIC] if len(versions) == 24 else [GENERIC]


def run(args, timeout=60):
    """Capture a bounded local Docker command; never mount host credentials."""
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=timeout).stdout


def main():
    """Run and clean a unique no-network PostgreSQL namespace with mandatory test counts."""
    name='hobbytree-v24-lab-'+uuid.uuid4().hex
    container=None
    suites = suites_for('src/main/resources/db/migration')
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
            'chmod +x gradlew && ./gradlew --offline --no-daemon --max-workers=2 cleanTest test ' + ' '.join('--tests ' + suite for suite in suites) + ' && '
            'cat build/test-results/test/TEST-com.hobbytree.hobbytree.deployment.*MigrationCliIsolatedTests.xml'],900)
        reports=re.findall(r'<testsuite [^>]+>.*?</testsuite>',result,re.S)
        if sorted(ET.fromstring(raw).attrib['name'] for raw in reports) != sorted(suites):
            raise ValueError('Every applicable release suite must run exactly once')
        counts=[]
        for raw in reports:
            suite=ET.fromstring(raw)
            cases=suite.findall('testcase')
            skipped=[c.attrib['name'] for c in cases if c.find('skipped') is not None]
            allowed=['reviewedCatalogueIsIdempotentAndRefusesExistingContentDrift()'] if suite.attrib['name'].endswith('.V24MigrationCliIsolatedTests') else []
            values={key:int(suite.attrib[key]) for key in ('tests','skipped','failures','errors')}
            minimum = 7 if suite.attrib['name'] == GENERIC else 6
            if (values['tests'] != len(cases) or values['tests']-values['skipped'] < minimum
                    or skipped != allowed or values['skipped'] != len(skipped)
                    or values['failures'] != 0 or values['errors'] != 0
                    or any(c.find('failure') is not None or c.find('error') is not None for c in cases)):
                raise ValueError('Release suite failed or an unexpected test was skipped')
            counts.append({'suite':suite.attrib['name'],**values,'excluded_catalogue_fixture':skipped})
        print(json.dumps({'isolated_postgresql':'PASS','external_network':False,'published_ports':0,
                          'legacy_v24_applicable': LEGACY in suites, 'suites':counts}))
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
