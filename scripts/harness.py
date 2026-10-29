#!/usr/bin/env python3
"""Taska verification harness. Python 3.9+, standard library only."""
import argparse
import fnmatch
import glob
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = Path('scripts/harness/verification.json')
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
STATUSES = {'PASS': 0, 'FAIL': 1, 'BLOCKED': 3, 'PARTIAL': 4}


class Invalid(ValueError):
    """Invalid repository configuration or invocation."""


def inside(root, value):
    p = (root / value).resolve()
    if p != root.resolve() and root.resolve() not in p.parents:
        raise Invalid('Path escapes checkout')
    return p


def redact(text, env=None):
    env = os.environ if env is None else env
    values = [v for k, v in env.items() if len(v) >= 6 and re.search(
        r'token|secret|password|passwd|cookie|credential|api_?key|authorization', k, re.I)]
    for value in sorted(values, key=len, reverse=True):
        text = text.replace(value, '[REDACTED]')
    text = re.sub(r'(?im)((?:authorization|proxy-authorization|cookie|set-cookie)\s*[:=]\s*)[^\r\n]+', r'\1[REDACTED]', text)
    text = re.sub(r'(?i)\bBearer\s+[^\s,;]+', 'Bearer [REDACTED]', text)
    text = re.sub(r'(?i)((?:token|secret|password|passwd|api[_-]?key|client_secret)["\s]*[:=]["\s]*)[^\s,";]+', r'\1[REDACTED]', text)
    text = re.sub(r'\b(?:eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)\b', '[REDACTED]', text)
    text = re.sub(r'(https?://)[^\s/@]+:[^\s/@]+@', r'\1[REDACTED]@', text)
    return re.sub(r'(https?://[^\s?]+)\?[^\s]+', r'\1?[REDACTED]', text)


def emit(value):
    print(redact(json.dumps(value, ensure_ascii=False, indent=2)))


def process(argv, cwd, timeout, env=None, capture=False):
    """No shell; bounded diagnostic tail; kill the whole process group on timeout."""
    with tempfile.TemporaryFile() as output:
        try:
            child = subprocess.Popen(argv, cwd=str(cwd), env=env, stdout=output,
                                     stderr=subprocess.STDOUT, start_new_session=True)
        except FileNotFoundError:
            return {'status': 'BLOCKED', 'reason': 'Required executable not found'}
        except OSError:
            return {'status': 'FAIL', 'reason': 'Could not start check process'}
        try:
            code = child.wait(timeout=timeout)
            status, reason = ('PASS', '') if code == 0 else ('FAIL', 'Process exited with code %s' % code)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            try:
                os.killpg(child.pid, signal.SIGTERM)
                time.sleep(0.1)
                # The leader can exit while a descendant ignores SIGTERM.
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            status, reason = 'FAIL', 'Process timed out or was interrupted'
        size = output.tell()
        output.seek(max(0, size - 16000))
        tail = redact(output.read().decode('utf-8', errors='replace'), env)
    result = {'status': status, 'reason': reason}
    if status != 'PASS' or capture:
        result['diagnostic'] = tail
    return result


def pom_modules(root):
    return [e.text for e in ET.parse(root / 'pom.xml').findall('m:modules/m:module', NS)]


def default_test_sources(root, module):
    return [p for p in (root / module / 'src/test').rglob('*.java')
            if any(fnmatch.fnmatchcase(p.stem, pat) for pat in ('Test*', '*Test', '*Tests', '*TestCase'))]


def load_manifest(root):
    try:
        m = json.loads(inside(root, MANIFEST).read_text())
        if (not isinstance(m, dict) or type(m.get('schemaVersion')) is not int or
                m['schemaVersion'] != 1 or m['modules'] != pom_modules(root)):
            raise Invalid('Manifest schema/modules differ from root POM')
        for module in m['modules']:
            if not re.fullmatch(r'[a-z][a-z0-9-]*', module):
                raise Invalid('Invalid module name')
            inside(root, module)
        if len(set(m['modules'])) != len(m['modules']):
            raise Invalid('Duplicate modules')
        if not isinstance(m['checks'], list) or not m['checks'] or not isinstance(m['capabilities'], dict):
            raise Invalid('Invalid checks/capabilities collection')
        for probe in m['capabilities'].values():
            if (not isinstance(probe['argv'], list) or not probe['argv'] or
                    not all(isinstance(a, str) and a for a in probe['argv'])):
                raise Invalid('Invalid capability argv')
        ids = [c['id'] for c in m['checks']]
        if len(ids) != len(set(ids)):
            raise Invalid('Duplicate check ID')
        for c in m['checks']:
            if not re.fullmatch(r'[a-z][a-z0-9.-]*', c['id']):
                raise Invalid('Invalid check ID')
            if c.get('module') and c['module'] not in m['modules']:
                raise Invalid('Unknown module in check')
            if (type(c['timeout']) not in (int, float) or
                    not 0 < c['timeout'] <= 7200):
                raise Invalid('Invalid timeout')
            if (not re.fullmatch(r'[a-z][a-z0-9-]*', c['group']) or
                    c['tier'] not in ('static', 'integration') or
                    c.get('kind', 'process') not in ('process', 'docs') or
                    not isinstance(c['writeScope'], list)):
                raise Invalid('Invalid check metadata')
            if not isinstance(c['requires'], list) or not isinstance(c['prerequisites'], list):
                raise Invalid('Invalid dependency/prerequisite list')
            inside(root, c['workdir'])
            if not set(c['requires']) <= set(ids):
                raise Invalid('Unknown dependency')
            if not set(c['prerequisites']) <= set(m['capabilities']):
                raise Invalid('Unknown prerequisite')
            if c.get('kind') != 'docs' and (not isinstance(c['argv'], list) or not c['argv'] or
                                               not all(isinstance(a, str) and a for a in c['argv'])):
                raise Invalid('argv must be a nonempty string array')
            if not isinstance(c.get('globs', []), list) or not set(c.get('globs', [])) <= set(c.get('argv', [])):
                raise Invalid('Invalid explicit argument globs')
            if c.get('env', {}).keys() - {'PGADMIN_CONTAINER_TEST'}:
                raise Invalid('Unexpected environment override')
            if c.get('reports'):
                inside(root, c['reports']['directory'])
                selectors = c['reports']['selectors']
                if not isinstance(selectors, list) or not all(isinstance(s, str) for s in selectors):
                    raise Invalid('Invalid report selectors')
                if not selectors and not c['reports'].get('noTestsReason'):
                    raise Invalid('No-test module requires an explicit reason')
                if any(not re.fullmatch(r'[A-Za-z0-9_.*]+', s) for s in selectors):
                    raise Invalid('Invalid report selector')
        for rule in m['impactRules']:
            if not set(rule.get('modules', [])) <= set(m['modules']):
                raise Invalid('Unknown impact module')
            if not set(rule.get('groups', [])) <= {c['group'] for c in m['checks']}:
                raise Invalid('Unknown impact group')
        for module in m['modules']:
            if module + '.verify' not in ids:
                raise Invalid('Module has no verify check')
        ordered_checks(m, ids)  # validates the DAG
        return m
    except (KeyError, TypeError, AttributeError, IndexError, json.JSONDecodeError, ET.ParseError, OSError) as exc:
        raise Invalid('Malformed manifest/POM: %s' % type(exc).__name__) from exc


def ordered_checks(m, selected):
    checks = {c['id']: c for c in m['checks']}
    result, visiting, done = [], set(), set()

    def add(name):
        if name not in checks:
            raise Invalid('Unknown check: %s' % name)
        if name in visiting:
            raise Invalid('Check dependency cycle')
        if name in done:
            return
        visiting.add(name)
        for dependency in checks[name]['requires']:
            add(dependency)
        visiting.remove(name)
        done.add(name)
        result.append(checks[name])
    for name in selected:
        add(name)
    return result


def expand(argv, root, patterns=()):
    result = []
    for arg in argv:
        arg = arg.replace('{python}', sys.executable).replace('{root}', str(root))
        if arg in patterns:
            matches = sorted(glob.glob(str(inside(root, arg))))
            if not matches:
                raise Invalid('Command glob has no matches')
            result.extend(matches)
        else:
            result.append(arg)
    return result


def capability(name, m, root):
    result = process(expand(m['capabilities'][name]['argv'], root), root, 15, capture=True)
    if name == 'java' and result['status'] == 'PASS':
        match = re.search(r'(?:version\s+"|openjdk\s+)([0-9]+)', result.get('diagnostic', ''))
        release = int(ET.parse(root / 'pom.xml').find('m:properties/m:java.version', NS).text)
        if not match or int(match.group(1)) < release:
            result['status'] = 'BLOCKED'
    return {'capability': name, 'status': 'PASS' if result['status'] == 'PASS' else 'BLOCKED',
            'reason': '' if result['status'] == 'PASS' else 'Prerequisite unavailable: ' + name}


def combined(results):
    codes = [STATUSES[r['status']] for r in results]
    return next((c for c in (1, 3, 4) if c in codes), 0)


def fingerprint(path):
    s = path.stat()
    return (s.st_mtime_ns, s.st_ino, hashlib.sha256(path.read_bytes()).hexdigest())


def snapshots(root, reports):
    directory = inside(root, reports['directory'])
    return {str(p): fingerprint(p) for p in directory.glob('TEST-*.xml')}


def test_reports(root, reports, before):
    directory = inside(root, reports['directory'])
    selectors = reports['selectors']
    found = {s: [] for s in selectors}
    try:
        for p in directory.glob('TEST-*.xml'):
            if before.get(str(p)) == fingerprint(p):
                continue
            suite = ET.parse(p).getroot()
            # Surefire emits one testsuite per file; only fresh reports count.
            name = suite.attrib.get('name', p.stem[5:]).split('.')[-1]
            for selector in selectors:
                if fnmatch.fnmatchcase(name, selector):
                    values = {k: int(suite.attrib.get(k, '0')) for k in ('tests', 'failures', 'errors', 'skipped')}
                    if (any(n < 0 for n in values.values()) or
                            values['skipped'] + values['failures'] + values['errors'] > values['tests']):
                        raise ValueError('Negative test count')
                    found[selector].append(values)
    except (ET.ParseError, ValueError, OSError):
        return {'status': 'FAIL', 'reason': 'Invalid test report'}
    counts = {s: {k: sum(v[k] for v in values) for k in ('tests', 'failures', 'errors', 'skipped')}
              for s, values in found.items()}
    if any(v['tests'] == 0 for v in counts.values()):
        return {'status': 'FAIL', 'reason': 'Required selector has no fresh tests', 'testCounts': counts}
    if any(v['failures'] or v['errors'] for v in counts.values()):
        return {'status': 'FAIL', 'reason': 'Test report contains failures', 'testCounts': counts}
    if any(v['skipped'] for v in counts.values()):
        return {'status': 'PARTIAL', 'reason': 'Selected tests include skipped cases', 'testCounts': counts}
    result = {'status': 'PASS', 'reason': '', 'testCounts': counts}
    if not selectors:
        result['testScope'] = reports['noTestsReason']
    return result


def docs_errors(root, m):
    errors = []
    paths = [root / 'AGENTS.md', root / 'README.md', *sorted((root / 'docs/agents').glob('*.md'))]
    for p in paths:
        if not p.is_file():
            errors.append('Missing documentation: ' + str(p.relative_to(root)))
            continue
        for link in re.findall(r'\]\(([^)]+)\)', p.read_text()):
            if re.match(r'[A-Za-z][A-Za-z0-9+.-]*:', link) or link.startswith('#'):
                continue
            target = unquote(link.split('#')[0]).strip('<>')
            if not (p.parent / target).exists():
                errors.append('Broken link in ' + str(p.relative_to(root)) + ': ' + target)
    for c in m['checks']:
        reports = c.get('reports')
        if not reports:
            continue
        mod = c['module']
        selectors = reports['selectors']
        if not selectors and default_test_sources(root, mod):
            errors.append('Default test sources added to no-test module: ' + mod)
        for selector in selectors:
            if selector == '*':
                if not default_test_sources(root, mod):
                    errors.append('No default tests for ' + mod)
            elif not list((root / mod / 'src/test').rglob(selector + '.java')):
                errors.append('Missing test source: ' + mod + '/' + selector)
        if c['id'].endswith('.it'):
            wanted = '-Dtest=' + ','.join(selectors)
            if wanted not in c['argv']:
                errors.append('IT argv/report selectors differ: ' + c['id'])
    ci = (root / '.github/workflows/pr-check.yml').read_text()
    for binding in ('python3 scripts/harness.py check --module ${{ matrix.mod.module }}',
                    'python3 scripts/harness.py check --group config',
                    'python3 scripts/harness.py check --group harness',
                    "['modules']"):
        if binding not in ci:
            errors.append('CI does not consume manifest: ' + binding)
    # The human-readable IT table remains checked against the executable manifest.
    text = (root / 'docs/agents/verification.md').read_text()
    documented = set(re.findall(r'\b[A-Z][A-Za-z]+IT\b', text))
    actual = {s for c in m['checks'] if c['id'].endswith('.it') for s in c['reports']['selectors']}
    if actual != documented:
        errors.append('Documented IT selectors differ from manifest')
    return errors


def run_checks(root, m, selected):
    results, checked = [], {}
    for c in ordered_checks(m, selected):
        name = c['id']
        if any(r['status'] != 'PASS' for r in results if r['checkId'] in c['requires']):
            result = {'status': 'BLOCKED', 'reason': 'Required check did not pass'}
        elif c.get('kind') == 'docs':
            errors = docs_errors(root, m)
            result = {'status': 'FAIL' if errors else 'PASS', 'reason': '; '.join(errors)}
        else:
            for prerequisite in c['prerequisites']:
                if prerequisite not in checked:
                    checked[prerequisite] = capability(prerequisite, m, root)
            missing = [p for p in c['prerequisites'] if checked[p]['status'] != 'PASS']
            if missing:
                result = {'status': 'BLOCKED', 'reason': 'Missing prerequisites: ' + ', '.join(missing)}
            else:
                reports = c.get('reports')
                before = snapshots(root, reports) if reports else {}
                env = dict(os.environ, **c.get('env', {}))
                result = process(expand(c['argv'], root, c.get('globs', [])), inside(root, c['workdir']), c['timeout'], env)
                if result['status'] == 'PASS' and reports:
                    result.update(test_reports(root, reports, before))
        result['checkId'] = name
        results.append(result)
        # Progress contains IDs/status only; command output is sanitized in final JSON.
        print(name + ': ' + result['status'], file=sys.stderr, flush=True)
    return results


def git(root, *args):
    p = subprocess.run(['git', *args], cwd=str(root), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode:
        raise Invalid('Git command failed; verify checkout and base ref')
    return p.stdout.decode('utf-8', errors='replace')


def plan(root, m, base):
    if not re.fullmatch(r'[A-Za-z0-9_./@~^+-]{1,200}', base) or base.startswith('-'):
        raise Invalid('Invalid base ref')
    sha = git(root, 'rev-parse', '--verify', '--end-of-options', base + '^{commit}').strip()
    ancestor = git(root, 'merge-base', sha, 'HEAD').strip()
    paths = set(git(root, 'diff', '--name-only', '-z', '--no-renames', ancestor, '--').split('\0'))
    paths.update(git(root, 'ls-files', '--others', '--exclude-standard', '-z').split('\0'))
    paths.discard('')
    direct, groups, reasons = set(), {'docs'}, []
    for path in sorted(paths):
        module = path.split('/')[0]
        if path.endswith('.md'):
            pass
        elif module in m['modules']:
            direct.add(module)
            reasons.append({'path': path, 'modules': [module], 'reason': 'Module changed'})
        elif path.startswith(('infra/', 'scripts/harness', '.github/codex/', 'docker-compose', '.env.')) or path == '.github/workflows/pr-check.yml':
            pass  # explicit impact rules below
        else:
            direct.update(m['modules'])
            groups.update(('harness', 'config'))
            reasons.append({'path': path, 'reason': 'Unclassified/shared change: conservative full verification'})
        for rule in m['impactRules']:
            if any(path.startswith(prefix) for prefix in rule['prefixes']):
                direct.update(rule.get('modules', []))
                groups.update(rule.get('groups', []))
                reasons.append({'path': path, 'reason': rule['reason']})
    # Expand upstream and downstream from actual reactor POM dependencies.
    artifacts = {}
    dependencies = {}
    for module in m['modules']:
        pom = ET.parse(root / module / 'pom.xml')
        artifacts[pom.find('m:artifactId', NS).text] = module
        dependencies[module] = {e.text for e in pom.findall('m:dependencies/m:dependency/m:artifactId', NS)}
    reactor_deps = {module: {artifacts[d] for d in deps if d in artifacts}
                    for module, deps in dependencies.items()}
    affected = set(direct)
    # Only changed/downstream modules propagate impact. Build-only upstream
    # dependencies must not turn every leaf edit into a full monorepo plan.
    while True:
        downstream = {module for module, deps in reactor_deps.items() if deps & affected}
        if downstream <= affected:
            break
        affected.update(downstream)
    while True:
        upstream = {dep for module in affected for dep in reactor_deps[module]}
        if upstream <= affected:
            break
        affected.update(upstream)
    selected = [c['id'] for c in m['checks'] if c.get('module') in affected or c['group'] in groups]
    return {'baseCommit': sha, 'mergeBase': ancestor, 'sourceHead': git(root, 'rev-parse', 'HEAD').strip(),
            'dirty': bool(git(root, 'status', '--porcelain')), 'paths': sorted(paths), 'modules': sorted(affected), 'checks': [c['id'] for c in ordered_checks(m, selected)],
            'reasons': reasons, 'dependencyExpansion': sorted(affected - direct),
            'limitations': ['Changed modules propagate to downstream; build-only upstream does not propagate sideways.',
                            'Review semantic/event consumers and E2E requirements manually; plan does not execute checks.']}


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    doctor = sub.add_parser('doctor', help='Read-only capability probe; does not run Maven wrapper')
    doctor.add_argument('--tier', choices=['static', 'java', 'config', 'all'], default='all')
    planner = sub.add_parser('plan', help='Conservative impact plan, including uncommitted/untracked files')
    planner.add_argument('--base', required=True)
    check = sub.add_parser('check', help='Execute selected checks plus dependencies')
    selection = check.add_mutually_exclusive_group(required=True)
    selection.add_argument('--module')
    selection.add_argument('--group')
    selection.add_argument('--check', action='append')
    selection.add_argument('--all', action='store_true')
    sub.add_parser('docs-check', help='Read-only docs, manifest and CI consistency checks')
    args = parser.parse_args(argv)
    try:
        m = load_manifest(root)
        if args.command == 'doctor':
            names = {'static': ['python', 'git'], 'java': ['python', 'git', 'java', 'docker'],
                     'config': ['python', 'node', 'docker', 'compose'], 'all': list(m['capabilities'])}[args.tier]
            results = [capability(n, m, root) for n in names]
            wrapper = (root / 'mvnw').is_file()
            results.append({'capability': 'maven-wrapper', 'status': 'PASS' if wrapper else 'BLOCKED',
                            'reason': 'Wrapper is not executed by doctor'})
            emit({'results': results, 'javaRelease': ET.parse(root / 'pom.xml').find('m:properties/m:java.version', NS).text,
                  'ciJdk': re.search(r'java-version:\s*[\"\']?([0-9]+)', (root / '.github/workflows/pr-check.yml').read_text()).group(1), 'note': 'Executable probes only; no fixture/database writes or wrapper downloads.'})
            return combined(results)
        if args.command == 'plan':
            emit(plan(root, m, args.base))
            return 0
        if args.command == 'docs-check':
            selected = ['docs']
        elif args.module:
            if args.module not in m['modules']:
                raise Invalid('Unknown module')
            selected = [c['id'] for c in m['checks'] if c.get('module') == args.module]
        elif args.group:
            selected = [c['id'] for c in m['checks'] if c['group'] == args.group]
            if not selected:
                raise Invalid('Unknown check group')
        elif args.check:
            selected = args.check
        else:
            selected = [c['id'] for c in m['checks']]
        results = run_checks(root, m, selected)
        emit({'sourceHead': git(root, 'rev-parse', 'HEAD').strip(),
              'dirty': bool(git(root, 'status', '--porcelain')), 'results': results})
        return combined(results)
    except Invalid as exc:
        emit({'status': 'FAIL', 'reason': str(exc)})
        return 2
    except (OSError, ET.ParseError) as exc:
        emit({'status': 'FAIL', 'reason': 'Repository operation failed: ' + type(exc).__name__})
        return 1


if __name__ == '__main__':
    sys.exit(main())
