"""Read-only oneshot verification. Never wait while holding the deployment lock."""
from contextlib import contextmanager
import json
import math
from pathlib import Path
import re
import subprocess
import time

SERVICE = 'hobbytree-main-runtime.service'
TRANSITIONS = frozenset({'activating', 'deactivating', 'reloading'})
SUCCESS = frozenset({'RUNTIME_HEALTHY', 'RUNTIME_RECOVERED'})
PROPERTIES = ('Type', 'LoadState', 'UnitFileState', 'ActiveState', 'SubState', 'Result', 'InvocationID',
              'ExecMainStatus', 'ExecMainStartTimestampMonotonic', 'ExecMainExitTimestampMonotonic')


class VerificationError(ValueError):
    """A fixed check name, never raw journal messages or command output."""


def require(ok, check):
    if not ok:
        raise VerificationError(check)


def integer(value):
    require(isinstance(value, str) and re.fullmatch(r'[0-9]+', value), 'invalid-monotonic-value')
    return int(value)


def completed(snapshot, accepted=SUCCESS):
    """Return evidence for this completed invocation, or None while it is pending."""
    props, boot = snapshot['properties'], snapshot['boot_id'].replace('-', '')
    require(re.fullmatch(r'[0-9a-f]{32}', boot) is not None, 'invalid-boot-id')
    require(props.get('Type') == 'oneshot' and props.get('LoadState') == 'loaded', 'unexpected-service')
    require(props.get('UnitFileState') == 'enabled', 'service-not-enabled')
    state = props.get('ActiveState')
    if state in TRANSITIONS:
        return None
    require(state == 'inactive' and props.get('SubState') == 'dead', 'service-not-quiescent')
    require(props.get('Result') == 'success' and props.get('ExecMainStatus') == '0', 'service-exit-failed')
    start, end = (integer(props.get(key)) for key in
                  ('ExecMainStartTimestampMonotonic', 'ExecMainExitTimestampMonotonic'))
    if start == 0 or end == 0:
        return None
    require(end >= start, 'invalid-invocation-range')
    invocation = props.get('InvocationID', '')
    if invocation in ('', '0' * 32):
        return None
    require(isinstance(invocation, str) and re.fullmatch(r'[0-9a-f]{32}', invocation), 'invalid-invocation-id')
    outcomes = set()
    for row in snapshot['journal']:
        if (row.get('_BOOT_ID') != boot or row.get('_SYSTEMD_UNIT') != SERVICE
                or row.get('_SYSTEMD_INVOCATION_ID') != invocation):
            continue
        message = row.get('MESSAGE')
        if not isinstance(message, str) or not message.startswith('RUNTIME_'):
            continue
        # Journal receipt can occur after ExecMainExitTimestampMonotonic.
        # The trusted boot/unit/invocation identity remains valid for delayed stdout.
        outcomes.add(message)
    if not outcomes:  # Journal delivery may lag process exit.
        return None
    require(len(outcomes) == 1, 'ambiguous-invocation-result')
    outcome = outcomes.pop()
    if outcome == 'RUNTIME_BUSY':
        return None  # A skipped lock attempt is not a successful health check.
    require(outcome in accepted, 'runtime-result-not-successful')
    return {'boot_id': boot, 'invocation_id': invocation, 'start_us': start, 'end_us': end, 'outcome': outcome}


def verify_oneshot(read_snapshot, try_lock, inspect, *, timeout=180, interval=0.25,
                   accepted=frozenset({'RUNTIME_HEALTHY'}), clock=time.monotonic, sleep=time.sleep):
    """Bracket a read-only inspection with the same completed invocation.

    All callbacks must have their own bounded I/O timeouts. The release lock is
    nonblocking and is released before every wait. A timer can start even while
    this lock is held; discard that inspection and await a fresh normal result.
    """
    require(type(timeout) in (int, float) and math.isfinite(timeout) and timeout > 0, 'invalid-timeout')
    require(type(interval) in (int, float) and math.isfinite(interval) and interval > 0, 'invalid-interval')
    require(bool(accepted) and set(accepted) <= SUCCESS, 'invalid-success-results')
    deadline = clock() + timeout

    def remaining():
        value = deadline - clock()
        if value <= 0:
            raise TimeoutError('oneshot-verification-timeout')
        return value

    while True:
        remaining()
        before = completed(read_snapshot(), accepted)
        remaining()
        if before is not None:
            with try_lock() as acquired:
                if acquired:
                    locked = completed(read_snapshot(), accepted)
                    remaining()
                    if locked == before:
                        result = inspect()
                        remaining()
                        after = completed(read_snapshot(), accepted)
                        remaining()
                        if after == locked:
                            return {'invocation': after, 'inspection': result}
        sleep(min(interval, remaining()))


def command(args):
    return subprocess.check_output(args, stderr=subprocess.DEVNULL, timeout=15)


def systemd_snapshot(run=command, read_boot=lambda: Path('/proc/sys/kernel/random/boot_id').read_text().strip()):
    """Query fixed service metadata and current-boot journal; never control units."""
    boot = read_boot()
    args = ['systemctl', 'show', SERVICE]
    for name in PROPERTIES:
        args += ['-p', name]
    props = dict(line.split('=', 1) for line in run(args).decode().splitlines())
    journal = []
    if props.get('ActiveState') == 'inactive':
        raw = run(['journalctl', '-b', '-u', SERVICE, '--output', 'json', '--no-pager'])
        journal = [json.loads(line) for line in raw.splitlines()]
    require(read_boot() == boot, 'boot-changed-during-snapshot')
    return {'boot_id': boot, 'properties': props, 'journal': journal}


@contextmanager
def deployment_lock(path):
    """Use the caller's already-validated existing lock file; do not create one."""
    import fcntl
    with Path(path).open('rb') as stream:
        acquired = False
        try:
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
            except BlockingIOError:
                pass
            yield acquired
        finally:
            if acquired:
                fcntl.flock(stream, fcntl.LOCK_UN)
