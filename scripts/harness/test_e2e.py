"""Own fake HTTP/Docker boundaries; no fault injection into shared environments."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import URLError

SPEC = importlib.util.spec_from_file_location('taska_e2e_tested', Path(__file__).with_name('e2e.py'))
e = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = e
SPEC.loader.exec_module(e)


class E2ETest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.store = e.StateStore(self.root)
        self.state = self.store.create('a' * 16, 'local', 'isolated-compose', 'actor')

    def test_state_tampering_and_checkout_substitution_rejected(self):
        path = self.store.path(self.state['runId'])
        envelope = json.loads(path.read_text())
        envelope['state']['environment'] = 'other-stage'
        e.private_json(path, envelope)
        with self.assertRaises(e.Invalid):
            self.store.load(path)

    def test_private_modes_and_symlink_rejected(self):
        path = self.store.path(self.state['runId'])
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        path.chmod(0o644)
        with self.assertRaises(e.Invalid):
            self.store.load(path)
        path.unlink()
        path.symlink_to(self.store.keyfile)
        with self.assertRaises(e.Invalid):
            self.store.load(path)

    def test_two_runs_and_duplicate_run_are_isolated(self):
        other = self.store.create('b' * 16, 'local', 'isolated-compose', 'actor')
        self.assertNotEqual(other['ownerNonce'], self.state['ownerNonce'])
        with self.assertRaises(FileExistsError):
            self.store.create('a' * 16, 'local', 'isolated-compose', 'actor')
        self.assertEqual(self.store.load(self.store.path('b' * 16)), other)

    def test_revision_outage_does_not_skip_or_falsify_cleanup(self):
        self.state['composeProject'] = 'fixture'
        report = {'results': [], 'serviceRevisionsBefore': {'service': 'old'}, 'serviceRevisionsAfter': {}}
        local = e.Local(self.root, self.store, self.state)
        def cleaned():
            self.state['cleanupStatus'] = 'PASS'
        with patch.object(local, 'revisions', side_effect=e.Blocked('stopped')):
            with patch.object(local, 'cleanup', side_effect=cleaned) as cleanup:
                e.finish_environment(self.store, self.state, report, local=local)
        cleanup.assert_called_once()
        self.assertEqual(self.state['cleanupStatus'], 'PASS')
        self.assertTrue(any(r['status'] == 'BLOCKED' for r in report['results']))
        self.assertFalse(any(r['checkId'] == 'cleanup' for r in report['results']))

    def test_daemon_switch_refuses_cleanup_before_deletion(self):
        self.state['dockerDaemon'] = 'original-daemon'
        self.state['resources'] = [{'type': 'owned'}]
        with patch.object(e, 'run_command', return_value='other-daemon') as command:
            with self.assertRaises(e.Invalid):
                e.Local(self.root, self.store, self.state).cleanup()
        self.assertEqual(command.call_count, 1)
        self.assertEqual(self.state['resources'], [{'type': 'owned'}])

    def test_parallel_first_store_creation_publishes_complete_key(self):
        import concurrent.futures
        directory = self.root / 'parallel'
        directory.mkdir()
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            keys = list(pool.map(lambda _: e.StateStore(directory).key, range(24)))
        self.assertEqual(len(set(keys)), 1)
        self.assertEqual(len(keys[0]), 32)

    def test_run_lock_prevents_simultaneous_cleanup(self):
        path = self.store.directory / 'test.lock'
        with e.mutex(path):
            with self.assertRaises(e.Invalid):
                with e.mutex(path):
                    self.fail('Concurrent RUN lock acquired')

    def test_stage_actor_role_and_status_checked(self):
        config = {'origin': 'https://fixture.invalid', 'actors': {'G': {'id': 'actor', 'tokenEnv': 'HARNESS_TEST_TOKEN'}}}
        with patch.dict(os.environ, HARNESS_TEST_TOKEN='not-a-real-token'):
            for profile in ({'id': 'actor', 'status': 'ACTIVE', 'globalRole': 'USER'},
                            {'id': 'actor', 'status': 'BLOCKED', 'globalRole': 'GLOBAL_ADMIN'}):
                with patch.object(e.Http, 'expect', return_value=e.Response(200, {}, json.dumps(profile).encode())):
                    with self.assertRaises(e.Failure):
                        e.stage_clients(config)

    def test_http_casing_empty_body_and_no_redirect(self):
        r = e.Response(401, {'WWW-AuThEnTiCaTe': 'Bearer'}, b'')
        self.assertEqual(r.header('www-authenticate'), 'Bearer')
        self.assertIsNone(r.json())
        self.assertIsNone(e.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid'))

    def test_write_timeout_is_not_retried_and_intent_survives(self):
        http = e.Http('https://fixture.invalid')
        with patch.object(http.opener, 'open', side_effect=URLError('timed out')) as send:
            with self.assertRaises(e.UnknownWrite):
                e.create_project(self.store, self.state, http, 'actor', 'A')
        self.assertEqual(send.call_count, 1)
        recovered = self.store.load(self.store.path('a' * 16))
        self.assertEqual(len(recovered['pendingWrites']), 1)
        self.assertEqual(recovered['resources'], [])

    def test_reconcile_unknown_write_does_not_claim_absence(self):
        self.state['pendingWrites'] = [{'slot': 'A', 'actor': 'actor', 'projectKey': e.project_key('a' * 16, 'A')}]
        client = e.Http('https://fixture.invalid')
        with patch.object(client, 'expect', return_value=e.Response(200, {}, b'{"items":[]}')):
            with self.assertRaises(e.Failure):
                e.reconcile_projects(self.store, self.state, {'actor': client})
        self.assertEqual(len(self.state['pendingWrites']), 1)

    def test_foreign_container_prevents_every_deletion(self):
        local = e.Local(self.root, self.store, self.state)
        calls = []
        def docker(argv, *args, **kwargs):
            calls.append(argv)
            if argv[1:3] == ['ps', '-aq']:
                return 'owned foreign'
            if argv[1:3] == ['network', 'ls']:
                return ''
            if argv[1:3] == ['container', 'inspect']:
                return json.dumps([{'Config': {'Labels': {e.LABEL: self.state['ownerNonce'], 'com.docker.compose.project': local.project}}},
                                   {'Config': {'Labels': {e.LABEL: 'foreign', 'com.docker.compose.project': local.project}}}])
            self.fail('Unexpected mutation: ' + repr(argv))
        with patch.object(e, 'run_command', side_effect=docker):
            with self.assertRaises(e.Invalid):
                local.cleanup()
        self.assertFalse(any('rm' in a for a in calls))

    def test_cleanup_is_idempotent_when_resources_absent(self):
        with patch.object(e, 'run_command', return_value=''):
            e.Local(self.root, self.store, self.state).cleanup()
            e.Local(self.root, self.store, self.state).cleanup()
        self.assertEqual(self.state['cleanupStatus'], 'PASS')

    def test_cleanup_failure_retains_registry(self):
        self.state['resources'] = [{'type': 'fixture-marker'}]
        with patch.object(e, 'run_command', side_effect=e.Failure('unavailable')):
            with self.assertRaises(e.Failure):
                e.Local(self.root, self.store, self.state).cleanup()
        self.assertEqual(len(self.state['resources']), 1)
        self.assertNotEqual(self.state['cleanupStatus'], 'PASS')

    def test_missing_capability_or_changed_revision_never_pass(self):
        self.assertEqual(e.capability_result(['mailbox'], {})['status'], 'BLOCKED')
        self.assertEqual(e.revision_result({'gateway': 'old'}, {'gateway': 'new'})['status'], 'PARTIAL')
        self.assertEqual(e.revision_result({}, {})['status'], 'BLOCKED')

    def test_projection_keeps_business_url_and_oauth_ids_distinct_types(self):
        self.assertEqual(e.business_projection({'avatarUrl': 'ephemeral', 'callbackUrl': 'business'}), {'callbackUrl': 'business'})
        e.OAuthIds('12345678-1234-4234-8234-123456789012', 'https://client.invalid/metadata')
        with self.assertRaises(e.Invalid):
            e.OAuthIds('https://client.invalid/metadata', 'internal-id')

    def test_timeout_terminates_process_group(self):
        marker = self.root / 'late-write'
        child_code = 'import time,pathlib;time.sleep(0.6);pathlib.Path(%r).write_text("bad")' % str(marker)
        leader = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",%r]);time.sleep(10)' % child_code
        with self.assertRaises(e.Failure):
            e.run_command([sys.executable, '-c', leader], self.root, timeout=0.1)
        import time
        time.sleep(0.8)
        self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
