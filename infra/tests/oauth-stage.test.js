const assert = require('node:assert/strict');
const test = require('node:test');
const { execFileSync } = require('node:child_process');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const env = {
  ...process.env,
  OAUTH_DB_PASSWORD: 'local-db-password-for-compose-validation-only',
  OAUTH_INTROSPECTION_SECRET: 'local-introspection-secret-for-compose-validation',
  OAUTH_IDENTITY_SERVICE_CREDENTIAL: 'local-identity-secret-for-compose-validation',
  MCP_OAUTH_EXCHANGE_CREDENTIAL: 'local-exchange-secret-for-compose-validation',
  OAUTH_DELEGATION_SIGNING_SECRET: 'local-delegation-secret-for-compose-validation',
  OAUTH_ISSUER: 'https://oauth.taska.ozero.dev',
  MCP_OAUTH_RESOURCE: 'https://mcp.taska.ozero.dev/mcp',
  OAUTH_CIMD_HOSTS: '',
  OAUTH_DCR_ENABLED: 'true',
  OAUTH_REDIRECT_URIS: 'http://127.0.0.1:6276/oauth/callback',
  OAUTH_INTROSPECTION_URL: 'https://oauth.taska.ozero.dev/oauth2/introspect',
  OAUTH_GRANT_VALIDATION_URL: 'https://oauth.taska.ozero.dev/internal/grants/validate',
  OAUTH_INTERNAL_HOST: 'oauth.taska.ozero.dev',
  OAUTH_TRAEFIK_IP: '10.0.1.10',
  OAUTH_PRIVATE_CLIENT_CIDR: '10.0.1.0/24',
  OAUTH_INTERNAL_PROXIES: '10\\.0\\.1\\.10',
};
function config(files, profiles = []) {
  return JSON.parse(execFileSync('docker', [
    'compose', '--env-file', '.env.docker.example',
    ...files.flatMap(file => ['-f', file]),
    ...profiles.flatMap(profile => ['--profile', profile]),
    'config', '--no-env-resolution', '--format', 'json',
  ], { cwd: root, env, encoding: 'utf8' }));
}
test('default deployment excludes OAuth services and uses the actual gateway for MCP health', () => {
  const rendered = config(['docker-compose.yml']);
  assert.equal(rendered.services['oauth-server'], undefined);
  assert.equal(rendered.services['oauth-db'], undefined);
  assert.equal(rendered.services['mcp-server'].environment.MCP_GATEWAY_URL, 'http://api-gateway:8080');
});
test('OAuth stage merges private TLS routing without replacing public domain labels', () => {
  const rendered = config(['docker-compose.yml', 'docker-compose.oauth.yml', 'docker-compose.oauth.stage.yml'], ['oauth']);
  const oauth = rendered.services['oauth-server'];
  assert.equal(oauth.environment.OAUTH_CIMD_HOSTS, '');
  assert.equal(oauth.environment.OAUTH_DCR_ENABLED, 'true');
  assert.equal(oauth.environment.OAUTH_REDIRECT_URIS, env.OAUTH_REDIRECT_URIS);
  assert.equal(oauth.environment.OAUTH_INTERNAL_PROXIES, env.OAUTH_INTERNAL_PROXIES);
  const trustedPeer = new RegExp('^' + oauth.environment.OAUTH_INTERNAL_PROXIES + '$');
  assert.ok(trustedPeer.test('10.0.1.10'));
  assert.ok(!trustedPeer.test('10x0x1x10'));
  assert.ok(!trustedPeer.test('10.0.1.11'));
  assert.deepEqual(Object.keys(oauth.networks).sort(), ['dokploy-network', 'taskanetwork']);
  assert.equal(rendered.networks['dokploy-network'].external, true);
  const rule = oauth.labels['traefik.http.routers.taska-oauth-private.rule'];
  assert.match(rule, /Host\(`oauth\.taska\.ozero\.dev`\)/);
  assert.match(rule, /ClientIP\(`10\.0\.1\.0\/24`\)/);
  assert.match(rule, /Path\(`\/oauth2\/introspect`\)/);
  assert.match(rule, /PathPrefix\(`\/internal\/`\)/);
  assert.equal(oauth.labels['traefik.docker.network'], 'dokploy-network');
  assert.equal(oauth.labels['traefik.http.services.taska-oauth-private.loadbalancer.server.port'], '8080');
  assert.ok(JSON.stringify(rendered.services['auth-service'].extra_hosts).includes('oauth.taska.ozero.dev=10.0.1.10'));
  assert.equal(rendered.services['auth-service'].environment.AUTH_OAUTH_ENABLED, 'true');
  assert.equal(rendered.services['auth-service'].environment.OAUTH_INTROSPECTION_URL, env.OAUTH_INTROSPECTION_URL);
  assert.equal(rendered.services['auth-service'].environment.OAUTH_GRANT_VALIDATION_URL, env.OAUTH_GRANT_VALIDATION_URL);
  assert.equal(rendered.services['mcp-server'].environment.MCP_OAUTH_ENABLED, 'true');
  assert.equal(rendered.services['api-gateway'].environment.OAUTH_ISSUER, env.OAUTH_ISSUER);
  assert.equal(oauth.ports[0].host_ip, '127.0.0.1');
  assert.equal(rendered.services['oauth-db'].volumes[0].source, 'oauth-db-data');
});
test('public ingress paths cannot route introspection, private RPC or actuator', () => {
  const prefixes = JSON.parse(readFileSync(path.join(root, 'infra/oauth-stage/public-paths.json')));
  assert.equal(new Set(prefixes).size, prefixes.length);
  for (const privatePath of ['/internal/grants/validate', '/oauth2/introspect', '/actuator/health']) {
    assert.ok(prefixes.every(prefix => !privatePath.startsWith(prefix)), privatePath);
  }
  for (const endpoint of ['/login/context', '/logout', '/oauth/consent', '/oauth/grants', '/oauth/register', '/oauth2/authorize', '/oauth2/token', '/oauth2/revoke', '/.well-known/oauth-authorization-server']) {
    assert.ok(prefixes.some(prefix => endpoint.startsWith(prefix)), endpoint);
  }
});
