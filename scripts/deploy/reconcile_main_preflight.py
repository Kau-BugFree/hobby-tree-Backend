#!/usr/bin/env python3
"""Manually reconcile only a proven preflight-only failure; never replay a release."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time

import main_release_host as H


def evidence(request_id, pins):
    H.require(re.fullmatch(r'[1-9][0-9]{0,19}-[1-9][0-9]{0,4}', request_id))
    directory = H.STATE / request_id
    paths = {'active': H.STATE / 'active.json', 'journal': directory / 'journal.json',
             'previous': directory / 'previous.json', 'request': directory / 'request.json'}
    raw = {key: H.protected(path).read_bytes() for key, path in paths.items()}
    H.require(set(pins) == {'active', 'journal', 'previous'})
    H.require(all(re.fullmatch(H.SHA, value) and H.sha(raw[key]) == value for key, value in pins.items()),
              'Evidence hash mismatch')
    active, journal, previous, request = (json.loads(raw[k]) for k in paths)
    # Validate the historical envelope without renewing or overwriting it.
    H.payload(request, now=request['expires_at'] - 1)
    H.require(request['request'] == request_id and journal['request'] == request)
    events = journal['events']
    H.require(len(events) == 1 and set(events[0]) == {'phase', 'status', 'at'}
              and events[0]['phase'] == 'preflight' and events[0]['status'] == 'failed'
              and type(events[0]['at']) is int, 'Only preflight-only failures are eligible')
    H.require(previous['status'] == 'complete' and previous['digest'] != request['digest'])
    H.require(active == {**previous, 'status': 'blocked', 'request': request_id},
              'Blocked state does not match the preserved previous release')
    return paths, raw, previous, request


def disabled(host, config):
    H.require(config['enabled'] is False and config['runtime']['enabled'] is False,
              'Deployment and runtime recovery must remain disabled')
    for unit in ('hobbytree-backend.service', 'hobbytree-main-runtime.service', 'hobbytree-main-runtime.timer'):
        props = dict(line.split('=', 1) for line in host.command(
            ['systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'UnitFileState']).decode().splitlines())
        H.require(props.get('ActiveState') == 'inactive' and props.get('UnitFileState') == 'disabled')


def observation(host, previous, request_id):
    # No running migration/recovery child may survive an interrupted preflight.
    H.require(not host.command(['docker', 'ps', '-q', '--filter', 'label=hobbytree.main-release=' + request_id]).strip())
    result = {}
    for service in ('backend', 'redis'):
        obj = host.container(service)
        H.require(obj['State']['Running'] and obj['State']['Health']['Status'] == 'healthy'
                  and obj['HostConfig']['RestartPolicy']['Name'] == 'no')
        if service == 'backend':
            H.require(obj['Image'] == previous['image_id']
                      and obj['Config']['Image'] == H.REGISTRY + '@' + previous['digest'])
        # Deliberately exclude Config.Env and other potential credentials from audit files.
        result[service] = {key: obj[key] for key in ('Id', 'Image', 'RestartCount', 'Mounts')}
        result[service]['started_at'] = obj['State']['StartedAt']
    result['boot_id'] = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    return result


def reconcile(request_id, pins):
    """Caller holds the release flock. All durable failure inputs stay byte-identical."""
    paths, raw, previous, request = evidence(request_id, pins)
    config_raw = H.protected(H.CONFIG).read_bytes()
    config = json.loads(config_raw)
    H.require(config['enabled'] is False and config['runtime']['enabled'] is False)
    H.require(config['executor_sha256'] == H.sha(H.protected(H.__file__).read_bytes())
              and config['runtime']['sources']['host'] == config['executor_sha256'])
    H.configuration({**config, 'enabled': True})
    directory = H.STATE / ('reconcile-' + request_id)
    directory.mkdir(mode=0o700)  # Exclusive: never erase/reuse ambiguous audit evidence.
    (directory / 'docker').mkdir(mode=0o700)
    H.save(directory / 'intent.json', {
        'request': request_id, 'at': int(time.time()), 'input_sha256': {k: H.sha(v) for k, v in raw.items()},
        'config_sha256': H.sha(config_raw), 'executor_sha256': config['executor_sha256'],
        'reconciler_sha256': H.sha(H.protected(__file__).read_bytes()),
        'restored_active_sha256': H.sha(H.canonical(previous))})
    # Only the in-memory validation adapter is enabled; no configuration file or gate is changed.
    host = H.Host({**config, 'enabled': True}, directory)
    try:
        disabled(host, config)
        before = observation(host, previous, request_id)
        # Candidate contains the generic DB checker, even when the old V24 image does not.
        # preflight calls only DB mode 'before' (history/policy SELECTs), never execute/perform.
        host.preflight({**request, 'expires_at': int(time.time()) + 3600}, previous)
        host.probes('http://127.0.0.1:8080')
        host.wait_target('healthy')
        host.probes('https://api.hobbytreeapp.com')
        H.require(observation(host, previous, request_id) == before, 'Running service changed during verification')
        disabled(host, config)
    except Exception as exc:
        H.save(directory / 'failed.json', {'status': 'blocked', 'error_type': type(exc).__name__, 'at': int(time.time())})
        raise
    finally:
        credential = directory / 'docker/config.json'
        if credential.exists():
            H.protected(credential).unlink()
    H.require(H.protected(H.CONFIG).read_bytes() == config_raw, 'Configuration changed during verification')
    H.require(all(H.protected(path).read_bytes() == raw[key] for key, path in paths.items()),
              'Evidence changed during verification')
    H.save(directory / 'verified.json', {'status': 'verified_before_commit', 'at': int(time.time()),
                                       'observed': before, 'restored_active_sha256': H.sha(H.canonical(previous))})
    # Last durable operation. If fsync/printing fails, inspect receipt + active hash, never rerun blindly.
    H.save(H.STATE / 'active.json', previous)


def main():
    import fcntl
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', required=True)
    for key in ('active', 'journal', 'previous'):
        parser.add_argument('--' + key + '-sha256', required=True)
    args = parser.parse_args()
    H.require(os.geteuid() == 0)
    H.protected(__file__)
    H.protected(H.STATE)
    with open(H.protected(H.ROOT / 'lock'), 'rb') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        reconcile(args.request, {k: getattr(args, k + '_sha256') for k in ('active', 'journal', 'previous')})
    print('PREFLIGHT_RECONCILED_NO_RELEASE_RETRY')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('PREFLIGHT_RECONCILIATION_STOPPED_INSPECT_EVIDENCE', file=sys.stderr)
        sys.exit(20)
