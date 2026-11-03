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

    def test_missing_posix_runtime_is_blocked_without_breaking_module_discovery(self):
        import builtins
        from types import SimpleNamespace
        original_import = builtins.__import__
        def without_fcntl(name, *args, **kwargs):
            if name == 'fcntl':
                raise ModuleNotFoundError('simulated unsupported platform')
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=without_fcntl):
            spec = importlib.util.spec_from_file_location('harness_without_fcntl', Path(__file__).with_name('e2e.py'))
            module = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = module
            spec.loader.exec_module(module)
            report, code = module.handle(SimpleNamespace(command='e2e'), self.root)
        self.assertEqual(code, 3)
        self.assertEqual(report['status'], 'BLOCKED')
        self.assertIn('fcntl', report['reason'])

    def test_stage_cleanup_uses_actor_ids_and_rejects_live_ownership_mismatch(self):
        actor = '12345678-1234-4234-8234-123456789012'
        project_id = '22345678-1234-4234-8234-123456789012'
        self.state['actors'] = {'A': actor}
        resource = {'type': 'project', 'id': project_id, 'projectKey': e.project_key('a' * 16, 'A'),
                    'slot': 'A', 'actor': actor, 'runId': 'a' * 16, 'origin': 'created-response'}
        self.state['resources'] = [resource]
        http = e.Http('https://fixture.invalid')
        path = '/api/v1/projects/' + project_id
        actual = {'id': project_id, 'projectKey': resource['projectKey'],
                  'createdBy': 'foreign-actor', 'currentUserRole': 'ADMIN', 'archivedAt': None}
        with patch.object(http, 'expect', return_value=e.Response(200, {}, json.dumps(actual).encode())) as request:
            with self.assertRaises(e.Invalid):
                e.cleanup_projects(self.store, self.state, {actor: http})
        self.assertEqual(request.call_args_list[0].args, ('GET', path, 200))
        self.assertEqual(request.call_count, 1)
        self.assertNotIn('archived', resource)
        actual['createdBy'] = actor
        def successful(method, route, status):
            if method == 'GET' and route == path:
                return e.Response(200, {}, json.dumps(actual).encode())
            if method == 'GET' and route == path + '/members':
                return e.Response(200, {}, json.dumps({'members': [{'userId': actor, 'role': 'ADMIN'}]}).encode())
            if method == 'DELETE' and route == path:
                return e.Response(200, {}, b'{"archivedAt":"2026-11-03T00:00:00Z"}')
            self.fail('Unexpected cleanup operation')
        with patch.object(http, 'expect', side_effect=successful):
            e.cleanup_projects(self.store, self.state, {actor: http})
        self.assertEqual(self.state['cleanupStatus'], 'PASS')
        self.assertTrue(resource['archived'])

    def test_interrupt_during_cleanup_still_writes_failed_report(self):
        from types import SimpleNamespace
        args = SimpleNamespace(command='e2e', tier='local', scenario=['E2E-01'], run_id='c' * 16, config=None)
        with patch.object(e, 'catalog', return_value={'E2E-01': 'fixture'}), \
                patch.object(e, 'source', return_value={'sourceHead': 'fixture', 'sourceTree': 'fixture', 'dirty': False}), \
                patch.object(e.Local, 'provision', side_effect=e.Blocked('fixture prerequisite missing')), \
                patch.object(e, 'finish_environment', side_effect=KeyboardInterrupt()):
            report, code = e.handle(args, self.root)
        saved = json.loads((self.store.path('c' * 16).parent / 'report.json').read_text())
        self.assertEqual(code, 1)
        self.assertEqual(saved['status'], 'FAIL')
        self.assertEqual(saved['cleanupStatus'], 'FAIL')
        self.assertTrue(any(r['reason'].startswith('Cleanup interrupted') for r in saved['results']))
        self.assertEqual(self.store.load(self.store.path('c' * 16))['cleanupStatus'], 'FAIL')

    def test_malformed_stage_config_returns_controlled_error(self):
        import copy
        config = {'origin': 'https://fixture.invalid', 'allowedOrigins': ['https://fixture.invalid'],
                  'environmentId': 'test-stage', 'allowProjectArchiveResidue': True,
                  'actors': {role: {'id': str(e.uuid.uuid4()), 'tokenEnv': 'HARNESS_' + role} for role in ('A', 'M', 'V', 'O', 'G')},
                  'revisionPaths': {m: '/revision/' + m for m in e.MODULES[:-1]}}
        mutations = [lambda c: c.update(origin=12), lambda c: c.update(allowedOrigins=None),
                     lambda c: c.update(environmentId=[]), lambda c: c['actors']['A'].update(id=3),
                     lambda c: c['actors']['A'].update(tokenEnv=None), lambda c: c.update(revisionPaths=[]),
                     lambda c: c['revisionPaths'].update({'auth-service': 3})]
        path = self.root / 'stage.json'
        for mutate in mutations:
            invalid = copy.deepcopy(config)
            mutate(invalid)
            e.private_json(path, invalid)
            with self.assertRaises(e.Invalid):
                e.load_config(path)

    def test_cleanup_early_stage_state_is_idempotent_without_network(self):
        from types import SimpleNamespace
        state = self.store.create('b' * 16, 'stage', 'approved-stage', '')
        args = SimpleNamespace(command='cleanup', run_state=self.store.path('b' * 16), config=None)
        with patch.object(e.Http, 'request', side_effect=AssertionError('No HTTP for an empty registry')):
            for _ in range(2):
                report, code = e.handle(args, self.root)
                self.assertEqual(code, 0)
                self.assertEqual(report['cleanupStatus'], 'PASS')

    def test_rejected_creation_clears_intent_and_never_adopts_existing_project(self):
        http = e.Http('https://fixture.invalid')
        with patch.object(http, 'request', return_value=e.Response(409, {}, b'')):
            with self.assertRaises(e.Failure):
                e.create_project(self.store, self.state, http, 'actor', 'A')
        recovered = self.store.load(self.store.path('a' * 16))
        self.assertEqual(recovered['pendingWrites'], [])
        self.assertEqual(recovered['resources'], [])

    def test_lost_duplicate_response_cannot_reconcile_foreign_creation(self):
        http = e.Http('https://fixture.invalid')
        with patch.object(http, 'request', side_effect=e.UnknownWrite('lost response')):
            with self.assertRaises(e.UnknownWrite):
                e.create_project(self.store, self.state, http, 'actor', 'A')
        pending = self.state['pendingWrites'][0]
        old = {'id': '12345678-1234-4234-8234-123456789012', 'createdBy': 'actor',
               'projectKey': pending['projectKey'], 'description': 'Harness creation from another run'}
        with patch.object(http, 'expect', return_value=e.Response(200, {}, json.dumps({'items': [old]}).encode())):
            with self.assertRaises(e.Failure):
                e.reconcile_projects(self.store, self.state, {'actor': http})
        self.assertEqual(self.state['resources'], [])
        old['description'] = pending['creationMarker']
        with patch.object(http, 'expect', return_value=e.Response(200, {}, json.dumps({'items': [old]}).encode())):
            e.reconcile_projects(self.store, self.state, {'actor': http})
        self.assertEqual(self.state['resources'][0]['id'], old['id'])
        self.assertEqual(self.state['pendingWrites'], [])

    def test_project_keys_preserve_entire_run_identity(self):
        self.assertNotEqual(e.project_key('a' * 14 + '01', 'A'), e.project_key('a' * 14 + '02', 'A'))

    def test_packaged_artifact_name_uses_pom_version_and_final_name(self):
        (self.root / 'service').mkdir()
        (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.0-SNAPSHOT</version></project>')
        module = self.root / 'service/pom.xml'
        module.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>service</artifactId></project>')
        self.assertEqual(e.packaged_jar(self.root, 'service').name, 'service-2.0-SNAPSHOT.jar')
        module.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>service</artifactId><version>3.0</version><build><finalName>custom-${project.version}</finalName></build></project>')
        self.assertEqual(e.packaged_jar(self.root, 'service').name, 'custom-3.0.jar')

    def test_keyboard_interrupt_is_preserved_after_child_reaping(self):
        from unittest.mock import Mock
        child = Mock()
        child.pid = 1234
        child.communicate.side_effect = [KeyboardInterrupt(), ('', '')]
        with patch.object(e.subprocess, 'Popen', return_value=child), patch.object(e.os, 'killpg'), patch.object(e.time, 'sleep'):
            with self.assertRaises(KeyboardInterrupt):
                e.run_command(['fixture'], self.root)
        self.assertEqual(child.communicate.call_count, 2)

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
