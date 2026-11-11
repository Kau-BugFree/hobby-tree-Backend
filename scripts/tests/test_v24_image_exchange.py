"""No-network image exchange rehearsal with real V24 journal and nonce files."""
import copy
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from test_v24_transition import H, P, T, module, plan

X = module('v24_exchange_image')


@unittest.skipUnless(os.name == 'posix' and os.geteuid() == 0, 'isolated Linux root fixtures only')
class ExchangeTests(unittest.TestCase):
    def setUp(self):
        """Prepare a running V24, two signed successor plans and an isolated ledger."""
        temp = tempfile.TemporaryDirectory(dir='/run')
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.install = self.root/'install'
        self.install.mkdir()
        self.pointer, self.ticket, self.env = (self.root/name for name in ('pointer', 'ticket', 'settings'))
        patcher = patch.multiple(H, STATE=self.root, INSTALL=self.install, POINTER=self.pointer, TICKET=self.ticket)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.source = plan()
        self.forward = copy.deepcopy(self.source)
        self.forward.update(request='b'*32, snapshot='hobbytree-v24-'+'b'*32, revision='2'*40,
                            previous=self.source['candidate'], previous_image_id=self.source['candidate_image_id'],
                            candidate='sha256:'+'2'*64, candidate_image_id='sha256:'+'3'*64,
                            candidate_jar_sha256=P.sha(b'candidate jar'))
        self.source['candidate_jar_sha256'] = P.sha(b'previous jar')
        for name in X.REPLACED_TOOLS:
            old, new = ('previous '+name).encode(), ('candidate '+name).encode()
            P.create(self.install/name, old, 0o500)
            self.source['bundle'][name] = P.sha(old)
            self.forward['bundle'][name] = P.sha(new)
        self.rollback = dict(self.source, request='c'*32, snapshot='hobbytree-v24-'+'c'*32,
                             previous=self.forward['candidate'], previous_image_id=self.forward['candidate_image_id'],
                             bundle=self.forward['bundle'])
        self.hosts = {}
        self.settings = b'BACKEND_IMAGE=previous\nKEY=unchanged\n'
        P.create(self.env, self.settings)
        P.create(self.pointer, (self.source['request']+'\n').encode())
        self.running, self.target, self.invocation = True, 'healthy', '1'*32
        self.actions = []
        for direction, target in (('source', self.source), ('forward', self.forward), ('rollback', self.rollback)):
            self.make_host(direction, target)
        source_host = self.hosts['source']
        source_host.journal.directory.mkdir()
        P.create(source_host.journal.directory/'plan.json', P.canonical(self.source))
        self.source_nonce = '1'*32
        self.current = self.record_container(source_host, self.source_nonce, '5'*64)
        self.source_state = dict(request=self.source['request'], plan_sha256=source_host.journal.plan_hash,
                                 phase='complete', status='v24-complete', approvals={'activate': self.source_nonce},
                                 facts=dict(schema_version=24, candidate_settings_sha256=P.sha(self.settings),
                                            candidate_container_id='5'*64, active_approval_id=self.source_nonce,
                                            catalogue_rows=3570, redis_id='9'*64, redis_mounts=[]))
        self.source_raw = P.canonical(self.source_state)
        P.create(source_host.journal.directory/'state.json', self.source_raw)
        P.create(self.root/'active.json', self.source_raw)
        self.manifest = dict(format=1, source_request=self.source['request'],
                             source_plan_sha256=source_host.journal.plan_hash, source_state_sha256=P.sha(self.source_raw),
                             forward_plan_sha256=self.hosts['forward'].journal.plan_hash,
                             rollback_plan_sha256=self.hosts['rollback'].journal.plan_hash, drain_seconds=300)
        self.signature = patch.object(T, 'signature')
        self.signature.start()
        self.addCleanup(self.signature.stop)
        self.cors = patch.object(X, 'verify_runtime',
                                 side_effect=lambda *args, public=False: self.actions.append('public-http' if public else 'http'))
        self.http_probe = self.cors.start()
        self.addCleanup(self.cors.stop)
        patcher = patch.object(X, 'request_headers', return_value=(200, {}))
        patcher.start()
        self.addCleanup(patcher.stop)

    def make_host(self, direction, target):
        """Connect each proposed request to the same simulated Docker and ALB state."""
        bundle = self.root/direction
        bundle.mkdir()
        for name in X.REPLACED_TOOLS:
            P.create(bundle/name, ('candidate '+name).encode())
        nonce_prefix = {'source': '1', 'forward': '2', 'rollback': '3'}[direction]
        jar = b'candidate jar' if direction == 'forward' else b'previous jar'
        P.create(bundle/'candidate.jar', jar)
        journal = P.Journal(self.root, target['request'], P.sha(P.canonical(target)))
        host = Mock(plan=target, bundle=bundle, journal=journal, scratch=bundle)
        host.runtime.image = 'repo@'+target['candidate']
        host.runtime.container.side_effect = lambda _: self.container()
        host.runtime.extract_schema.return_value = target['after_schema']
        host.image_check.return_value = {'Config': {'Env': []}}
        host.compose.return_value = ([], {'environment': {}})
        host.release.ENV_FILE = self.env
        host.release.image_in_env.side_effect = lambda body, image: b'BACKEND_IMAGE='+image.encode()+b'\n'+body.split(b'\n', 1)[1]
        host.runtime.target_state.side_effect = lambda: self.target
        host.aws.side_effect = lambda *args: self.describe_target(args, target)
        host.target.side_effect = self.change_target
        host.run.side_effect = self.command
        host.cleanup_database.return_value = True
        host.perform.side_effect = lambda phase, state: self.perform(host, phase, state, nonce_prefix)
        for index, phase in enumerate(X.PHASES):
            now = int(time.time())
            value = dict(format=1, action='hobbytree-v18-v24', request=target['request'], phase=phase,
                         plan_sha256=journal.plan_hash, approval_id=nonce_prefix+str(index)*31,
                         issued_at=now-1, expires_at=now+3600)
            P.create(bundle/(phase+'.approval.json'), P.canonical(value))
            P.create(bundle/(phase+'.approval.sig'), b'fixture')
        self.hosts[direction] = host

    def describe_target(self, args, plan):
        """Return the fixed target and the reviewed five-minute drain delay."""
        if args[1] == 'describe-target-group-attributes':
            return {'Attributes': [{'Key': 'deregistration_delay.timeout_seconds', 'Value': '300'}]}
        return {'TargetHealthDescriptions': [] if self.target == 'unused' else [
            {'Target': {'Id': plan['instance'], 'Port': 8080}, 'TargetHealth': {'State': self.target}}]}

    def change_target(self, action):
        """Represent asynchronous deregistration in the simulated ALB."""
        self.actions.append(action)
        self.target = 'draining' if action == 'deregister-targets' else 'healthy'

    def command(self, argv, *unused):
        """Model drain wait, systemd and bounded Docker observations."""
        if argv[:3] == ['aws', 'elbv2', 'wait']:
            self.actions.append('wait')
            self.target = 'unused'
        elif argv[:2] == ['systemctl', 'stop']:
            self.actions.append('stop')
            self.running = False
        elif argv[:2] == ['systemctl', 'show']:
            return ('ActiveState='+('active' if self.running else 'inactive')+
                    '\nUnitFileState=disabled\nInvocationID='+self.invocation+'\n').encode()
        elif argv[:2] == ['docker', 'ps']:
            return self.current['Id'].encode() if self.current else b''
        else:
            self.fail('Unexpected command')
        return b''

    def container(self):
        """Report the recorded container with its actual simulated running state."""
        status = 'running' if self.running else self.current.get('fixture_status', 'exited')
        return dict(self.current, State={'Running': self.running, 'Status': status, 'Health': {'Status': 'healthy'}})

    def record_container(self, host, nonce, cid):
        """Write the same ownership evidence generated by the existing start hook."""
        P.create(host.journal.directory/('backend-'+nonce+'.json'), P.canonical(
            dict(container_id=cid, approval_id=nonce, invocation=self.invocation, bundle=str(host.bundle))))
        return dict(Id=cid, Image=host.plan['candidate_image_id'], Name='/hobbytree-backend-1',
                    Config={'Image': host.runtime.image, 'Env': [], 'Labels': {
                        'hobbytree.v24.request': host.plan['request'], 'hobbytree.v24.nonce': nonce}},
                    HostConfig={'RestartPolicy': {'Name': 'no'}})

    def perform(self, host, phase, state, prefix):
        """Emulate successful guarded start and stable publication."""
        self.actions.append(phase)
        if phase == 'publish':
            self.change_target('register-targets')
            return {'alb_stable_seconds': 150}
        H.Host.stopped_predecessor(host, state)
        nonce = state['approvals']['activate']
        self.invocation = prefix*32
        cid = prefix*64
        self.current = self.record_container(host, nonce, cid)
        self.running = True
        return {'candidate_container_id': cid, 'active_approval_id': nonce}

    def execute(self, direction):
        """Run the real orchestration with the signed manifest represented by test bytes."""
        raw = P.canonical(self.manifest)
        now = int(time.time())
        approval = dict(format=1, action=X.ACTION, direction=direction, manifest_sha256=P.sha(raw),
                        tool_sha256='4'*64, approval_id=('8' if direction == 'forward' else '9')*32,
                        issued_at=now-1, expires_at=now+3600)
        X.validate_plans(P, self.manifest, self.source, self.forward, self.rollback)
        host = self.hosts[direction]
        reserve = (('9'*32, '3'+'0'*31, '3'+'1'*31), now+3600) if direction == 'forward' else None
        return X.exchange(P, H, T, host, self.hosts['source'], self.hosts['forward'], self.manifest, approval,
                          P.canonical(approval), b'fixture', P.canonical(host.plan), raw, reserve)

    def test_forward_preserves_the_original_and_checks_http_before_publication(self):
        """Apply only the new image and retain V24 catalogue evidence and source journal."""
        self.assertEqual(self.execute('forward')['phase'], 'complete')
        self.assertEqual(self.actions, ['deregister-targets', 'wait', 'stop', 'activate', 'http',
                                       'publish', 'register-targets', 'public-http'])
        forward = self.hosts['forward'].journal.load()
        self.assertEqual(forward['facts']['catalogue_rows'], 3570)
        self.assertEqual(P.read(self.hosts['source'].journal.directory/'state.json'), self.source_raw)
        self.assertEqual(self.current['Image'], self.forward['candidate_image_id'])
        with self.assertRaises(ValueError):
            self.execute('rollback')

    def test_cors_failure_excludes_candidate_and_recovers_previous_image(self):
        """A failed candidate never enters traffic and a fresh rollback restores the old image."""
        self.http_probe.side_effect = [ValueError('CORS failed'), None, None]
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertEqual(self.target, 'unused')
        self.assertFalse(self.running)
        failed = P.read(self.hosts['forward'].journal.directory/'state.json')
        self.assertEqual(P.strict_json(failed)['phase'], 'failed')
        self.assertEqual(self.execute('rollback')['phase'], 'complete')
        self.assertEqual(P.read(self.hosts['forward'].journal.directory/'state.json'), failed)
        self.assertEqual(P.read(self.env), self.settings)
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])
        self.assertEqual(self.target, 'healthy')

    def test_failed_drain_never_stops_a_draining_backend(self):
        """Preserve the original service until the rollback can observe a completed drain."""
        def fail_wait(argv, *args):
            """Fail only the drain waiter after the deregistration request."""
            if argv[:3] == ['aws', 'elbv2', 'wait']:
                raise TimeoutError('fixture')
            return self.command(argv, *args)

        self.hosts['forward'].run.side_effect = fail_wait
        with self.assertRaises(TimeoutError):
            self.execute('forward')
        self.assertTrue(self.running)
        self.assertNotIn('stop', self.actions)
        self.assertEqual(P.read(self.root/'active.json'), self.source_raw)
        self.execute('rollback')
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])

    def test_rollback_can_start_when_failed_create_left_no_backend(self):
        """An approved rollback admits absence only after observing the fixed Docker name."""
        def fail_create(*unused):
            """Simulate Compose removing the old container then failing to create a new one."""
            self.current = None
            raise RuntimeError('fixture')

        self.hosts['forward'].perform.side_effect = fail_create
        with self.assertRaises(RuntimeError):
            self.execute('forward')
        self.execute('rollback')
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])

    def test_rollback_accepts_only_unstarted_create_with_verified_intent(self):
        """Recover a Compose create that succeeded before Docker inspect returned."""
        forward = self.hosts['forward']

        def partial_create(phase, state):
            """Leave a created candidate with an intent and no post-create ledger."""
            nonce = state['approvals'][phase]
            self.invocation = '2'*32
            intent = T.creation_intent(forward, state, self.invocation, nonce, forward.bundle)
            P.create(forward.journal.directory/('create-backend-'+nonce+'.json'), P.canonical(intent))
            self.current = self.record_container(forward, nonce, '2'*64)
            self.current['fixture_status'] = 'created'
            (forward.journal.directory/('backend-'+nonce+'.json')).unlink()
            raise RuntimeError('fixture')

        forward.perform.side_effect = partial_create
        with self.assertRaises(RuntimeError):
            self.execute('forward')
        self.current['fixture_status'] = 'exited'
        before = self.actions[:]
        with self.assertRaises(ValueError):
            self.execute('rollback')
        self.assertEqual(self.actions, before)
        self.current['fixture_status'] = 'created'
        self.execute('rollback')
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])

    def test_only_ticket_from_the_failed_activation_is_archived_on_rollback(self):
        """Require the exact failed request, signature and boot before removing its ticket."""
        forward = self.hosts['forward']

        def failed_start(phase, state):
            """Leave the guarded start ticket behind when systemd fails before consuming it."""
            P.create(self.ticket, P.canonical(dict(request=self.forward['request'],
                plan_sha256=forward.journal.plan_hash, phase=phase, approval_id=state['approvals'][phase],
                boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                verified_at=int(time.time()), bundle=str(forward.bundle))))
            raise RuntimeError('fixture')

        forward.perform.side_effect = failed_start
        with self.assertRaises(RuntimeError):
            self.execute('forward')
        original = P.read(self.ticket)
        P.replace(self.ticket, P.canonical(dict(P.strict_json(original), approval_id='f'*32)))
        before = self.actions[:]
        with self.assertRaises(ValueError):
            self.execute('rollback')
        self.assertEqual(self.actions, before)
        self.assertTrue(self.ticket.exists())
        P.replace(self.ticket, original)
        self.execute('rollback')
        self.assertFalse(self.ticket.exists())
        self.assertEqual(P.read(self.hosts['rollback'].journal.directory/'forward-ticket.before.json'), original)

    def test_failure_after_registration_keeps_draining_container_until_rollback(self):
        """Allow ALB to drain already routed requests before stopping the candidate."""
        host = self.hosts['forward']

        def fail_published(phase, state):
            """Fail after ALB registration while preserving the candidate's ledger."""
            facts = self.perform(host, phase, state, '2')
            if phase == 'publish':
                raise ValueError('fixture')
            return facts

        host.perform.side_effect = fail_published
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertEqual(self.target, 'draining')
        self.assertTrue(self.running)
        self.execute('rollback')
        self.assertEqual(self.target, 'healthy')
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])

    def test_public_cors_failure_is_still_recoverable_after_healthy_alb(self):
        """Complete only after external validation, retaining a rollback path on public CORS failure."""
        self.http_probe.side_effect = [None, ValueError('public CORS failed'), None, None]
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertEqual(self.hosts['forward'].journal.load()['phase'], 'failed')
        self.assertEqual(self.target, 'draining')
        self.execute('rollback')
        self.assertEqual(self.current['Image'], self.source['candidate_image_id'])

    def test_schema_drift_or_wrong_rollback_identity_is_refused_before_drain(self):
        """Reject any SQL change, even if other signed plan fields match."""
        self.hosts['forward'].runtime.extract_schema.return_value = '0'*64
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertEqual(self.actions, [])
        self.rollback['revision'] = '8'*40
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertTrue(self.running)

    def test_unknown_container_refuses_rollback_without_stopping_it(self):
        """A different container cannot be adopted just because its name matches."""
        self.http_probe.side_effect = ValueError('fixture')
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.current['Id'] = 'e'*64
        old_actions = self.actions[:]
        with self.assertRaises(ValueError):
            self.execute('rollback')
        self.assertEqual(self.actions, old_actions)

    def test_containment_reports_failure_if_candidate_did_not_stop(self):
        """An inactive unit alone is insufficient to claim the backend stopped."""
        host = self.hosts['forward']
        self.http_probe.side_effect = ValueError('fixture')

        def incomplete_stop(argv, *args):
            """Report an inactive unit while the candidate container is still running."""
            if self.current['Id'] == '2'*64:
                if argv[:2] == ['systemctl', 'stop']:
                    return b''
                if argv[:2] == ['systemctl', 'show']:
                    return b'ActiveState=inactive\nUnitFileState=disabled\n'
            return self.command(argv, *args)

        host.run.side_effect = incomplete_stop
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertFalse(host.journal.load()['containment']['stop'])
        self.assertTrue(self.running)

    def test_unverified_forward_approval_cannot_authorize_rollback(self):
        """Rollback requires the original consumed forward approval for the same manifest."""
        self.http_probe.side_effect = ValueError('fixture')
        with self.assertRaises(ValueError):
            self.execute('forward')
        (self.root/('v24-approval-'+'8'*32+'.used')).unlink()
        with self.assertRaises(FileNotFoundError):
            self.execute('rollback')

    def test_losing_the_publication_pair_preserves_the_pending_evidence(self):
        """A partial journal publication cannot be guessed or rolled back implicitly."""
        replace = P.replace

        def fail_active(path, data, mode=0o600):
            """Inject an interruption between the two durable journal writes."""
            if path == self.root/'active.json':
                raise OSError('fixture')
            return replace(path, data, mode)

        with patch.object(P, 'replace', side_effect=fail_active), self.assertRaises(OSError):
            self.execute('forward')
        self.assertTrue((self.hosts['forward'].journal.directory/'update.pending.json').exists())
        self.assertEqual(P.read(self.root/'active.json'), self.source_raw)
        with self.assertRaises(ValueError):
            self.execute('rollback')

    def test_preflight_signature_cannot_be_reused(self):
        """A consumed activate nonce blocks the exchange before ALB is changed."""
        P.create(self.root/('v24-approval-'+'2'+'0'*31+'.used'), b'already used')
        with self.assertRaises(ValueError):
            self.execute('forward')
        self.assertEqual(self.actions, [])

    def test_missing_or_expiring_rollback_prevents_forward_admission(self):
        """Reserve both rollback artifacts and enough time for the two attempts."""
        host = self.hosts['rollback']
        manifest, tool = P.canonical(self.manifest), b'fixture tool'
        with self.assertRaises(FileNotFoundError):
            X.ready_rollback(P, H, T, host, manifest, tool)
        now = int(time.time())
        proof = dict(format=1, action=X.ACTION, direction='rollback', manifest_sha256=P.sha(manifest),
                     tool_sha256=P.sha(tool), approval_id='9'*32, issued_at=now, expires_at=now+2000)
        path = host.bundle/'exchange.approval.json'
        P.create(path, P.canonical(proof))
        P.create(host.bundle/'exchange.approval.sig', b'fixture')
        with patch.object(X, 'verify_signature'):
            with self.assertRaises(ValueError):
                X.ready_rollback(P, H, T, host, manifest, tool)
            proof['expires_at'] = now+3600
            P.replace(path, P.canonical(proof))
            nonces, expiry = X.ready_rollback(P, H, T, host, manifest, tool)
            self.assertEqual(len(nonces), 3)
            self.assertGreaterEqual(expiry, now+3599)
        self.assertEqual(self.actions, [])

    def test_delayed_forward_refuses_to_drain_after_losing_rollback_reserve(self):
        """Recheck approval windows after journal preparation and before the first ALB mutation."""
        host = self.hosts['forward']
        real_save = host.journal.save
        now = int(time.time())
        clock = Mock(return_value=now)

        def delayed_save(state, block_legacy=True):
            """Simulate staging taking long enough to consume the rollback reserve."""
            real_save(state, block_legacy=block_legacy)
            clock.return_value = now+1000

        with patch.object(X.time, 'time', clock), patch.object(host.journal, 'save', side_effect=delayed_save):
            with self.assertRaises(ValueError):
                self.execute('forward')
        self.assertEqual(self.actions, [])
        self.assertTrue(self.running)
        self.assertEqual(P.read(self.root/'active.json'), self.source_raw)
        with self.assertRaises(ValueError):
            self.execute('rollback')

    def test_phase_approval_window_covers_publish_admission(self):
        """An approval with only 30 seconds beyond the controller window is inadequate."""
        now = int(time.time())
        proof = dict(direction='rollback', expires_at=now+3600)
        for expires in (now+X.EXCHANGE_WINDOW+30, now+X.EXCHANGE_WINDOW+X.PHASE_RESERVE):
            approvals = {'publish': (dict(expires_at=expires), b'', b'')}
            with patch.object(X.time, 'time', return_value=now):
                if expires < now+X.EXCHANGE_WINDOW+X.PHASE_RESERVE:
                    with self.assertRaises(ValueError):
                        X.require_exchange_windows(P, proof, approvals, None)
                else:
                    X.require_exchange_windows(P, proof, approvals, None)

    def test_exact_rsa_proof_rejects_modified_manifest_or_executable(self):
        """Use a real fixture RSA signature and compare the authorized byte identities."""
        private, public = self.root/'private.pem', self.root/'public.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048',
                        '-out', str(private)], check=True, capture_output=True)
        subprocess.run(['openssl', 'pkey', '-in', str(private), '-pubout', '-out', str(public)],
                       check=True, capture_output=True)
        manifest, tool = P.canonical(self.manifest), Path(X.__file__).read_bytes()
        now = int(time.time())
        raw = P.canonical(dict(format=1, action=X.ACTION, direction='forward', manifest_sha256=P.sha(manifest),
                               tool_sha256=P.sha(tool), approval_id='8'*32, issued_at=now, expires_at=now+3600))
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(private)],
                                   input=raw, check=True, capture_output=True).stdout
        with patch.multiple(X, PUBLIC_KEY=public, PUBLIC_SHA=P.sha(public.read_bytes())):
            X.verify_signature(raw, signature)
            with self.assertRaises(subprocess.CalledProcessError):
                X.verify_signature(raw+b' ', signature)
        X.validate_approval(P, raw, manifest, tool)
        for changed_manifest, changed_tool in ((manifest+b' ', tool), (manifest, tool+b' ')):
            with self.assertRaises(ValueError):
                X.validate_approval(P, raw, changed_manifest, changed_tool)
