import contextlib
import base64
import shutil
import subprocess
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('configure', Path(__file__).with_name('configure.py'))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


class FakeAPI:
    def __init__(self, env='# existing\nOTHER="keep this"\n', domains=None):
        self.compose = {'composeId': c.COMPOSE_ID, 'appName': c.APP_NAME,
                        'branch': 'develop', 'env': env, 'command': ''}
        self.domains = [] if domains is None else domains
        self.calls = []
        self.reads = 0
        self.change_on_read = None

    def call(self, endpoint, data=None):
        self.calls.append((endpoint, copy.deepcopy(data)))
        if endpoint == 'compose.one':
            self.reads += 1
            if self.reads == self.change_on_read:
                self.compose['env'] += 'CONCURRENT=preserve\n'
            return copy.deepcopy(self.compose)
        if endpoint == 'domain.byComposeId':
            return copy.deepcopy(self.domains)
        if endpoint == 'domain.create':
            self.domains.append(dict(data, domainId=str(len(self.domains))))
            return {}
        if endpoint == 'compose.update':
            self.compose.update(data)
            return {}
        raise AssertionError(endpoint)

    def writes(self):
        return [(name, data) for name, data in self.calls if data is not None]


class ConfigureTests(unittest.TestCase):
    def test_plan_has_no_writes_or_generated_secrets(self):
        api = FakeAPI()
        with patch.object(c.secrets, 'token_urlsafe', side_effect=AssertionError('plan generated secret')):
            report = c.configure(api, 'plan')
        self.assertEqual([], api.writes())
        self.assertEqual(list(c.SECRET_KEYS), report['missingSecretKeys'])
        self.assertEqual(list(c.EXPECTED_PATHS), report['domainsToCreate'])
        self.assertFalse(report['deployed'])

    def test_apply_is_idempotent_and_preserves_unknown_values_and_domains(self):
        unrelated = {'host': 'api.taska.ozero.dev', 'path': '/', 'serviceName': 'api-gateway', 'opaque': 'preserve'}
        api = FakeAPI(domains=[unrelated])
        c.configure(api, 'apply')
        env = c.env_entries(api.compose['env'])
        self.assertTrue(api.compose['env'].startswith('# existing\nOTHER="keep this"\n'))
        self.assertEqual(c.COMMAND, api.compose['command'])
        self.assertFalse(api.compose['command'].startswith('docker '))
        self.assertIn("OAUTH_INTERNAL_PROXIES='10\\.0\\.1\\.10'", api.compose['env'])
        values = [env[k] for k in c.SECRET_KEYS]
        self.assertEqual(6, len(set(values)))
        self.assertTrue(all(len(v) >= 32 for v in values))
        self.assertEqual(unrelated, api.domains[0])
        old_env = api.compose['env']
        api.calls.clear()
        c.configure(api, 'apply')
        self.assertEqual(old_env, api.compose['env'])
        self.assertEqual([], api.writes())

    def test_only_missing_secret_generated(self):
        old = 'x' * 40
        api = FakeAPI('OAUTH_DB_PASSWORD=' + old + '\n')
        c.configure(api, 'apply')
        self.assertEqual(old, c.env_entries(api.compose['env'])['OAUTH_DB_PASSWORD'])

    def test_reject_duplicate_or_weak_existing_secret(self):
        for env in ['OAUTH_DB_PASSWORD=weak\n', 'OAUTH_DB_PASSWORD=' + 'a' * 40 + '\nOAUTH_INTROSPECTION_SECRET=' + 'a' * 40 + '\n']:
            api = FakeAPI(env)
            with self.assertRaises(c.ConfigurationError):
                c.configure(api, 'apply')
            self.assertEqual([], api.writes())

    def test_reject_wrong_compose_identity(self):
        for key, value in [('composeId', 'other'), ('appName', 'other'), ('branch', 'main')]:
            api = FakeAPI()
            api.compose[key] = value
            with self.assertRaises(c.ConfigurationError):
                c.configure(api, 'apply')
            self.assertEqual([], api.writes())

    def test_reject_unsafe_existing_domain_before_writes(self):
        for modification in [{'path': '/'}, {'path': '/internal/'}, {'path': '/oauth2/'},
                             {'stripPath': True}, {'internalPath': '/internal'}, {'internalPath': '/login'},
                             {'middlewares': ['rewrite@file']}, {'https': False},
                             {'serviceName': 'other'}, {'host': 'public.other.invalid'},
                             {'forwardAuthEnabled': True}, {'port': 9090}]:
            domain = dict(c.domain_spec('/login'), **modification)
            api = FakeAPI(domains=[domain])
            with self.subTest(modification=modification), self.assertRaises(c.ConfigurationError):
                c.configure(api, 'apply')
            self.assertEqual([], api.writes())

    def test_abort_concurrent_change_before_domain_writes(self):
        api = FakeAPI()
        api.change_on_read = 2
        with self.assertRaises(c.ConfigurationError):
            c.configure(api, 'apply')
        self.assertEqual([], api.writes())
        self.assertIn('CONCURRENT=preserve', api.compose['env'])

    def test_abort_late_change_without_overwriting_environment(self):
        api = FakeAPI()
        api.change_on_read = 3
        with self.assertRaises(c.ConfigurationError):
            c.configure(api, 'apply')
        self.assertFalse(any(name == 'compose.update' for name, _ in api.calls))
        self.assertEqual(13, len(api.domains))  # rerun reconciles these safe records
        self.assertIn('CONCURRENT=preserve', api.compose['env'])

    def test_sync_command_absent_false_true_and_no_secret_generation(self):
        for marker, old, expected in [('', 'custom', 'custom'),
                                      (c.MARKER + '=false\n', c.COMMAND, c.ROLLBACK_COMMAND),
                                      (c.MARKER + '=true\n', '', c.COMMAND)]:
            api = FakeAPI(marker)
            api.compose['command'] = old
            with patch.object(c.secrets, 'token_urlsafe', side_effect=AssertionError('sync generated secret')):
                c.configure(api, 'sync-command')
            self.assertEqual(marker, api.compose['env'])
            self.assertEqual(expected, api.compose['command'])
            self.assertEqual([], api.domains)
        with self.assertRaises(c.ConfigurationError):
            c.command_for(c.MARKER + '=unknown\n')

    def test_backup_is_exclusive_private_and_outside_git(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'backup.json'
            api = FakeAPI()
            c.configure(api, 'apply', target)
            self.assertEqual(0o600, target.stat().st_mode & 0o777)
            self.assertEqual('# existing\nOTHER="keep this"\n', json.loads(target.read_text())['compose']['env'])
            with self.assertRaises(FileExistsError):
                c.backup_snapshot(target, {}, [])
            (Path(folder) / '.git').mkdir()
            with self.assertRaises(c.ConfigurationError):
                c.outside_git(Path(folder) / 'forbidden')

    def test_plan_report_contains_no_secret_values(self):
        api = FakeAPI('OAUTH_DB_PASSWORD=' + 'sensitive' * 8 + '\n')
        self.assertNotIn('sensitive', json.dumps(c.configure(api, 'plan')))

    def test_emergency_disable_allows_unsafe_domains_but_enable_rejects(self):
        unsafe = c.domain_spec('/')
        api = FakeAPI(c.MARKER + '=false\n', domains=[unsafe])
        api.compose['command'] = c.COMMAND
        c.configure(api, 'sync-command')
        self.assertEqual(c.ROLLBACK_COMMAND, api.compose['command'])
        self.assertEqual([unsafe], api.domains)
        for mode in ['plan', 'apply', 'sync-command']:
            api = FakeAPI(c.MARKER + '=true\n', domains=[unsafe])
            with self.assertRaises(c.ConfigurationError):
                c.configure(api, mode)
            self.assertEqual([], api.writes())

    def test_rollback_command_stops_only_owned_optional_services(self):
        first, second = c.ROLLBACK_COMMAND.split(' && ')
        self.assertEqual('compose -p ' + c.APP_NAME + ' --env-file .env -f ./docker-compose.yml --profile oauth stop oauth-server oauth-db', first)
        self.assertEqual('docker compose -p ' + c.APP_NAME + ' --env-file .env -f ./docker-compose.yml up -d --build --remove-orphans', second)
        api = FakeAPI(c.MARKER + '=true\n')
        api.compose['command'] = c.ROLLBACK_COMMAND
        c.configure(api, 'sync-command')
        self.assertEqual(c.COMMAND, api.compose['command'])

    def test_custom_command_cannot_be_overwritten(self):
        for mode in ['apply', 'sync-command']:
            api = FakeAPI(c.MARKER + '=true\n')
            api.compose['command'] = 'compose custom-job'
            with self.assertRaises(c.ConfigurationError):
                c.configure(api, mode)
            self.assertEqual([], api.writes())

    def test_unrelated_duplicate_lines_preserved_with_last_value(self):
        text = 'ADMIN_DB_NAME=old\n# keep\nADMIN_DB_NAME=current\n'
        self.assertEqual('current', c.env_entries(text)['ADMIN_DB_NAME'])
        api = FakeAPI(text)
        c.configure(api, 'apply')
        self.assertTrue(api.compose['env'].startswith(text))
        self.assertTrue(all(d['internalPath'] == '/' and not d['stripPath'] for d in api.domains))

    def test_secret_validation_names_keys_without_values(self):
        for text, names in [
            ('OAUTH_DB_PASSWORD=weak-do-not-print\n', ['OAUTH_DB_PASSWORD']),
            ('OAUTH_DB_PASSWORD=' + 'private-value-' * 4 + '\nOAUTH_INTROSPECTION_SECRET=' + 'private-value-' * 4 + '\n',
             ['OAUTH_DB_PASSWORD', 'OAUTH_INTROSPECTION_SECRET']),
        ]:
            with self.assertRaises(c.ConfigurationError) as caught:
                c.desired_env(text)
            for name in names:
                self.assertIn(name, str(caught.exception))
            self.assertNotIn('weak-do-not-print', str(caught.exception))
            self.assertNotIn('private-value-', str(caught.exception))

    def test_generated_secret_collision_retried(self):
        api = FakeAPI('OAUTH_DB_PASSWORD=' + 'a' * 40 + '\n')
        generated = ['a' * 40, 'b' * 40, 'b' * 40, 'c' * 40, 'd' * 40, 'e' * 40, 'f' * 40]
        with patch.object(c.secrets, 'token_urlsafe', side_effect=generated) as random:
            c.configure(api, 'apply')
        self.assertEqual(6, random.call_count)
        self.assertEqual(6, len(set(c.env_entries(api.compose['env'])[k] for k in c.SECRET_KEYS)))

    def test_mcp_signing_key_uses_standard_base64_and_real_java_decoder(self):
        existing = ''.join(k + '=' + chr(65 + i) * 64 + '\n'
                           for i, k in enumerate(c.SECRET_KEYS) if k != 'MCP_GATEWAY_SIGNING_SECRET')
        api = FakeAPI(existing)
        with patch.object(c.secrets, 'token_bytes', return_value=b'\xff' * 48):
            c.configure(api, 'apply')
        encoded = c.env_entries(api.compose['env'])['MCP_GATEWAY_SIGNING_SECRET']
        self.assertEqual('/' * 64, encoded)
        self.assertEqual(b'\xff' * 48, base64.b64decode(encoded, validate=True))
        # Exercise the production Java parser shared by gateway and MCP, rather
        # than assuming Python's decoder has exactly the same acceptance policy.
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('JDK unavailable for cross-language contract')
        source = Path(__file__).resolve().parents[2] / 'common-lib/src/main/java/ru/taska/security/McpGatewaySignature.java'
        with tempfile.TemporaryDirectory() as folder:
            harness = Path(folder) / 'SigningKeyContract.java'
            harness.write_text('import ru.taska.security.McpGatewaySignature; public class SigningKeyContract { '
                               'public static void main(String[] args) throws Exception { '
                               'String key = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine(); '
                               'System.out.print(McpGatewaySignature.secret(key).length); }}')
            subprocess.run(['javac', '-d', folder, str(source), str(harness)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', folder, 'SigningKeyContract'], input=encoded,
                                    text=True, check=True, capture_output=True)
            self.assertEqual('48', result.stdout)

    def test_invalid_existing_mcp_key_rejected_before_platform_writes(self):
        for key in ['_' * 64, '-' * 64, base64.b64encode(b'x' * 24).decode()]:
            for mode in ['plan', 'apply']:
                api = FakeAPI('MCP_GATEWAY_SIGNING_SECRET=' + key + '\n')
                with self.subTest(mode=mode), self.assertRaises(c.ConfigurationError) as caught:
                    c.configure(api, mode)
                self.assertIn('MCP_GATEWAY_SIGNING_SECRET', str(caught.exception))
                self.assertEqual([], api.writes())

    def test_duplicate_environment_key_refused(self):
        with self.assertRaises(c.ConfigurationError):
            c.env_entries('OAUTH_ISSUER=1\nOAUTH_ISSUER=2\n')

    def test_api_refuses_credential_forwarding_redirects_and_plain_http(self):
        for url in ['http://dokploy.invalid', 'https://user:pass@dokploy.invalid', 'https://dokploy.invalid/api']:
            with self.assertRaises(c.ConfigurationError):
                c.Dokploy(url, 'key')
        self.assertIsNone(c.NoRedirect().redirect_request(None, None, None, None, None, None))


if __name__ == '__main__':
    unittest.main()
