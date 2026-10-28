"""Hermetic harness acceptance tests: real subprocesses and disposable Git repos."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('taska_harness', Path(__file__).resolve().parents[1] / 'harness.py')
h = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(h)


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='taska-harness-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.manifest = copy.deepcopy(h.load_manifest(h.ROOT))

    def write(self, path, text):
        p = self.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return p

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode().strip()

    def repo(self):
        self.git('init', '-q')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'user.name', 'Harness fixture')
        self.git('config', 'commit.gpgsign', 'false')
        for name, deps in [('base', []), ('leaf', ['base']), ('consumer', ['leaf']), ('sibling', ['base'])]:
            xml = '<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>' + name + '</artifactId><dependencies>'
            xml += ''.join('<dependency><artifactId>' + d + '</artifactId></dependency>' for d in deps)
            self.write(name + '/pom.xml', xml + '</dependencies></project>')
            self.write(name + '/src/Main.java', 'class Main {}')
        self.write('.gitignore', 'target/\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        return {'modules': ['base', 'leaf', 'consumer', 'sibling'], 'checks': [
            {'id': n + '.verify', 'module': n, 'group': 'java', 'requires': []}
            for n in ['base', 'leaf', 'consumer', 'sibling']], 'impactRules': []}

    def report(self, name='DemoTest', tests=2, skipped=0, failures=0):
        return self.write('service/target/surefire-reports/TEST-' + name + '.xml',
                          '<testsuite name="example.%s" tests="%s" skipped="%s" failures="%s" errors="0"/>'
                          % (name, tests, skipped, failures))

    def test_report_rejects_stale_or_missing_selector(self):
        p = self.report()
        reports = {'directory': 'service/target/surefire-reports', 'selectors': ['DemoTest']}
        before = h.snapshots(self.root, reports)
        self.assertEqual(h.test_reports(self.root, reports, before)['status'], 'FAIL')
        self.assertEqual(h.test_reports(self.root, reports, {})['status'], 'PASS')
        reports['selectors'] = ['TypoIT']
        self.assertEqual(h.test_reports(self.root, reports, {})['status'], 'FAIL')
        self.assertTrue(p.is_file())

    def test_report_rejects_zero_failure_malformed_and_skipped(self):
        reports = {'directory': 'service/target/surefire-reports', 'selectors': ['DemoTest']}
        for kwargs, expected in [({'tests': 0}, 'FAIL'), ({'failures': 1}, 'FAIL'),
                                 ({'skipped': 2}, 'PARTIAL'), ({'tests': -1}, 'FAIL'),
                                 ({'tests': 1, 'skipped': 2}, 'FAIL')]:
            with self.subTest(kwargs=kwargs):
                self.report(**kwargs)
                self.assertEqual(h.test_reports(self.root, reports, {})['status'], expected)
        self.report().write_text('<malformed')
        self.assertEqual(h.test_reports(self.root, reports, {})['status'], 'FAIL')

    def test_report_requires_each_class_not_only_aggregate(self):
        self.report('OneIT')
        reports = {'directory': 'service/target/surefire-reports', 'selectors': ['OneIT', 'OtherIT']}
        self.assertEqual(h.test_reports(self.root, reports, {})['status'], 'FAIL')
        self.report('OtherIT')
        self.assertEqual(h.test_reports(self.root, reports, {})['status'], 'PASS')

    def test_process_failure_is_redacted(self):
        env = dict(os.environ, FIXTURE_API_KEY='super-private-key')
        code = "import sys; print('Authorization: Bearer opaque\\npassword=plainpass\\nhttps://x.test/?secret=abc\\nsuper-private-key'); sys.exit(7)"
        result = h.process([sys.executable, '-c', code], self.root, 2, env)
        self.assertEqual(result['status'], 'FAIL')
        for secret in ['opaque', 'plainpass', 'secret=abc', 'super-private-key']:
            self.assertNotIn(secret, result['diagnostic'])
        self.assertIn('code 7', result['reason'])

    def test_redaction_protects_credentials_in_various_formats(self):
        text = 'Cookie: session=private\n{"client_secret":"hidden"}\nBearer opaque\nhttps://name:password@host/path?code=private\nghp_1234567'
        safe = h.redact(text, {})
        for secret in ['session=private', 'hidden', 'opaque', 'name:password', 'code=private', 'ghp_1234567']:
            self.assertNotIn(secret, safe)

    def test_process_pass_has_no_raw_output(self):
        result = h.process([sys.executable, '-c', "print('private output')"], self.root, 2)
        self.assertEqual(result, {'status': 'PASS', 'reason': ''})

    def test_timeout_kills_descendant_which_ignores_termination(self):
        pidfile = self.root / 'pid'
        heartbeat = self.root / 'heartbeat'
        child = ('import signal,time; from pathlib import Path; '
                 'signal.signal(signal.SIGTERM,signal.SIG_IGN); '
                 'p=Path(%r)\nwhile True: p.write_text(str(time.time_ns())); time.sleep(0.02)') % str(heartbeat)
        leader = ('import subprocess,sys,time; p=subprocess.Popen([sys.executable,"-c",%r]); '
                  'open(%r,"w").write(str(p.pid)); time.sleep(30)') % (child, str(pidfile))
        result = h.process([sys.executable, '-c', leader], self.root, 0.5)
        self.assertEqual(result['status'], 'FAIL')
        self.assertTrue(heartbeat.is_file())
        last = heartbeat.read_text()
        time.sleep(0.15)
        self.assertEqual(last, heartbeat.read_text(), 'Descendant continued running after timeout')

    def test_missing_binary_is_blocked(self):
        self.assertEqual(h.process(['/definitely/missing/taska-tool'], self.root, 1)['status'], 'BLOCKED')

    def test_argv_is_not_shell_interpreted(self):
        marker = self.root / 'must-not-exist'
        argument = '$(touch %s); echo bad' % marker
        result = h.process([sys.executable, '-c', 'import sys; assert sys.argv[1].startswith("$(")', argument], self.root, 2)
        self.assertEqual(result['status'], 'PASS')
        self.assertFalse(marker.exists())

    def test_plan_downstream_and_upstream_without_sideways_propagation(self):
        m = self.repo()
        self.write('leaf/src/Main.java', 'class Changed {}')
        result = h.plan(self.root, m, 'HEAD')
        self.assertEqual(result['modules'], ['base', 'consumer', 'leaf'])
        self.assertNotIn('sibling.verify', result['checks'])
        self.assertIn('leaf/src/Main.java', result['paths'])

    def test_plan_shared_base_changes_all_consumers(self):
        m = self.repo()
        self.write('base/src/Main.java', 'class Changed {}')
        self.assertEqual(set(h.plan(self.root, m, 'HEAD')['modules']), set(m['modules']))

    def test_plan_untracked_and_deleted_files(self):
        m = self.repo()
        self.write('leaf/src/New.java', 'class New {}')
        (self.root / 'consumer/src/Main.java').unlink()
        paths = h.plan(self.root, m, 'HEAD')['paths']
        self.assertIn('leaf/src/New.java', paths)
        self.assertIn('consumer/src/Main.java', paths)

    def test_plan_docs_only_and_unknown_shared_change(self):
        m = self.repo()
        self.write('docs/new.md', '# Docs')
        self.assertEqual(h.plan(self.root, m, 'HEAD')['modules'], [])
        self.write('unknown.config', 'changed')
        self.assertEqual(set(h.plan(self.root, m, 'HEAD')['modules']), set(m['modules']))

    def test_plan_impact_rules_include_event_consumers(self):
        m = self.repo()
        m['impactRules'] = [{'prefixes': ['leaf/'], 'modules': ['sibling'], 'reason': 'event consumer'}]
        self.write('leaf/src/Main.java', 'changed')
        result = h.plan(self.root, m, 'HEAD')
        self.assertIn('sibling', result['modules'])
        self.assertIn('event consumer', [r['reason'] for r in result['reasons']])

    def test_plan_invalid_or_missing_refs(self):
        m = self.repo()
        for ref in ['--output=bad', 'HEAD;touch bad', '$(id)', 'refs/heads/absent', '']:
            with self.subTest(ref=ref), self.assertRaises(h.Invalid):
                h.plan(self.root, m, ref)

    def test_path_escape_and_symlink_rejected(self):
        with self.assertRaises(h.Invalid):
            h.inside(self.root, '../outside')
        (self.root / 'outside').symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaises(h.Invalid):
            h.inside(self.root, 'outside/path')

    def test_dependency_order_deduplicates_and_rejects_cycle(self):
        m = {'checks': [{'id': 'verify', 'requires': []}, {'id': 'it', 'requires': ['verify']}]}
        self.assertEqual([c['id'] for c in h.ordered_checks(m, ['it', 'verify'])], ['verify', 'it'])
        m['checks'][0]['requires'] = ['it']
        with self.assertRaises(h.Invalid):
            h.ordered_checks(m, ['it'])
        with self.assertRaises(h.Invalid):
            h.ordered_checks(m, ['unknown'])

    def test_failure_or_missing_prerequisite_blocks_dependents(self):
        checks = [{'id': name, 'requires': [] if name == 'first' else ['first'], 'prerequisites': ['docker'],
                   'argv': [sys.executable, '-c', 'raise SystemExit(1)'], 'workdir': '.', 'timeout': 2}
                  for name in ['first', 'second']]
        with patch.object(h, 'capability', return_value={'status': 'BLOCKED'}), contextlib.redirect_stderr(io.StringIO()):
            results = h.run_checks(self.root, {'checks': checks}, ['second'])
        self.assertEqual([r['status'] for r in results], ['BLOCKED', 'BLOCKED'])
        with patch.object(h, 'capability', return_value={'status': 'PASS'}), contextlib.redirect_stderr(io.StringIO()):
            results = h.run_checks(self.root, {'checks': checks}, ['second'])
        self.assertEqual([r['status'] for r in results], ['FAIL', 'BLOCKED'])
        self.assertEqual(h.combined(results), 1)

    def test_real_subprocess_produces_fresh_reports(self):
        reportpath = self.root / 'service/target/surefire-reports/TEST-DemoTest.xml'
        script = self.write('verify.py', 'from pathlib import Path\np=Path(%r)\np.parent.mkdir(parents=True,exist_ok=True)\np.write_text(%r)\n' %
                            (str(reportpath), '<testsuite name="DemoTest" tests="2" failures="0" errors="0" skipped="0"/>'))
        c = {'id': 'service.verify', 'requires': [], 'prerequisites': [], 'argv': [sys.executable, str(script)],
             'workdir': '.', 'timeout': 2, 'reports': {'directory': 'service/target/surefire-reports', 'selectors': ['DemoTest']}}
        with contextlib.redirect_stderr(io.StringIO()):
            result = h.run_checks(self.root, {'checks': [c]}, [c['id']])[0]
        self.assertEqual(result['status'], 'PASS')
        c['argv'] = [sys.executable, '-c', 'pass']
        with contextlib.redirect_stderr(io.StringIO()):
            result = h.run_checks(self.root, {'checks': [c]}, [c['id']])[0]
        self.assertEqual(result['status'], 'FAIL')

    def test_doctor_is_read_only_and_never_invokes_wrapper(self):
        observed = []
        def probe(argv, cwd, timeout, **kwargs):
            observed.append(argv)
            return {'status': 'PASS', 'diagnostic': 'openjdk version "25.0.1"'}
        before = subprocess.check_output(['git', 'status', '--porcelain'], cwd=h.ROOT)
        with patch.object(h, 'process', side_effect=probe), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(h.main(['doctor', '--tier', 'all']), 0)
        after = subprocess.check_output(['git', 'status', '--porcelain'], cwd=h.ROOT)
        self.assertEqual(before, after)
        self.assertFalse(any('mvnw' in ' '.join(a) for a in observed))
        self.assertFalse(any('compose up' in ' '.join(a) for a in observed))

    def test_unsupported_java_and_unavailable_docker_do_not_pass(self):
        for diagnostic in ['openjdk version "17.0.1"', 'unknown format']:
            with patch.object(h, 'process', return_value={'status': 'PASS', 'diagnostic': diagnostic}):
                self.assertEqual(h.capability('java', self.manifest, h.ROOT)['status'], 'BLOCKED')
        with patch.object(h, 'process', return_value={'status': 'FAIL'}):
            self.assertEqual(h.capability('docker', self.manifest, h.ROOT)['status'], 'BLOCKED')

    def test_main_rejects_unknown_module_group_and_check(self):
        for selection in [['--module', 'absent'], ['--group', 'absent'], ['--check', 'absent']]:
            with self.subTest(selection=selection), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(h.main(['check', *selection]), 2)

    def test_manifest_invalid_schema_duplicate_cycle_and_path(self):
        cases = []
        m = copy.deepcopy(self.manifest); m['schemaVersion'] = 99; cases.append(m)
        m = copy.deepcopy(self.manifest); m['checks'].append(m['checks'][0]); cases.append(m)
        m = copy.deepcopy(self.manifest); m['checks'][0]['requires'] = [m['checks'][0]['id']]; cases.append(m)
        m = copy.deepcopy(self.manifest); m['checks'][0]['workdir'] = '../outside'; cases.append(m)
        m = copy.deepcopy(self.manifest); m['modules'] = ['../outside']; cases.append(m)
        m = copy.deepcopy(self.manifest); m['checks'][0]['prerequisites'] = ['absent']; cases.append(m)
        for m in cases:
            with self.subTest(schema=m.get('schemaVersion')), patch.object(Path, 'read_text', return_value=json.dumps(m)):
                with self.assertRaises(h.Invalid):
                    h.load_manifest(h.ROOT)

    def test_real_repository_docs_and_original_required_selectors(self):
        self.assertEqual(h.docs_errors(h.ROOT, self.manifest), [])
        expected = {'McpClientSecurityIT', 'OutboxRetryServiceIT', 'OutboxRetryRepositoryIT', 'ListIssuesForBoardIT',
                    'IssueTypeMoveIT', 'IssueTypeDeletionIT', 'IssueTypeWorkflowReservationIT', 'InvitedUnblockIT',
                    'WorkflowBindingMigrationIT', 'IssueTypeCreationIT', 'WorkflowBindingChangeIT',
                    'WorkflowCreatedTypeBindingIT', 'WorkflowScopeCheckIT'}
        actual = {s for c in self.manifest['checks'] if c['id'].endswith('.it') for s in c['reports']['selectors']}
        self.assertEqual(expected, actual)
        self.assertEqual(len(self.manifest['modules']), 14)

    def test_docs_check_detects_missing_test_source_and_ci_drift(self):
        m = copy.deepcopy(self.manifest)
        next(c for c in m['checks'] if c['id'].endswith('.it'))['reports']['selectors'] = ['TypoIT']
        self.assertTrue(any('Missing test source' in e for e in h.docs_errors(h.ROOT, m)))
        original = Path.read_text
        def read(path, *args, **kwargs):
            text = original(path, *args, **kwargs)
            return text.replace('python3 scripts/harness.py check --group config', 'echo bypass') if path.name == 'pr-check.yml' else text
        with patch.object(Path, 'read_text', read):
            self.assertTrue(any('CI does not consume' in e for e in h.docs_errors(h.ROOT, self.manifest)))

    def test_globs_are_expanded_without_a_shell(self):
        self.write('checks/a.test.js', '')
        self.write('checks/b.test.js', '')
        argv = h.expand(['node', '--test', 'checks/*.test.js'], self.root, ['checks/*.test.js'])
        self.assertEqual(argv[2:], [str(self.root / 'checks/a.test.js'), str(self.root / 'checks/b.test.js')])
        with self.assertRaises(h.Invalid):
            h.expand(['checks/missing*.js'], self.root, ['checks/missing*.js'])

    def test_unittest_selector_is_a_literal_not_an_argv_glob(self):
        self.assertEqual(h.expand(['-p', 'test_*.py'], self.root), ['-p', 'test_*.py'])

    def test_clean_checkout_cli_check_all_executes_manifest_without_setup(self):
        m = self.repo()
        self.write('pom.xml', '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>' +
                   ''.join('<module>' + n + '</module>' for n in m['modules']) + '</modules></project>')
        checks = []
        for name in m['modules']:
            code = 'from pathlib import Path; p=Path(%r); p.parent.mkdir(parents=True,exist_ok=True); p.write_text("done")' % (name + '/target/checked')
            checks.append({'id': name + '.verify', 'module': name, 'group': 'java', 'tier': 'static',
                           'argv': ['{python}', '-c', code], 'workdir': '.', 'prerequisites': ['python'],
                           'timeout': 2, 'requires': [], 'writeScope': ['*/target/']})
        manifest = {'schemaVersion': 1, 'modules': m['modules'], 'checks': checks, 'impactRules': [],
                    'capabilities': {'python': {'argv': ['{python}', '--version']}}}
        self.write('scripts/harness/verification.json', json.dumps(manifest))
        self.write('scripts/harness.py', (h.ROOT / 'scripts/harness.py').read_text())
        self.git('add', '.')
        self.git('commit', '-qm', 'clean CLI fixture')
        cli = subprocess.run([sys.executable, '-B', 'scripts/harness.py', 'check', '--all'], cwd=self.root,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(cli.returncode, 0, cli.stderr.decode())
        result = json.loads(cli.stdout)
        self.assertFalse(result['dirty'])
        self.assertEqual(len(result['results']), 4)
        for name in m['modules']:
            self.assertEqual((self.root / name / 'target/checked').read_text(), 'done')

    def test_exit_precedence(self):
        for statuses, code in [(['PASS'], 0), (['PARTIAL'], 4), (['BLOCKED', 'PARTIAL'], 3), (['FAIL', 'BLOCKED'], 1)]:
            self.assertEqual(h.combined([{'status': s} for s in statuses]), code)


if __name__ == '__main__':
    unittest.main()
