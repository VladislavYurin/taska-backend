#!/usr/bin/env python3
"""Configure Taska stage OAuth without deploying.

API: https://docs.dokploy.com/docs/api/reference-compose and reference-domain.
Dokploy has no conditional update API: rereads detect intervening changes, but
operators must serialize configuration writers while applying this plan.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

COMPOSE_ID = 'mIosJR_Yagh9i0Tuvsis9'
APP_NAME = 'taska-backend-ocrjz9'
HOST = 'oauth.taska.ozero.dev'
COMMAND = ('compose -p taska-backend-ocrjz9 --env-file .env '
           '-f ./docker-compose.yml -f ./docker-compose.oauth.yml '
           '-f ./docker-compose.oauth.stage.yml --profile oauth up -d --build --remove-orphans')
# Dokploy prepends docker to the first command and permits && only when each
# subsequent command starts with docker compose. Stop optional profile services
# explicitly: up --remove-orphans alone leaves disabled-profile containers alive.
ROLLBACK_COMMAND = ('compose -p taska-backend-ocrjz9 --env-file .env '
                    '-f ./docker-compose.yml --profile oauth stop oauth-server oauth-db '
                    '&& docker compose -p taska-backend-ocrjz9 --env-file .env '
                    '-f ./docker-compose.yml up -d --build --remove-orphans')
MARKER = 'TASKA_STAGE_OAUTH_ENABLED'
SECRET_KEYS = ('OAUTH_DB_PASSWORD', 'OAUTH_INTROSPECTION_SECRET',
               'OAUTH_IDENTITY_SERVICE_CREDENTIAL', 'MCP_OAUTH_EXCHANGE_CREDENTIAL',
               'OAUTH_DELEGATION_SIGNING_SECRET', 'MCP_GATEWAY_SIGNING_SECRET')
VALUES = {
    MARKER: 'true', 'OAUTH_ISSUER': 'https://' + HOST,
    'MCP_OAUTH_RESOURCE': 'https://mcp.taska.ozero.dev/mcp',
    'OAUTH_INTROSPECTION_URL': 'https://' + HOST + '/oauth2/introspect',
    'OAUTH_GRANT_VALIDATION_URL': 'https://' + HOST + '/internal/grants/validate',
    'OAUTH_INTROSPECTION_CLIENT_ID': 'auth-service',
    'OAUTH_INTERNAL_HOST': HOST, 'OAUTH_TRAEFIK_IP': '10.0.1.10',
    'OAUTH_PRIVATE_CLIENT_CIDR': '10.0.1.0/24',
    'OAUTH_INTERNAL_PROXIES': r'10\.0\.1\.10',
    'OAUTH_REDIRECT_URIS': 'http://127.0.0.1:6276/oauth/callback',
    'OAUTH_DCR_ENABLED': 'true', 'OAUTH_CIMD_HOSTS': '',
}
EXPECTED_PATHS = ('/.well-known/', '/login', '/logout', '/consent', '/assets/',
                  '/error', '/favicon.ico', '/oauth/consent', '/oauth/grants',
                  '/oauth/register', '/oauth2/authorize', '/oauth2/token', '/oauth2/revoke')


class ConfigurationError(Exception):
    pass


def env_entries(text):
    result = {}
    for line in text.splitlines():
        match = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$', line)
        if match:
            key, value = match.groups()
            if key in result and key in set(VALUES) | set(SECRET_KEYS):
                raise ConfigurationError('Duplicate environment key: ' + key)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            result[key] = value
    return result


def dotenv_value(value):
    # Unquoted backslashes are not uniformly preserved by dotenv implementations.
    return "'" + value + "'" if "\\" in value else value


def patch_env(text, updates):
    lines = text.splitlines(keepends=True)
    remaining = dict(updates)
    for i, line in enumerate(lines):
        match = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=', line)
        if match and match[1] in remaining:
            key = match[1]
            lines[i] = key + '=' + dotenv_value(remaining.pop(key)) + '\n'
    if remaining and lines and not lines[-1].endswith('\n'):
        lines[-1] += '\n'
    return ''.join(lines) + ''.join(k + '=' + dotenv_value(v) + '\n' for k, v in remaining.items())


def desired_env(text, generate=False):
    current = env_entries(text)
    updates = dict(VALUES)
    existing = [current[k] for k in SECRET_KEYS if current.get(k)]
    weak = [k for k in SECRET_KEYS if current.get(k) and
            (len(current[k]) < 32 or '\n' in current[k] or '\r' in current[k])]
    duplicate = [k for k in SECRET_KEYS if current.get(k) and existing.count(current[k]) > 1]
    if weak or duplicate:
        details = []
        if weak:
            details.append('weak secret keys: ' + ', '.join(weak))
        if duplicate:
            details.append('duplicate secret keys: ' + ', '.join(duplicate))
        raise ConfigurationError('; '.join(details) + '; rotation requires operator action')
    missing = [key for key in SECRET_KEYS if not current.get(key)]
    if generate:
        used = set(existing)
        for key in missing:
            value = secrets.token_urlsafe(48)
            while value in used:
                value = secrets.token_urlsafe(48)
            updates[key] = value
            used.add(value)
    return patch_env(text, updates), missing


def validate_compose(compose):
    if any(compose.get(k) != v for k, v in {'composeId': COMPOSE_ID, 'appName': APP_NAME, 'branch': 'develop'}.items()):
        raise ConfigurationError('Refusing unexpected compose identity/appName/branch')


def command_for(text):
    marker = env_entries(text).get(MARKER)
    if marker is None:
        return None
    if marker == 'true':
        return COMMAND
    if marker == 'false':
        return ROLLBACK_COMMAND
    raise ConfigurationError('Stage OAuth marker must be true or false')


def domain_spec(path):
    return {'composeId': COMPOSE_ID, 'host': HOST, 'path': path, 'port': 8080,
            'https': True, 'certificateType': 'letsencrypt', 'serviceName': 'oauth-server',
            'domainType': 'compose', 'stripPath': False, 'internalPath': '/', 'middlewares': []}


def validate_domains(domains):
    found = set()
    for domain in domains:
        if domain.get('host', '').lower().rstrip('.') != HOST:
            if domain.get('serviceName') == 'oauth-server':
                raise ConfigurationError('Unexpected public hostname on oauth-server')
            continue
        path = domain.get('path')
        spec = domain_spec(path)
        if (path not in EXPECTED_PATHS or path in found or
                any(domain.get(k) != spec[k] for k in ('composeId', 'serviceName', 'port', 'https', 'certificateType')) or
                domain.get('stripPath', False) or domain.get('internalPath') not in ('', None, '/') or
                domain.get('middlewares') not in (None, []) or domain.get('customEntrypoint') not in (None, '', 'websecure') or
                domain.get('forwardAuthEnabled', False)):
            raise ConfigurationError('Unsafe or conflicting OAuth public domain record')
        found.add(path)
    return [domain_spec(path) for path in EXPECTED_PATHS if path not in found]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Dokploy:
    def __init__(self, url, key):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ConfigurationError('DOKPLOY_URL must be an HTTPS origin')
        if parsed.path not in ('', '/'):
            raise ConfigurationError('DOKPLOY_URL must be an HTTPS origin')
        self.url, self.key = url.rstrip('/'), key.strip()
        if not self.key:
            raise ConfigurationError('Dokploy API key is missing')
        self.opener = urllib.request.build_opener(NoRedirect())

    def call(self, endpoint, data=None):
        url = self.url + '/api/' + endpoint
        if data is None:
            url += '?' + urllib.parse.urlencode({'composeId': COMPOSE_ID})
        request = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
                                         headers={'x-api-key': self.key, 'Content-Type': 'application/json'})
        try:
            with self.opener.open(request, timeout=30) as response:
                result = json.load(response)
            if isinstance(result, dict) and ('error' in result or result.get('success') is False):
                raise ConfigurationError('Dokploy API returned an error')
            return result
        except (urllib.error.URLError, ValueError):
            raise ConfigurationError('Dokploy API request failed: ' + endpoint) from None


def snapshot(api, validate_public=True):
    compose = api.call('compose.one')
    validate_compose(compose)
    domains = api.call('domain.byComposeId')
    if not isinstance(domains, list):
        raise ConfigurationError('Invalid domain list response')
    if validate_public:
        validate_domains(domains)
    return compose, domains


def outside_git(path):
    path = Path(path).expanduser().resolve()
    if any((p / '.git').exists() for p in [path.parent, *path.parents]):
        raise ConfigurationError('Secret/backup files must be outside Git worktrees')
    return path


def backup_snapshot(path, compose, domains):
    path = outside_git(path)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump({'compose': compose, 'domains': domains}, stream)


def configure(api, mode, backup=None):
    compose, domains = snapshot(api, validate_public=False)
    before = compose.get('env') or ''
    validate_public = not (mode == 'sync-command' and env_entries(before).get(MARKER) == 'false')
    if validate_public:
        validate_domains(domains)
    desired, missing = desired_env(before, generate=mode == 'apply') if mode != 'sync-command' else (before, [])
    command = command_for(desired)
    if command is not None and (compose.get('command') or '') not in ('', COMMAND, ROLLBACK_COMMAND):
        raise ConfigurationError('Refusing to overwrite unrelated custom compose command')
    additions = validate_domains(domains) if mode != 'sync-command' else []
    report = {'mode': mode, 'composeId': COMPOSE_ID, 'missingSecretKeys': missing,
              'changedEnvKeys': sorted(k for k, v in VALUES.items() if env_entries(before).get(k) != v) if mode != 'sync-command' else [],
              'domainsToCreate': [d['path'] for d in additions],
              'commandChange': command is not None and command != (compose.get('command') or ''), 'deployed': False}
    if mode == 'plan':
        return report
    if backup:
        backup_snapshot(backup, compose, domains)
    # Dokploy has no CAS. Abort rather than overwrite any observed concurrent edit.
    latest, latest_domains = snapshot(api, validate_public=validate_public)
    if latest.get('env') != compose.get('env') or latest.get('command') != compose.get('command') or latest_domains != domains:
        raise ConfigurationError('Concurrent Dokploy configuration change; rerun plan')
    for domain in additions:
        api.call('domain.create', domain)
    # Domain creation can take time: reread env immediately before its full replacement.
    latest = api.call('compose.one')
    validate_compose(latest)
    if latest.get('env') != compose.get('env') or latest.get('command') != compose.get('command'):
        raise ConfigurationError('Concurrent environment/command change; no environment overwritten')
    update = {'composeId': COMPOSE_ID}
    if desired != before:
        update['env'] = desired
    if command is not None and command != (latest.get('command') or ''):
        update['command'] = command
    if len(update) > 1:
        api.call('compose.update', update)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'apply', 'sync-command'])
    parser.add_argument('--backup', help='New 0600 backup file outside any Git worktree')
    args = parser.parse_args()
    try:
        if os.environ.get('DOKPLOY_COMPOSE_ID', COMPOSE_ID) != COMPOSE_ID:
            raise ConfigurationError('Unexpected DOKPLOY_COMPOSE_ID')
        paths = json.loads(Path(__file__).with_name('public-paths.json').read_text())
        if paths != list(EXPECTED_PATHS):
            raise ConfigurationError('Public path manifest does not match the reviewed boundary')
        key = os.environ.get('DOKPLOY_API_KEY')
        if not key and os.environ.get('DOKPLOY_API_KEY_FILE'):
            key = outside_git(os.environ['DOKPLOY_API_KEY_FILE']).read_text()
        api = Dokploy(os.environ.get('DOKPLOY_URL', ''), key or '')
        print(json.dumps(configure(api, args.mode, args.backup), indent=2))
    except ConfigurationError as error:
        # These messages are controlled and never include environment values.
        print('Configuration refused: ' + str(error), file=sys.stderr)
        return 1
    except OSError:
        # OS errors can include secret file contents/paths.
        print('API/file access failed; no deployment was requested.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
