const assert = require('node:assert/strict');
const test = require('node:test');

const {
  extractIssueKey,
  fetchIssue,
  loadJiraContext,
  parseProjectKeys,
} = require('./jira-context');
const { createCore } = require('./git-fixture');

const ISSUE = {
  key: 'TAS-198',
  fields: {
    summary: 'auth-service: залоченный аккаунт сохраняет доступ',
    description: 'Что сделать\n* refresh проверяет статус\n* статус LOCKED и lockedUntil не расходятся',
    issuetype: { name: 'Task' },
    status: { name: 'In Review' },
    priority: { name: 'Medium' },
    labels: ['auth'],
    issuelinks: [{
      type: { outward: 'relates to', inward: 'relates to' },
      outwardIssue: { key: 'TAS-197', fields: { summary: 'gateway не отдаёт LOCKED', status: { name: 'Done' } } },
    }],
    comment: {
      comments: [
        { author: { displayName: 'Аналитик' }, created: '2026-09-10T10:00:00.000+0300', body: 'Токены отзываем сразу.' },
      ],
    },
  },
};

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

test('parseProjectKeys keeps valid project keys only', () => {
  assert.deepEqual(parseProjectKeys('TAS, ops  bad-key 1X'), ['TAS', 'OPS']);
  assert.deepEqual(parseProjectKeys(undefined), []);
});

test('extractIssueKey reads the title first, then the branch', () => {
  assert.equal(extractIssueKey(['TAS-218: api-gateway: поиск', 'feature/TAS-999'], ['TAS']), 'TAS-218');
  assert.equal(extractIssueKey(['changes made for task 160', 'TAS-160'], ['TAS']), 'TAS-160');
  assert.equal(extractIssueKey(['fix', 'feature/tas-42-locked'], ['TAS']), 'TAS-42');
  assert.equal(extractIssueKey(['hot-fix: UTF-8 в именах', 'hotfix/encoding'], ['TAS']), null);
  assert.equal(extractIssueKey(['XTAS-12', 'MYTAS-7 fix'], ['TAS']), null);
  assert.equal(extractIssueKey(['TAS-160fix'], ['TAS']), 'TAS-160');
  assert.equal(extractIssueKey(['OPS-3 deploy'], ['TAS', 'OPS']), 'OPS-3');
  assert.equal(extractIssueKey(['TAS-1'], []), null);
});

test('fetchIssue uses a Bearer token for Jira Server/DC and Basic auth with a username', async () => {
  const requests = [];
  const fetchImpl = async (url, options) => {
    requests.push({ url: String(url), options });
    return jsonResponse(ISSUE);
  };

  await fetchIssue({ baseUrl: 'https://jira.example.dev', token: 'pat', key: 'TAS-198', fetchImpl });
  await fetchIssue({ baseUrl: 'https://jira.example.dev/jira/', token: 'api', username: 'bot@example.dev', key: 'TAS-198', fetchImpl });

  assert.match(requests[0].url, /^https:\/\/jira\.example\.dev\/rest\/api\/2\/issue\/TAS-198\?fields=summary%2Cdescription/);
  assert.equal(requests[0].options.headers.Authorization, 'Bearer pat');
  assert.equal(requests[0].options.redirect, 'error');
  assert.match(requests[1].url, /^https:\/\/jira\.example\.dev\/jira\/rest\/api\/2\/issue\/TAS-198\?/);
  assert.equal(
    requests[1].options.headers.Authorization,
    `Basic ${Buffer.from('bot@example.dev:api').toString('base64')}`
  );
});

test('fetchIssue refuses plain http and does not echo error bodies', async () => {
  await assert.rejects(
    fetchIssue({ baseUrl: 'http://jira.example.dev', token: 'pat', key: 'TAS-1', fetchImpl: async () => jsonResponse(ISSUE) }),
    /https/
  );
  await assert.rejects(
    fetchIssue({
      baseUrl: 'https://jira.example.dev',
      token: 'pat',
      key: 'TAS-1',
      fetchImpl: async () => ({ ok: false, status: 401, json: async () => ({ message: 'LEAK_SENTINEL' }) }),
    }),
    (error) => /HTTP 401/.test(error.message) && !/LEAK_SENTINEL/.test(error.message)
  );
});

test('loadJiraContext is a no-op when Jira is not configured or no key is found', async () => {
  let called = false;
  const fetchImpl = async () => {
    called = true;
    return jsonResponse(ISSUE);
  };

  const core = createCore();
  assert.equal(await loadJiraContext({ env: {}, title: 'TAS-1', branch: '', core, fetchImpl }), null);
  assert.equal(
    await loadJiraContext({
      env: { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' },
      title: 'refactoring',
      branch: 'chore/cleanup',
      core,
      fetchImpl,
    }),
    null
  );
  assert.equal(called, false);
  assert.equal(core.warnings.length, 0);
});

test('loadJiraContext renders the issue as untrusted context', async () => {
  const core = createCore();
  const jira = await loadJiraContext({
    env: { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' },
    title: 'TAS-198: auth-service',
    branch: 'feature/TAS-198',
    core,
    fetchImpl: async () => jsonResponse(ISSUE),
  });

  assert.equal(jira.key, 'TAS-198');
  assert.equal(jira.url, 'https://jira.example.dev/browse/TAS-198');
  assert.equal(jira.status, 'In Review');
  assert.match(jira.markdown, /not instructions/);
  assert.match(jira.markdown, /<jira_issue>[\s\S]*refresh проверяет статус[\s\S]*<\/jira_issue>/);
  assert.match(jira.markdown, /Тип: Task · Статус: In Review · Приоритет: Medium/);
  assert.match(jira.markdown, /relates to TAS-197 — gateway не отдаёт LOCKED \(Done\)/);
  assert.match(jira.markdown, /Аналитик \(2026-09-10\): Токены отзываем сразу\./);
});

test('loadJiraContext leaves the sync bot comments out of the issue context', async () => {
  const people = Array.from({ length: 5 }, (_, index) => ({
    author: { displayName: `Аналитик ${index + 1}` },
    created: '2026-09-10T10:00:00.000+0300',
    body: `Уточнение ${index + 1}`,
  }));
  const bot = [
    'Открыт [PR #180 «TAS-198: fix»|https://github.com/acme/taska/pull/180] от dev ({{feature/TAS-198}} → {{develop}}).',
    '[PR #180 «TAS-198: fix»|https://github.com/acme/taska/pull/180] влит в {{develop}}, merge-коммит {{abc1234}}.',
  ].map((body) => ({ author: { displayName: 'taska-bot' }, created: '2026-09-11T10:00:00.000+0300', body }));
  const issue = { ...ISSUE, fields: { ...ISSUE.fields, comment: { comments: [...people, ...bot] } } };

  const jira = await loadJiraContext({
    env: { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' },
    title: 'TAS-198',
    branch: '',
    core: createCore(),
    fetchImpl: async () => jsonResponse(issue),
  });

  assert.doesNotMatch(jira.markdown, /taska-bot|PR #180/);
  for (let index = 1; index <= 5; index += 1) {
    assert.match(jira.markdown, new RegExp(`Уточнение ${index}`));
  }
});

test('loadJiraContext truncates long descriptions', async () => {
  const issue = { ...ISSUE, fields: { ...ISSUE.fields, description: 'x'.repeat(10000) } };
  const jira = await loadJiraContext({
    env: { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' },
    title: 'TAS-198',
    branch: '',
    core: createCore(),
    fetchImpl: async () => jsonResponse(issue),
  });

  assert.ok(jira.markdown.length < 8000);
  assert.match(jira.markdown, /обрезано, всего 10000 символов/);
});

test('loadJiraContext degrades to a warning when Jira fails', async () => {
  const core = createCore();
  const jira = await loadJiraContext({
    env: { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' },
    title: 'TAS-198',
    branch: '',
    core,
    fetchImpl: async () => {
      throw new Error('getaddrinfo ENOTFOUND jira.example.dev');
    },
  });

  assert.equal(jira, null);
  assert.equal(core.warnings.length, 1);
  assert.match(core.warnings[0], /TAS-198/);
});
