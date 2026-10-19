const assert = require('node:assert/strict');
const test = require('node:test');
const { execFileSync } = require('node:child_process');
const { readFileSync } = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const promtailConfig = readFileSync(path.join(root, 'infra/promtail/promtail-config.yml'), 'utf8');
for (const [configured, expected] of [['', 'local'], ['local', 'local'], ['stage', 'stage'], ['production', 'production']]) {
  test(`Compose passes environment ${configured || '(default)'} to Promtail`, () => {
    const rendered = execFileSync('docker', [
      'compose', '--env-file', '.env.docker.example', '-f', 'docker-compose.yml',
      '-f', 'docker-compose.dev.yml', '--profile', 'infra', '--profile', 'services',
      'config', '--no-env-resolution', '--format', 'json',
    ], { cwd: root, env: { ...process.env, DEPLOY_ENVIRONMENT: configured }, encoding: 'utf8' });
    const promtail = JSON.parse(rendered).services.promtail;
    assert.equal(promtail.environment.DEPLOY_ENVIRONMENT, expected);
    const expanded = promtailConfig.replaceAll('${DEPLOY_ENVIRONMENT:-local}', promtail.environment.DEPLOY_ENVIRONMENT);
    assert.match(expanded, new RegExp(`target_label: environment\\s+replacement: ${expected}`));
    assert.ok(promtail.command.includes('-config.expand-env=true'));
  });
}

test('log dashboard filters by selected environments and scopes service choices', () => {
  const dashboard = JSON.parse(readFileSync(path.join(root, 'infra/grafana/dashboards/taska-logs.json'), 'utf8'));
  const environment = dashboard.templating.list.find(variable => variable.name === 'environment');
  assert.equal(environment.query.label, 'environment');
  assert.equal(environment.datasource.type, 'loki');
  assert.equal(environment.includeAll, true);
  assert.equal(environment.multi, true);
  const service = dashboard.templating.list.find(variable => variable.name === 'service');
  assert.ok(service.query.stream.includes('${environment:regex}'));
  const queries = dashboard.panels.flatMap(panel => panel.targets || []).map(target => target.expr).filter(Boolean);
  assert.ok(queries.length > 0);
  for (const query of queries) {
    assert.ok(query.includes('environment=~"${environment:regex}"'));
    assert.ok(!query.includes('environment="local"'));
  }
});
