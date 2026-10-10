const assert = require('node:assert/strict');
const test = require('node:test');

const syncJiraIssue = require('./jira-sync');
const { planSync, readSyncSettings } = syncJiraIssue;
const { createCore } = require('./git-fixture');

const ENV = { JIRA_BASE_URL: 'https://jira.example.dev', JIRA_TOKEN: 'pat' };
const TRANSITIONS = [
  { id: '11', name: 'To Do', to: { name: 'To Do' } },
  { id: '21', name: 'In Progress', to: { name: 'In Progress' } },
  { id: '31', name: 'In Review', to: { name: 'In Review' } },
  { id: '41', name: 'Done', to: { name: 'Done' } },
];

/** A Jira stand-in that records every request and keeps the issue status. */
function createJira({ status = 'In Progress', category = 'indeterminate', fail = {} } = {}) {
  const requests = [];
  const state = { status, category };
  const fetchImpl = async (url, options) => {
    const method = options.method ?? 'GET';
    const path = new URL(url).pathname;
    const body = options.body ? JSON.parse(options.body) : undefined;
    requests.push({ method, path, body, headers: options.headers });
    const kind = path.endsWith('/transitions') ? 'transitions'
      : path.endsWith('/remotelink') ? 'remotelink'
        : path.endsWith('/comment') ? 'comment'
          : 'issue';
    if (fail[kind]) {
      return { ok: false, status: fail[kind], json: async () => ({}) };
    }
    if (kind === 'issue') {
      return { ok: true, status: 200, json: async () => ({ fields: { status: { name: state.status, statusCategory: { key: state.category } } } }) };
    }
    if (kind === 'transitions' && method === 'GET') {
      return { ok: true, status: 200, json: async () => ({ transitions: TRANSITIONS }) };
    }
    if (kind === 'transitions') {
      state.status = TRANSITIONS.find((item) => item.id === body.transition.id).to.name;
      return { ok: true, status: 204, json: async () => { throw new Error('no body'); } };
    }
    return { ok: true, status: 201, json: async () => ({ id: '1' }) };
  };
  return { requests, state, fetchImpl };
}

function event(action, overrides = {}) {
  return {
    repo: { owner: 'acme', repo: 'taska' },
    payload: {
      action,
      pull_request: {
        number: 179,
        title: 'TAS-250: ci: AI-ревью [без повторов] | v2',
        html_url: 'https://github.com/acme/taska/pull/179',
        state: action === 'closed' ? 'closed' : 'open',
        draft: false,
        merged: false,
        merge_commit_sha: null,
        user: { login: 'nikita' },
        head: { ref: 'feature/TAS-250' },
        base: { ref: 'develop' },
        ...overrides,
      },
    },
  };
}

const posted = (jira, suffix) => jira.requests.filter((r) => r.method === 'POST' && r.path.endsWith(suffix));

test('opening a PR moves the issue to In Review, links the PR and comments', async () => {
  const jira = createJira();
  const core = createCore();

  const result = await syncJiraIssue({ context: event('opened'), core, env: ENV, fetchImpl: jira.fetchImpl });

  assert.equal(result.key, 'TAS-250');
  assert.equal(jira.state.status, 'In Review');
  assert.deepEqual(posted(jira, '/transitions').map((r) => r.body), [{ transition: { id: '31' } }]);

  const [link] = posted(jira, '/remotelink');
  assert.equal(link.path, '/rest/api/2/issue/TAS-250/remotelink');
  assert.equal(link.body.globalId, 'github-pr:acme/taska#179');
  assert.equal(link.body.object.url, 'https://github.com/acme/taska/pull/179');
  assert.equal(link.body.object.status.resolved, false);

  const [comment] = posted(jira, '/comment');
  assert.equal(
    comment.body.body,
    'Открыт [PR #179 «TAS-250: ci: AI-ревью без повторов v2»|https://github.com/acme/taska/pull/179] от nikita '
    + '({{feature/TAS-250}} → {{develop}}). Статус: In Progress → In Review.'
  );
  assert.equal(comment.headers.Authorization, 'Bearer pat');
  assert.equal(core.warnings.length, 0);
});

test('an issue already in review is not moved again but still gets the comment', async () => {
  const jira = createJira({ status: 'In Review' });

  await syncJiraIssue({ context: event('reopened'), core: createCore(), env: ENV, fetchImpl: jira.fetchImpl });

  assert.equal(posted(jira, '/transitions').length, 0);
  assert.match(posted(jira, '/comment')[0].body.body, /переоткрыт \(\{\{feature\/TAS-250\}\} → \{\{develop\}\}\)\.$/);
});

test('a finished issue is never moved back to review', async () => {
  const jira = createJira({ status: 'Закрыта', category: 'done' });

  const result = await syncJiraIssue({ context: event('opened'), core: createCore(), env: ENV, fetchImpl: jira.fetchImpl });

  assert.equal(result.move.reason, 'finished');
  assert.equal(posted(jira, '/transitions').length, 0);
  assert.equal(jira.state.status, 'Закрыта');
});

test('merging into develop moves the issue to Done', async () => {
  const jira = createJira({ status: 'In Review' });

  await syncJiraIssue({
    context: event('closed', { merged: true, merge_commit_sha: 'abcdef1234567890' }),
    core: createCore(),
    env: ENV,
    fetchImpl: jira.fetchImpl,
  });

  assert.equal(jira.state.status, 'Done');
  assert.equal(posted(jira, '/remotelink')[0].body.object.status.resolved, true);
  assert.match(
    posted(jira, '/comment')[0].body.body,
    /влит в \{\{develop\}\}, merge-коммит \{\{abcdef1\}\}\. Статус: In Review → Done\.$/
  );
});

test('merging into a feature branch (a stacked PR) does not finish the issue', async () => {
  const jira = createJira({ status: 'In Review' });

  await syncJiraIssue({
    context: event('closed', { merged: true, merge_commit_sha: 'abcdef1234567890', base: { ref: 'feature/TAS-249' } }),
    core: createCore(),
    env: ENV,
    fetchImpl: jira.fetchImpl,
  });

  assert.equal(jira.state.status, 'In Review');
  assert.equal(posted(jira, '/transitions').length, 0);
  assert.match(posted(jira, '/comment')[0].body.body, /влит в \{\{feature\/TAS-249\}\}, merge-коммит \{\{abcdef1\}\}\.$/);
});

test('closing a PR without merging only leaves a comment', async () => {
  const jira = createJira({ status: 'In Review' });

  await syncJiraIssue({ context: event('closed'), core: createCore(), env: ENV, fetchImpl: jira.fetchImpl });

  assert.equal(posted(jira, '/transitions').length, 0);
  assert.equal(jira.requests.filter((r) => r.path.endsWith('/transitions')).length, 0);
  assert.match(posted(jira, '/comment')[0].body.body, /закрыт без мержа\.$/);
});

test('PRs without a key, drafts and an unconfigured Jira change nothing', async () => {
  const jira = createJira();

  const noKey = await syncJiraIssue({
    context: event('opened', { title: 'Обновить зависимости', head: { ref: 'chore/deps' } }),
    core: createCore(),
    env: ENV,
    fetchImpl: jira.fetchImpl,
  });
  const draft = await syncJiraIssue({ context: event('opened', { draft: true }), core: createCore(), env: ENV, fetchImpl: jira.fetchImpl });
  const unconfigured = await syncJiraIssue({ context: event('opened'), core: createCore(), env: {}, fetchImpl: jira.fetchImpl });
  const synchronize = await syncJiraIssue({ context: event('synchronize'), core: createCore(), env: ENV, fetchImpl: jira.fetchImpl });

  assert.deepEqual(
    [noKey.skipped, draft.skipped, unconfigured.skipped, synchronize.skipped],
    ['no-key', 'no-action', 'not-configured', 'no-action']
  );
  assert.equal(jira.requests.length, 0);
});

test('Jira errors become warnings and the comment is still posted', async () => {
  const jira = createJira({ fail: { transitions: 403, remotelink: 500 } });
  const core = createCore();

  await syncJiraIssue({ context: event('opened'), core, env: ENV, fetchImpl: jira.fetchImpl });

  assert.equal(core.warnings.length, 2);
  assert.match(core.warnings[0], /Could not link the PR to TAS-250: Jira responded with HTTP 500/);
  assert.match(core.warnings[1], /Could not move TAS-250 to "In Review": Jira responded with HTTP 403/);
  const [comment] = posted(jira, '/comment');
  assert.doesNotMatch(comment.body.body, /Статус:/);
});

test('status names and release branches are configurable', () => {
  const settings = readSyncSettings({
    JIRA_STATUS_IN_REVIEW: 'На ревью',
    JIRA_STATUS_DONE: 'Готово',
    JIRA_DONE_BRANCHES: 'main, release',
  });
  assert.equal(settings.inReviewStatus, 'На ревью');
  assert.deepEqual(settings.doneBranches, ['main', 'release']);

  const merged = planSync(event('closed', { merged: true, base: { ref: 'release' } }).payload, settings);
  assert.equal(merged.targetStatus, 'Готово');
  const develop = planSync(event('closed', { merged: true, base: { ref: 'develop' } }).payload, settings);
  assert.equal(develop.targetStatus, null);
});
