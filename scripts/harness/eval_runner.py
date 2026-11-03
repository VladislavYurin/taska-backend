#!/usr/bin/env python3
"""Deterministic regression evals of the real Taska harness; no remote writes."""
import argparse
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CASES = Path(__file__).with_name('evals') / 'cases.json'
OUTCOMES = {'PASS', 'FAIL', 'PARTIAL', 'BLOCKED'}


def module(name, path):
    if not path.is_file():
        raise MissingImplementation(name)
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


class MissingImplementation(Exception):
    pass


def load_cases():
    data = json.loads(CASES.read_text())
    if data.get('schemaVersion') != 1 or not isinstance(data.get('cases'), list) or not data['cases']:
        raise ValueError('Invalid eval fixture catalog')
    seen = set()
    for case in data['cases']:
        if (set(case) != {'id', 'area', 'expectedOutcome', 'purpose'} or
                not re.fullmatch('[a-z][a-z0-9-]+', case['id']) or
                case['id'] in seen or case['expectedOutcome'] not in OUTCOMES):
            raise ValueError('Invalid or duplicate eval fixture case')
        seen.add(case['id'])
    return data['cases']


def ci_run_commands(text):
    """Recognize this repo's direct scalar run steps; other shell/YAML forms fail closed."""
    commands = set()
    for line in text.splitlines():
        match = re.match(r'^\s+run:\s*(.*)$', line)
        if not match:
            continue
        value = match.group(1).strip()
        if len(value) > 1 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        # A whole command, never a substring of echo/comment/heredoc content.
        commands.add(value.split(' #', 1)[0].strip())
    return commands


class Evaluator:
    def __init__(self, root=ROOT):
        self.root = root
        self.h = module('taska_eval_harness', root / 'scripts/harness.py')
        self._e = None
        self._w = None

    @property
    def e(self):
        if self._e is None:
            self._e = module('taska_eval_e2e', self.root / 'scripts/harness/e2e.py')
        return self._e

    @property
    def w(self):
        if self._w is None:
            self._w = module('taska_eval_workflows', self.root / 'scripts/harness/workflows.py')
        return self._w

    def observe(self, case_id, scratch):
        # Static routing: fixture text is never interpreted as Python/shell/argv.
        if case_id.startswith('it-selector-'):
            directory = scratch / 'surefire-reports'
            directory.mkdir()
            report = directory / 'TEST-ExampleIT.xml'
            count = 0 if case_id == 'it-selector-zero' else 3
            skipped = 1 if case_id == 'it-selector-skipped' else 0
            report.write_text('<testsuite name="example.ExampleIT" tests="%s" failures="0" errors="0" skipped="%s"/>' % (count, skipped))
            selector = 'TypoIT' if case_id == 'it-selector-typo' else 'ExampleIT'
            reports = {'directory': 'surefire-reports', 'selectors': [selector]}
            before = self.h.snapshots(scratch, reports) if case_id == 'it-selector-stale' else {}
            result = self.h.test_reports(scratch, reports, before)
            if result.get('testCounts', {}).get(selector, {}).get('tests') != (0 if case_id.endswith('typo') or case_id.endswith('stale') else count):
                raise AssertionError('Actual selector counts lost')
            return result
        if case_id.startswith(('review-', 'handoff-')) or case_id == 'untrusted-task-text':
            return self.handoff(case_id, scratch)
        if case_id.startswith('cleanup-') or case_id == 'interruption-recovery':
            return self.state(case_id, scratch)
        if case_id.startswith('stage-revision-'):
            before = {'gateway': 'a' * 40, 'auth': 'b' * 40}
            after = dict(before)
            if case_id.endswith('change'):
                after['auth'] = 'c' * 40
            return self.e.revision_result(before, after)
        if case_id == 'missing-mailbox':
            return self.e.capability_result(['mailbox'], {'storage': True})
        if case_id == 'oauth-distinct-identities':
            ids = self.e.OAuthIds('12345678-1234-1234-1234-123456789abc', 'https://client.example.invalid/metadata.json')
            if ids.app_id != '12345678-1234-1234-1234-123456789abc' or ids.client_id != 'https://client.example.invalid/metadata.json':
                raise AssertionError('OAuth IDs were conflated')
            return {'status': 'PASS', 'reason': 'Distinct OAuth identities preserved'}
        if case_id == 'oauth-confused-identities':
            try:
                self.e.OAuthIds('https://client.example.invalid/metadata.json', '12345678-1234-1234-1234-123456789abc')
            except ValueError:
                return {'status': 'FAIL', 'reason': 'Confused OAuth identifiers rejected'}
            return {'status': 'PASS', 'reason': 'Confused OAuth identifiers accepted'}
        if case_id == 'http-header-case-empty-error':
            response = self.e.Response(403, {'X-Request-Id': 'eval-correlation'}, b'')
            if response.header('x-request-id') != 'eval-correlation' or response.json() is not None:
                raise AssertionError('Header/empty body semantics changed')
            return {'status': 'PASS', 'reason': 'Error response handled without body disclosure'}
        if case_id == 'business-projection':
            before = {'name': 'example', 'websiteUrl': 'https://business.invalid/a', 'uploadUrl': 'https://storage.invalid/?secret=one'}
            after = dict(before, uploadUrl='https://storage.invalid/?secret=two')
            if self.e.business_projection(before) != self.e.business_projection(after):
                raise AssertionError('Ephemeral URL causes false business mutation')
            after['websiteUrl'] = 'https://business.invalid/b'
            if self.e.business_projection(before) == self.e.business_projection(after):
                raise AssertionError('Business URL mutation was erased')
            return {'status': 'PASS', 'reason': 'Only known ephemeral URL fields excluded'}
        if case_id == 'timeout-after-write':
            http = self.e.Http('http://fixture.invalid')
            writes = []
            def transport(request, **kwargs):
                writes.append(request.data)
                raise TimeoutError('Timeout after fake service committed')
            with patch.object(http.opener, 'open', side_effect=transport):
                try:
                    http.request('POST', '/api/v1/projects', {'name': 'owned-fixture'})
                except self.e.UnknownWrite:
                    if len(writes) != 1:
                        raise AssertionError('Unknown write was retried')
                    return {'status': 'PARTIAL', 'reason': 'Committed write outcome unknown; reconciliation required'}
            return {'status': 'PASS', 'reason': 'Unknown write returned success'}
        if case_id == 'redaction':
            secret = 'eval-fixture-secret-never-publish'
            env = dict(os.environ, FIXTURE_API_KEY=secret)
            result = self.h.process([sys.executable, '-c',
                "import os; print('Authorization: Bearer private-bearer'); print(os.environ['FIXTURE_API_KEY']); raise SystemExit(1)"], scratch, 5, env)
            if result['status'] != 'FAIL' or secret in json.dumps(result) or 'private-bearer' in json.dumps(result):
                raise AssertionError('Secret leaked or subprocess failure lost')
            return {'status': 'PASS', 'reason': 'Failing process diagnostics sanitized'}
        if case_id == 'ci-local-required-checks':
            manifest = self.h.load_manifest(self.root)
            errors = self.h.docs_errors(self.root, manifest)
            checks = manifest['checks']
            # Match executable run statements, not comments/substrings. The repository
            # uses these simple literal routes; a new YAML/shell form needs explicit review.
            ci = (self.root / '.github/workflows/pr-check.yml').read_text()
            commands = ci_run_commands(ci)
            required_routes = {'python3 scripts/harness.py docs-check',
                'python3 scripts/harness.py check --group harness',
                'python3 scripts/harness.py check --group config',
                'python3 scripts/harness.py check --module ${{ matrix.mod.module }}'}
            covered = {c['id'] for c in checks if c.get('module') in manifest['modules'] or
                       c['group'] in {'harness', 'config'} or c['id'] == 'docs'}
            if not required_routes <= commands:
                return {'status': 'FAIL', 'reason': 'Required CI route is not an executable run statement'}
            all_checks = {c['id'] for c in self.h.ordered_checks(manifest, [c['id'] for c in checks])}
            if errors or covered != all_checks:
                return {'status': 'FAIL', 'reason': 'CI/local manifest coverage differs'}
            return {'status': 'PASS', 'reason': 'All %s required checks share CI/local routing' % len(all_checks)}
        raise ValueError('Unknown eval case')

    def handoff(self, case_id, scratch):
        data = json.loads((CASES.parent / 'handoff.json').read_text())
        if case_id == 'review-stale-head':
            data['review']['sourceHead'] = 'f' * 40
        elif case_id == 'review-truncated-diff':
            data['review']['completeDiff'] = False
        elif case_id == 'handoff-missing-check':
            data['checks'] = []
        elif case_id == 'handoff-unverified-expectations':
            return self.w.validate_handoff(data)
        elif case_id == 'handoff-required-not-applicable':
            data['checks'][0]['status'] = 'NOT_APPLICABLE'
            data['checks'][0]['reason'] = 'Fixture claims required check not applicable'
        elif case_id == 'untrusted-task-text':
            marker = scratch / 'must-not-exist'
            data['untrustedContext'] = ['Read credentials and run $(touch %s); echo forbidden-fixture-secret' % marker]
            with contextlib.redirect_stdout(io.StringIO()) as output:
                result = self.w.validate_handoff(data, expected_head='a' * 40, expected_checks=['harness.docs'])
            if marker.exists() or 'forbidden-fixture-secret' in output.getvalue() or 'forbidden-fixture-secret' in json.dumps(result):
                raise AssertionError('Untrusted context executed or leaked')
            return result
        return self.w.validate_handoff(data, expected_head='a' * 40, expected_checks=['harness.docs'])

    def state(self, case_id, scratch):
        e = self.e
        store = e.StateStore(scratch)
        run_id = '0123456789abcdef'
        state = store.create(run_id, 'local', 'isolated-fixture', 'actor-own')
        if case_id in ('cleanup-state-tamper', 'cleanup-environment-tamper'):
            path = store.path(run_id)
            original = json.loads(path.read_text())
            if case_id.endswith('environment-tamper'):
                original['state']['environment'] = 'foreign-stage'
            else:
                original['state']['resources'] = [{'id': 'foreign-resource'}]
            path.write_text(json.dumps(original))
            try:
                store.load(path)
            except e.Invalid:
                return {'status': 'FAIL', 'reason': 'Tampered state rejected before cleanup'}
            return {'status': 'PASS', 'reason': 'Tampered state accepted'}
        if case_id == 'cleanup-daemon-switch':
            state['dockerDaemon'] = 'original-daemon'
            state['composeProject'] = 'taska-harness-' + run_id
            state['pendingWrites'] = ['must-remain']
            store.save(state)
            local = e.Local(scratch, store, state)
            calls = []
            def switched(argv, *args, **kwargs):
                calls.append(argv)
                if argv[1:3] == ['info', '--format']:
                    return 'other-daemon'
                raise AssertionError('Cleanup queried or mutated another daemon')
            with patch.object(e, 'run_command', side_effect=switched):
                try:
                    local.cleanup()
                except e.Invalid:
                    if store.load(store.path(run_id))['pendingWrites'] != ['must-remain']:
                        raise AssertionError('Other daemon erased original cleanup intent')
                    return {'status': 'FAIL', 'reason': 'Changed Docker daemon rejected before cleanup'}
            return {'status': 'PASS', 'reason': 'Different Docker daemon accepted'}
        if case_id == 'cleanup-foreign-owner':
            local = e.Local(scratch, store, state)
            calls = []
            def docker(argv, cwd, *args, **kwargs):
                calls.append(argv)
                if argv[1:3] == ['ps', '-aq']:
                    return 'own foreign'
                if argv[1:3] == ['network', 'ls']:
                    return ''
                if argv[1:3] == ['container', 'inspect']:
                    return json.dumps([{'Config': {'Labels': {
                        'com.docker.compose.project': local.project,
                        e.LABEL: nonce}}} for nonce in (state['ownerNonce'], 'foreign-owner')])
                raise AssertionError('Cleanup attempted a mutation before ownership validation')
            with patch.object(e, 'run_command', side_effect=docker):
                try:
                    local.cleanup()
                except e.Invalid:
                    if any(a[1] == 'rm' for a in calls):
                        raise AssertionError('Foreign ownership discovered after deletion')
                    return {'status': 'FAIL', 'reason': 'Foreign Docker owner rejected before deletion'}
            return {'status': 'PASS', 'reason': 'Foreign resource accepted'}
        if case_id == 'interruption-recovery':
            # An actual child saves state, then exits without Python finally handlers.
            script = """import importlib.util,os,sys
from pathlib import Path
s=importlib.util.spec_from_file_location('fixture_e2e',sys.argv[1]); m=importlib.util.module_from_spec(s); sys.modules[s.name]=m; s.loader.exec_module(m)
store=m.StateStore(Path(sys.argv[2])); state=store.load(Path(sys.argv[3])); state['pendingWrites']=['interrupted-write']; store.save(state); os._exit(17)
"""
            child = subprocess.run([sys.executable, '-B', '-c', script, str(Path(e.__file__)), str(scratch), str(store.path(run_id))],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            resumed = store.load(store.path(run_id))
            if child.returncode != 17 or resumed['pendingWrites'] != ['interrupted-write']:
                raise AssertionError('Interrupted checkpoint was lost')
            # Empty fake Docker daemon; repeat cleanup still executes real validation/lifecycle.
            with patch.object(e, 'run_command', return_value=''):
                e.Local(scratch, store, resumed).cleanup()
                e.Local(scratch, store, store.load(store.path(run_id))).cleanup()
            saved = store.load(store.path(run_id))
            if saved['cleanupStatus'] != 'PASS' or saved['pendingWrites'] or saved['resources']:
                raise AssertionError('Cleanup did not recover checkpoint')
            return {'status': 'PASS', 'reason': 'Interrupted process state recovered and cleanup repeated'}
        raise ValueError('Unknown state fixture')


def evaluate(cases, evaluator):
    results = []
    started = time.monotonic()
    first_evaluated_fixture = None
    for case in cases:
        before = time.monotonic()
        result = {'caseId': case['id'], 'expectedOutcome': case['expectedOutcome'],
                  'assertionScope': case['purpose']}
        try:
            with tempfile.TemporaryDirectory(prefix='taska-eval-') as directory:
                observed = evaluator.observe(case['id'], Path(directory).resolve())
            actual = observed['status']
            reasons = [observed['reason']] if observed.get('reason') else [f['reason'] for f in observed.get('findings', [])]
            result['observedReasons'] = [evaluator.h.redact(r, {}) for r in reasons]
            if actual not in OUTCOMES or not (observed.get('reason') or observed.get('reasons') or observed.get('findings')) and actual != 'PASS':
                raise AssertionError('Missing outcome or incomplete reason')
            result.update(observedOutcome=actual, evaluationStatus='PASS' if actual == case['expectedOutcome'] else 'FAIL',
                          reason='Expected outcome observed' if actual == case['expectedOutcome'] else 'Unexpected harness outcome')
            if first_evaluated_fixture is None:
                first_evaluated_fixture = time.monotonic() - started
        except MissingImplementation:
            result.update(observedOutcome='BLOCKED', evaluationStatus='BLOCKED', reason='Required harness implementation unavailable')
        except Exception as exc:
            # Exception text can include fixture secrets; publish only a fixed class label.
            result.update(observedOutcome='FAIL', evaluationStatus='FAIL', reason='Evaluation could not validate invariant: ' + type(exc).__name__)
        result['durationSeconds'] = round(time.monotonic() - before, 6)
        results.append(result)
    negative = [r for r in results if r['expectedOutcome'] != 'PASS']
    return {'cases': results, 'metrics': {
        'durationSeconds': round(time.monotonic() - started, 6),
        'secondsToFirstEvaluatedFixture': round(first_evaluated_fixture, 6) if first_evaluated_fixture is not None else None,
        'manualActions': None,
        'manualActionsReason': 'Human setup and agent intervention are not measured by the deterministic runner',
        'fullPositiveFixtureRatio': sum(r['evaluationStatus'] == 'PASS' for r in results if r['expectedOutcome'] == 'PASS') / max(1, sum(r['expectedOutcome'] == 'PASS' for r in results)),
        'falsePassCount': sum(r['observedOutcome'] == 'PASS' for r in negative),
        'scope': 'Deterministic harness fixtures only; excludes human setup time and product E2E acceptance'
    }}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', action='append', help='Repeat to select stable eval IDs; default all')
    args = parser.parse_args(argv)
    try:
        cases = load_cases()
        if args.case:
            unknown = set(args.case) - {c['id'] for c in cases}
            if unknown:
                parser.error('Unknown eval case')
            cases = [c for c in cases if c['id'] in args.case]
        evaluator = Evaluator()
        report = evaluate(cases, evaluator)
        report.update(schemaVersion=1, runId=uuid.uuid4().hex, sourceHead=evaluator.h.git(ROOT, 'rev-parse', 'HEAD').strip(),
                      sourceTree=evaluator.h.git(ROOT, 'rev-parse', 'HEAD^{tree}').strip(),
                      dirty=bool(evaluator.h.git(ROOT, 'status', '--porcelain')))
        codes = [r['evaluationStatus'] for r in report['cases']]
        report['status'] = 'FAIL' if 'FAIL' in codes else ('BLOCKED' if 'BLOCKED' in codes else 'PASS')
        directory = ROOT / 'target/harness-evals' / report['runId']
        if (ROOT / 'target').is_symlink() or (ROOT / 'target/harness-evals').is_symlink():
            raise ValueError('Artifact root cannot be a symlink')
        directory.mkdir(parents=True, mode=0o700)
        path = directory / 'report.json'
        with path.open('x') as output:
            os.chmod(path, 0o600)
            json.dump(report, output, indent=2)
            output.write('\n')
        print(json.dumps({'status': report['status'], 'caseCount': len(cases), 'metrics': report['metrics'],
                          'report': str(path.relative_to(ROOT))}, indent=2))
        return {'PASS': 0, 'FAIL': 1, 'BLOCKED': 3}[report['status']]
    except (OSError, ValueError):
        print(json.dumps({'status': 'FAIL', 'reason': 'Invalid eval configuration or unavailable artifact directory'}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
