#!/usr/bin/env python3
"""Manually reconcile only a proven preflight-only failure; never replay a release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time

import main_release_host as H


def audit_path(request_id, attempt):
    H.require(re.fullmatch(r'[1-9][0-9]{0,19}-[1-9][0-9]{0,4}', request_id))
    H.require(type(attempt) is int and 1 <= attempt <= 10)
    suffix = '' if attempt == 1 else '-attempt-' + str(attempt)
    return H.STATE / ('reconcile-' + request_id + suffix)


def audit_hash(directory):
    """Hash every protected file and directory; never emit their content or follow links."""
    info = H.protected(directory).lstat()
    H.require(stat.S_ISDIR(info.st_mode))
    rows = [['.', 'directory', stat.S_IMODE(info.st_mode)]]
    def walk(parent):
        for path in sorted(parent.iterdir()):
            info = path.lstat()
            H.require(not stat.S_ISLNK(info.st_mode))
            H.protected(path)
            name = path.relative_to(directory).as_posix()
            if stat.S_ISDIR(info.st_mode):
                rows.append([name, 'directory', stat.S_IMODE(info.st_mode)])
                walk(path)
            else:
                H.require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1)
                digest = hashlib.sha256()
                with path.open('rb') as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                        digest.update(chunk)
                rows.append([name, digest.hexdigest(), stat.S_IMODE(info.st_mode)])
    walk(directory)
    return H.sha(H.canonical(rows))


def failed_chain(request_id, count, raw, previous):
    """Only completed failed attempts can be parents; partial/verified attempts require inspection."""
    H.require(type(count) is int and 1 <= count <= 10)
    digest = None
    for attempt in range(1, count + 1):
        directory = audit_path(request_id, attempt)
        H.protected(directory)
        H.require(not (directory / 'verified.json').exists()
                  and not (directory / 'docker/config.json').exists(), 'Ambiguous audit requires inspection')
        intent = json.loads(H.protected(directory / 'intent.json').read_bytes())
        failure = json.loads(H.protected(directory / 'failed.json').read_bytes())
        H.require(failure['status'] == 'blocked' and type(failure['at']) is int
                  and isinstance(failure['error_type'], str))
        H.require(intent['request'] == request_id and intent.get('attempt', 1) == attempt
                  and intent['input_sha256'] == {k: H.sha(v) for k, v in raw.items()}
                  and intent['restored_active_sha256'] == H.sha(H.canonical(previous)))
        H.require(intent.get('prior_audit_sha256') == digest, 'Prior audit chain mismatch')
        digest = audit_hash(directory)
    return digest


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
        result[service] = {key: obj[key] for key in ('Id', 'Image', 'RestartCount')}
        mounts = obj['Mounts']
        H.require(isinstance(mounts, list), 'Invalid Docker mount list')
        # Docker may reorder the list. Keep each complete mount and its multiplicity.
        result[service]['Mounts'] = sorted(mounts, key=H.canonical)
        result[service]['started_at'] = obj['State']['StartedAt']
    result['boot_id'] = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    return result


def reconcile(request_id, pins, attempt=1, prior_audit_sha256=None):
    """Caller holds the release flock. All durable failure inputs stay byte-identical."""
    paths, raw, previous, request = evidence(request_id, pins)
    config_raw = H.protected(H.CONFIG).read_bytes()
    config = json.loads(config_raw)
    H.require(config['enabled'] is False and config['runtime']['enabled'] is False)
    H.require(config['executor_sha256'] == H.sha(H.protected(H.__file__).read_bytes())
              and config['runtime']['sources']['host'] == config['executor_sha256'])
    H.configuration({**config, 'enabled': True})
    directory = audit_path(request_id, attempt)
    if attempt == 1:
        H.require(prior_audit_sha256 is None)
    else:
        H.require(isinstance(prior_audit_sha256, str) and re.fullmatch(H.SHA, prior_audit_sha256))
        H.require(failed_chain(request_id, attempt - 1, raw, previous) == prior_audit_sha256,
                  'Approved prior audit hash mismatch')
    directory.mkdir(mode=0o700)  # Exclusive: never erase/reuse ambiguous audit evidence.
    (directory / 'docker').mkdir(mode=0o700)
    H.save(directory / 'intent.json', {
        'request': request_id, 'attempt': attempt, 'prior_audit_sha256': prior_audit_sha256,
        'at': int(time.time()), 'input_sha256': {k: H.sha(v) for k, v in raw.items()},
        'config_sha256': H.sha(config_raw), 'executor_sha256': config['executor_sha256'],
        'reconciler_sha256': H.sha(H.protected(__file__).read_bytes()),
        'restored_active_sha256': H.sha(H.canonical(previous))})
    # Only the in-memory validation adapter is enabled; no configuration file or gate is changed.
    host = H.Host({**config, 'enabled': True}, directory)
    stage = 'disabled-before'
    def checkpoint(name):
        H.save(directory / 'progress.json', {'stage': stage, 'check': name, 'at': int(time.time())})
    host.on_check = checkpoint
    try:
        disabled(host, config)
        stage = 'observation-before'
        before = observation(host, previous, request_id)
        # Candidate contains the generic DB checker, even when the old V24 image does not.
        # preflight calls only DB mode 'before' (history/policy SELECTs), never execute/perform.
        stage = 'preflight'
        host.preflight({**request, 'expires_at': int(time.time()) + 3600}, previous)
        stage = 'internal-probes'
        host.probes('http://127.0.0.1:8080')
        stage = 'target-health'
        host.wait_target('healthy')
        stage = 'public-probes'
        host.probes('https://api.hobbytreeapp.com')
        stage = 'observation-after'
        H.require(observation(host, previous, request_id) == before, 'Running service changed during verification')
        stage = 'disabled-after'
        disabled(host, config)
        stage = 'credential-cleanup'
        credential = directory / 'docker/config.json'
        if credential.exists():
            H.protected(credential).unlink()
        stage = 'final-evidence'
        H.require(H.protected(H.CONFIG).read_bytes() == config_raw, 'Configuration changed during verification')
        H.require(all(H.protected(path).read_bytes() == raw[key] for key, path in paths.items()),
                  'Evidence changed during verification')
        if attempt > 1:
            H.require(failed_chain(request_id, attempt - 1, raw, previous) == prior_audit_sha256,
                      'Prior audit changed during verification')
        stage = 'verification-receipt'
        H.save(directory / 'verified.json', {'status': 'verified_before_commit', 'at': int(time.time()),
                                           'observed': before, 'restored_active_sha256': H.sha(H.canonical(previous))})
        stage = 'active-commit'
        # Last durable operation. A write/fsync failure is ambiguous, never eligible for retry.
        H.save(H.STATE / 'active.json', previous)
    except Exception as exc:
        cleanup_error = None
        credential = directory / 'docker/config.json'
        try:
            if credential.exists():
                H.protected(credential).unlink()
        except Exception as cleanup:
            cleanup_error = H.failure_kind(cleanup)
        detail = host.diagnostics()
        H.save(directory / 'failed.json', {'status': 'blocked', 'error_type': H.failure_kind(exc),
                                          'stage': stage, 'host': detail, 'credential_cleanup_error': cleanup_error,
                                          'at': int(time.time())})
        raise


def main():
    import fcntl
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', required=True)
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--prior-audit-sha256')
    parser.add_argument('--inspect-attempt', type=int,
                        help='Read-only failed audit chain digest; no host validation or retry')
    for key in ('active', 'journal', 'previous'):
        parser.add_argument('--' + key + '-sha256', required=True)
    args = parser.parse_args()
    H.require(os.geteuid() == 0)
    H.protected(__file__)
    H.protected(H.STATE)
    with open(H.protected(H.ROOT / 'lock'), 'rb') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        pins = {k: getattr(args, k + '_sha256') for k in ('active', 'journal', 'previous')}
        if args.inspect_attempt is not None:
            H.require(args.attempt == 1 and args.prior_audit_sha256 is None)
            _, raw, previous, _ = evidence(args.request, pins)
            print(json.dumps({'request': args.request, 'attempt': args.inspect_attempt,
                              'audit_sha256': failed_chain(args.request, args.inspect_attempt, raw, previous)}))
            return
        reconcile(args.request, pins, args.attempt, args.prior_audit_sha256)
    print('PREFLIGHT_RECONCILED_NO_RELEASE_RETRY')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('PREFLIGHT_RECONCILIATION_STOPPED_INSPECT_EVIDENCE', file=sys.stderr)
        sys.exit(20)
