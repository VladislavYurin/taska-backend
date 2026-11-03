#!/usr/bin/env python3
"""Offline workflow handoff checks; never execute commands from evidence."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SKILLS = ('task-intake', 'change-verification', 'e2e-regression',
          'pr-review-followup', 'docs-sync')
CODES = {'PASS': 0, 'FAIL': 1, 'BLOCKED': 3, 'PARTIAL': 4}
SHA = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')


def validate_handoff(data, expected_head=None, expected_checks=None):
    """Check consistency against caller-verified head/check IDs, not authenticity.

    Text fields (including untrustedContext) are inert and never echoed. Real PR,
    test reports and resources must still be inspected by the receiving agent.
    """
    findings = []

    def add(status, reason):
        findings.append({'status': status, 'reason': reason})

    try:
        required = {'schemaVersion', 'task', 'scope', 'branch', 'baseHead',
                    'sourceHead', 'sourceTree', 'dirty', 'contracts', 'checks',
                    'requiredChecks', 'review', 'limitations', 'resources'}
        if not isinstance(data, dict) or not required <= data.keys() or data.keys() - required - {'untrustedContext'}:
            raise ValueError()
        if type(data['schemaVersion']) is not int or data['schemaVersion'] != 1:
            raise ValueError()
        for field in ('task', 'scope', 'branch'):
            if not isinstance(data[field], str) or not data[field].strip():
                raise ValueError()
        for field in ('baseHead', 'sourceHead', 'sourceTree'):
            if not isinstance(data[field], str) or not SHA.fullmatch(data[field]):
                raise ValueError()
        if type(data['dirty']) is not bool:
            raise ValueError()
        for field in ('contracts', 'limitations', 'untrustedContext'):
            if not isinstance(data.get(field, []), list) or not all(isinstance(v, str) for v in data.get(field, [])):
                raise ValueError()
        required_checks = data['requiredChecks']
        if (not isinstance(required_checks, list) or not required_checks or
                any(not isinstance(v, str) or not IDENTIFIER.fullmatch(v) for v in required_checks) or
                len(required_checks) != len(set(required_checks))):
            raise ValueError()
        checks = data['checks']
        if not isinstance(checks, list):
            raise ValueError()
        seen = set()
        for check in checks:
            if not isinstance(check, dict) or set(check) != {'id', 'status', 'sourceHead', 'scope', 'reason'}:
                raise ValueError()
            if (not isinstance(check['id'], str) or not IDENTIFIER.fullmatch(check['id']) or
                    check['id'] in seen or check['status'] not in (*CODES, 'NOT_APPLICABLE') or
                    not isinstance(check['sourceHead'], str) or not SHA.fullmatch(check['sourceHead']) or
                    not isinstance(check['scope'], str) or not check['scope'].strip() or
                    not isinstance(check['reason'], str)):
                raise ValueError()
            seen.add(check['id'])
            if check['status'] != 'PASS' and not check['reason'].strip():
                raise ValueError()
        review = data['review']
        if (not isinstance(review, dict) or set(review) != {'sourceHead', 'completeDiff', 'unresolvedBlocking'} or
                not isinstance(review['sourceHead'], str) or not SHA.fullmatch(review['sourceHead']) or
                type(review['completeDiff']) is not bool or type(review['unresolvedBlocking']) is not int or
                review['unresolvedBlocking'] < 0):
            raise ValueError()
        if not isinstance(data['resources'], list):
            raise ValueError()
        for resource in data['resources']:
            if not isinstance(resource, dict) or set(resource) != {'kind', 'id', 'cleanupAction'} or not all(
                    isinstance(v, str) and v.strip() for v in resource.values()):
                raise ValueError()
        if expected_head is None or expected_checks is None:
            add('PARTIAL', 'Caller has not independently supplied head and required checks')
        if expected_head is not None:
            if not isinstance(expected_head, str) or not SHA.fullmatch(expected_head):
                raise ValueError()
            if data['sourceHead'] != expected_head:
                add('PARTIAL', 'Handoff source head is stale')
        if expected_checks is not None:
            if (not isinstance(expected_checks, (list, tuple, set)) or not expected_checks or
                    any(not isinstance(v, str) or not IDENTIFIER.fullmatch(v) for v in expected_checks)):
                raise ValueError()
            if set(required_checks) != set(expected_checks):
                add('PARTIAL', 'Required check scope differs from independently selected checks')
        if data['dirty']:
            add('PARTIAL', 'Working tree contains uncommitted changes')
        if review['sourceHead'] != data['sourceHead']:
            add('PARTIAL', 'Review head is stale')
        if not review['completeDiff']:
            add('PARTIAL', 'Review diff is partial or truncated')
        if review['unresolvedBlocking']:
            add('FAIL', 'Blocking review findings remain unresolved')
        selected = set(required_checks) | set(expected_checks or [])
        if selected - seen:
            add('PARTIAL', 'Required check evidence is missing')
        for check in checks:
            if check['id'] not in selected:
                continue
            if check['sourceHead'] != data['sourceHead']:
                add('PARTIAL', 'Required check evidence belongs to another head')
            if check['status'] != 'PASS':
                add(check['status'] if check['status'] in CODES else 'PARTIAL',
                    'A required check has not passed')
        if data['limitations']:
            add('PARTIAL', 'Acceptance limitations remain')
        if data['resources']:
            add('PARTIAL', 'Owned resources remain; cleanup needs verification')
    except (ValueError, TypeError, KeyError):
        add('FAIL', 'Invalid handoff schema or trusted comparison input')
    status = next((s for s in ('FAIL', 'BLOCKED', 'PARTIAL') if any(f['status'] == s for f in findings)), 'PASS')
    return {'schemaVersion': 1, 'status': status, 'findings': findings,
            'scope': 'Offline consistency only; independently verify evidence and permissions'}


def smoke(root=ROOT):
    """Repository/command smoke, deliberately separate from live client discovery."""
    errors = []
    for name in SKILLS:
        path = root / '.agents' / 'skills' / name / 'SKILL.md'
        if not path.is_file():
            errors.append('Missing skill: ' + name)
            continue
        text = path.read_text()
        if not text.startswith('---\n') or '\nname: ' + name + '\n' not in text or '\ndescription: ' not in text:
            errors.append('Invalid skill metadata: ' + name)
        for link in re.findall(r'\]\(([^)]+)\)', text):
            if re.match(r'[a-z]+:', link) or link.startswith('#'):
                continue
            if not (path.parent / link.split('#')[0]).is_file():
                errors.append('Broken skill reference: ' + name)
    for args in (['--help'], ['doctor', '--tier', 'static'], ['plan', '--help'],
                 ['check', '--help'], ['docs-check', '--help'],
                 ['e2e', '--help'], ['cleanup', '--help']):
        try:
            run = subprocess.run([sys.executable, str(root / 'scripts/harness.py'), *args],
                                 cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            if run.returncode:
                errors.append('Harness command unavailable: ' + args[0])
        except (OSError, subprocess.TimeoutExpired):
            errors.append('Harness command unavailable: ' + args[0])
    return {'schemaVersion': 1, 'status': 'FAIL' if errors else 'PASS', 'findings': errors,
            'scope': 'Repository files and local command availability only',
            'clientDiscovery': 'NOT_VERIFIED; follow docs/agents/adapters/codex.md in a fresh client'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('smoke', help='Check repository adapter files and local commands; no client inference')
    handoff = commands.add_parser('handoff', help='Validate private handoff evidence without executing it')
    handoff.add_argument('file', type=Path)
    handoff.add_argument('--head', required=True, help='Independently verified current PR/checkout head')
    handoff.add_argument('--required-check', action='append', required=True, help='Independent plan check ID; repeatable')
    args = parser.parse_args()
    if args.command == 'smoke':
        result = smoke()
    else:
        try:
            if args.file.stat().st_size > 1024 * 1024:
                raise ValueError()
            result = validate_handoff(json.loads(args.file.read_text()), args.head, args.required_check)
        except (OSError, ValueError, UnicodeError):
            result = {'status': 'FAIL', 'findings': [{'status': 'FAIL', 'reason': 'Cannot read a valid handoff JSON'}]}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return CODES[result['status']]


if __name__ == '__main__':
    sys.exit(main())
