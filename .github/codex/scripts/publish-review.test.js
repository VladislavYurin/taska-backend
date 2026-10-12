const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const publishReview = require('./publish-review');
const {
  classifyPreviousFindings,
  parseReview,
  planComments,
  readSettings,
  renderSummary,
} = publishReview;
const { parseState } = require('./review-shared');
const {
  createContext,
  createCore,
  createGithub,
  createRepository,
  inDirectory,
  numberedLines,
  replaceLines,
} = require('./git-fixture');

const CLOCK_SKEW = 'unlockIfExpired использует now() БД, а AccountLockService сравнивает lockedUntil с Instant.now() JVM. При рассинхроне часов БД и приложения возможна ситуация, когда сервис считает окно истёкшим, а UPDATE не срабатывает.';
const CLOCK_SKEW_REWORDED = '`unlockIfExpired` использует `now()` из Postgres, тогда как `AccountLockService.resolveLockState` сравнивает `lockedUntil` с `Instant.now()` приложения. При рассинхронизации часов сервис считает окно истёкшим, а UPDATE не срабатывает.';

function comment(overrides = {}) {
  return { severity: 'high', path: 'src/A.java', line: 10, body: 'Новая проблема с транзакцией при сохранении пользователя.', ...overrides };
}

function scope({ added = [10, 11, 12, 20], reviewable = added, touched = ['src/A.java'] } = {}) {
  return {
    prAddedLines: new Map([['src/A.java', new Set(added)]]),
    reviewableLines: new Map([['src/A.java', new Set(reviewable)]]),
    touchedFiles: new Set(touched),
  };
}

function previousFinding(overrides = {}) {
  return {
    id: 'F1',
    threadId: 'T1',
    commentId: 501,
    url: 'https://github.com/acme/taska/pull/7#discussion_r501',
    body: `<!-- ai-pr-review:0123456789abcdef0123 -->\n🟠 **[high]** ${CLOCK_SKEW}`,
    path: 'src/A.java',
    line: 11,
    originalLine: 11,
    isResolved: false,
    isOutdated: false,
    severity: 'high',
    text: CLOCK_SKEW,
    replies: [],
    ...overrides,
  };
}

test('parseReview validates and normalizes the model response', () => {
  const review = parseReview(JSON.stringify({
    summary: ' Summary ',
    task_alignment: ' Покрывает требования. ',
    manual_checks: ['Run smoke test', 42, ''],
    comments: [
      { severity: 'high', path: 'src/Foo.java', line: 10, body: 'Fix the race.' },
      { severity: 'High', path: './src/Bar.java', line: '12', body: 'Numeric string line.' },
    ],
    previous_findings: [
      { id: 'F1', status: 'fixed' },
      { id: 'F2', status: 'open' },
      { id: 'bogus', status: 'fixed' },
      { id: 'F3', status: 'maybe' },
    ],
    tests: 'Tests are missing.',
  }));

  assert.equal(review.summary, 'Summary');
  assert.equal(review.taskAlignment, 'Покрывает требования.');
  assert.deepEqual(review.manualChecks, ['Run smoke test']);
  assert.deepEqual(review.comments.map((c) => [c.severity, c.path, c.line]), [
    ['high', 'src/Foo.java', 10],
    ['high', 'src/Bar.java', 12],
  ]);
  assert.deepEqual(review.previousFindings, [{ id: 'F1', status: 'fixed' }, { id: 'F2', status: 'open' }]);
  assert.deepEqual(review.warnings, []);
  assert.throws(() => parseReview('[]'), /JSON object/);
  assert.throws(() => parseReview('not json'));
});

test('parseReview drops malformed findings instead of failing the review', () => {
  const review = parseReview(JSON.stringify({
    summary: 's',
    manual_checks: [],
    tests: 't',
    comments: [
      { severity: 'high', path: 'src/A.java', line: 0, body: 'Line zero: kept for the summary.' },
      { severity: 'high', path: 'src/A.java', line: null, body: 'No line: kept for the summary.' },
      { severity: 'blocker', path: 'src/A.java', line: 3, body: 'Unknown severity.' },
      { severity: 'high', path: '../etc/passwd', line: 1, body: 'Unsafe path.' },
      { severity: 'high', path: 'src/A.java', line: 4, body: '' },
      'not an object',
      { severity: 'low', path: 'src/A.java', line: 5, body: 'Valid.' },
    ],
  }));

  assert.deepEqual(review.comments.map((c) => [c.line, c.body]), [
    [null, 'Line zero: kept for the summary.'],
    [null, 'No line: kept for the summary.'],
    [5, 'Valid.'],
  ]);
  assert.equal(review.warnings.length, 6);
  assert.match(review.warnings[0], /no valid line \(0\); listed in the summary only/);
});

test('parseReview tolerates responses without the new optional fields', () => {
  const review = parseReview(JSON.stringify({ summary: 's', manual_checks: [], comments: [], tests: 't' }));

  assert.equal(review.taskAlignment, '');
  assert.deepEqual(review.previousFindings, []);
  const empty = parseReview('{}');
  assert.deepEqual(empty.comments, []);
  assert.match(empty.summary, /не вернула/);
});

test('planComments lists findings without a line in the summary', () => {
  const plan = planComments([comment({ line: null, body: 'Проблема в удалённом файле, строки нет.' })], scope());

  assert.deepEqual(plan.extra.map((c) => c.line), [null]);
  assert.equal(plan.inline.length, 0);
});

test('readSettings applies defaults and bounds', () => {
  assert.deepEqual(readSettings({}), { minSeverity: 'medium', maxInline: 8, autoResolve: false });
  assert.deepEqual(
    readSettings({ AI_REVIEW_INLINE_MIN_SEVERITY: 'high', AI_REVIEW_MAX_INLINE: '50', AI_REVIEW_AUTO_RESOLVE: 'true' }),
    { minSeverity: 'high', maxInline: 20, autoResolve: true }
  );
  assert.equal(readSettings({ AI_REVIEW_INLINE_MIN_SEVERITY: 'bogus', AI_REVIEW_MAX_INLINE: '-1' }).maxInline, 8);
});

test('planComments keeps minor and overflow findings out of the diff', () => {
  const plan = planComments([
    comment({ severity: 'low', line: 10, body: 'Мелочь про логирование ошибки маппинга пользователя в репозитории.' }),
    comment({ severity: 'medium', line: 11, body: 'Проблема с пагинацией при пустом фильтре и нулевом размере страницы.' }),
    comment({ severity: 'critical', line: 12, body: 'Удаление колонки без переноса данных теряет историю блокировок навсегда.' }),
  ], { ...scope(), minSeverity: 'medium', maxInline: 1 });

  assert.deepEqual(plan.inline.map((c) => c.severity), ['critical']);
  assert.deepEqual(plan.extra.map((c) => c.severity), ['medium', 'low']);
});

test('planComments drops repeats of earlier threads, even resolved ones', () => {
  const plan = planComments([
    comment({ line: 12, body: CLOCK_SKEW_REWORDED }),
    comment({ line: 20, body: 'Миграция переносит locked_until, но не выставляет status для заблокированных пользователей.' }),
  ], { ...scope(), findings: [previousFinding({ isResolved: true })] });

  assert.equal(plan.duplicates.length, 1);
  assert.equal(plan.duplicates[0].previous.threadId, 'T1');
  assert.deepEqual(plan.inline.map((c) => c.line), [20]);
});

test('planComments drops repeats within the same run', () => {
  const plan = planComments([
    comment({ line: 10, body: CLOCK_SKEW }),
    comment({ line: 12, body: CLOCK_SKEW_REWORDED }),
  ], scope());

  assert.equal(plan.inline.length, 1);
  assert.equal(plan.duplicates.length, 1);
});

test('planComments ignores already reviewed code in an incremental review', () => {
  const plan = planComments([
    comment({ line: 10 }),
    comment({ line: 20, body: 'Новый код теряет исключение при откате транзакции и отдаёт клиенту успех.' }),
    comment({ path: 'src/Untouched.java', line: 3, body: 'Проблема в файле, который не менялся с прошлого ревью вовсе.' }),
    comment({ line: 99, body: 'Удалённая проверка прав оставила эндпоинт без авторизации совсем.' }),
  ], scope({ reviewable: [20] }));

  assert.deepEqual(plan.inline.map((c) => c.line), [20]);
  assert.deepEqual(plan.outOfScope.map((c) => `${c.path}:${c.line}`), ['src/A.java:10', 'src/Untouched.java:3']);
  assert.deepEqual(plan.extra.map((c) => c.line), [99]);
});

test('planComments keeps findings about code deleted since the last review', () => {
  const plan = planComments([
    comment({ line: 10, body: 'Удалённая проверка прав оставила эндпоинт без авторизации совсем.' }),
    comment({ line: 20, body: 'Старая проблема с пагинацией далеко от удалённого кода, уже проверялась.' }),
  ], { ...scope({ reviewable: [] }), deletionRanges: new Map([['src/A.java', [[12, 13]]]]) });

  assert.deepEqual(plan.extra.map((c) => c.line), [10]);
  assert.deepEqual(plan.outOfScope.map((c) => c.line), [20]);
});

test('classifyPreviousFindings trusts only explicit fixed statuses', () => {
  const findings = [
    previousFinding({ id: 'F1', severity: 'medium' }),
    previousFinding({ id: 'F2', severity: 'critical' }),
    previousFinding({ id: 'F3' }),
    previousFinding({ id: null }),
    previousFinding({ id: 'F4', isResolved: true }),
  ];

  const { open, fixed } = classifyPreviousFindings(findings, [
    { id: 'F1', status: 'fixed' },
    { id: 'F2', status: 'open' },
    { id: 'F4', status: 'fixed' },
  ]);

  assert.deepEqual(fixed.map((f) => f.id), ['F1']);
  assert.deepEqual(open.map((f) => f.id), ['F2', 'F3', null]);
  assert.deepEqual(open.map((f) => f.status), ['open', null, null]);
});

test('classifyPreviousFindings keeps earlier marks and the team\'s reopen decision', () => {
  const marked = '<!-- ai-pr-review:aa -->\n✅ _Похоже, исправлено._ <!-- ai-review-status -->\n\n🟠 **[high]** Проблема.';
  const { open, fixed } = classifyPreviousFindings([
    previousFinding({ id: null, threadId: 'beyond-cap', body: marked }),
    previousFinding({ id: 'F1', threadId: 'reopened', body: marked, reopened: true }),
    previousFinding({ id: 'F2', threadId: 'judged-open', body: marked }),
  ], [{ id: 'F1', status: 'fixed' }, { id: 'F2', status: 'open' }]);

  assert.deepEqual(fixed.map((f) => f.threadId), ['beyond-cap']);
  assert.deepEqual(open.map((f) => f.threadId), ['reopened', 'judged-open']);
});

test('renderSummary links open findings instead of repeating them', () => {
  const open = Array.from({ length: 12 }, (_, index) => previousFinding({
    id: `F${index + 1}`,
    url: `https://example.test/thread-${index + 1}`,
    replies: index === 0 ? [{ author: 'dev', body: 'Не согласен' }] : [],
  }));
  const body = renderSummary({
    review: {
      summary: 'PR меняет блокировку аккаунтов.',
      taskAlignment: 'Не реализован отзыв токенов из требований.',
      manualChecks: ['Проверить логин после блокировки'],
      tests: 'Есть юнит-тесты.',
    },
    plan: { inline: [comment()], extra: [comment({ severity: 'low', line: 11 })], duplicates: [{}], outOfScope: [] },
    open,
    fixed: [previousFinding({ id: 'F20', url: 'https://example.test/fixed' })],
    jira: { key: 'TAS-198', url: 'https://jira.example.dev/browse/TAS-198', summary: 'Залоченный аккаунт', status: 'In Review' },
    provider: 'deepseek',
    headSha: 'c'.repeat(40),
    mode: 'incremental',
    lastReviewedSha: 'd'.repeat(40),
    settings: { minSeverity: 'medium', maxInline: 8, autoResolve: false },
  });

  assert.equal(parseState(body).sha, 'c'.repeat(40));
  assert.match(body, /\*\*Задача:\*\* \[TAS-198\]\(https:\/\/jira\.example\.dev\/browse\/TAS-198\) — Залоченный аккаунт _\(In Review\)_/);
  assert.match(body, /### Соответствие задаче\nНе реализован отзыв токенов/);
  assert.match(body, /🟠 1 high — опубликованы в diff\./);
  assert.match(body, /<details><summary>Мелкие замечания \(1\)<\/summary>/);
  assert.match(body, /### Открытые замечания с прошлых ревью \(12\)/);
  assert.match(body, /\[`A\.java:11`\]\(https:\/\/example\.test\/thread-1\) — unlockIfExpired использует now\(\) БД.* 💬/);
  assert.match(body, /<details><summary>Ещё 2<\/summary>[\s\S]*thread-12/);
  assert.match(body, /### Похоже, исправлены \(1\)\nЕсли согласны — отметьте Resolve\./);
  assert.match(body, /изменения с `ddddddd` · отброшено повторов: 1/);
  assert.doesNotMatch(body, new RegExp(CLOCK_SKEW.slice(80, 120).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
});

async function setUpPublish(t, { review, findings = [], issueComments = [], failResolve = false, mode = 'incremental' }) {
  const repository = createRepository();
  t.after(repository.cleanup);
  const original = numberedLines(30);
  const baseSha = repository.commit('base', { 'src/A.java': original });
  repository.git('checkout', '--quiet', '-b', 'feature');
  const reviewedSha = repository.commit('first', { 'src/A.java': replaceLines(original, { 5: 'feature 5' }) });
  const headSha = repository.commit('second', {
    'src/A.java': replaceLines(original, { 5: 'feature 5', 20: 'feature 20' }),
  });

  fs.writeFileSync(path.join(repository.directory, 'codex-output.json'), JSON.stringify(review));
  fs.mkdirSync(path.join(repository.directory, '.ai-review'));
  fs.writeFileSync(path.join(repository.directory, '.ai-review', 'state.json'), JSON.stringify({
    mode,
    lastReviewedSha: mode === 'incremental' ? reviewedSha : null,
    pendingFiles: [],
    findings,
    jira: null,
  }));

  const github = createGithub({ issueComments, failResolve });
  return { repository, github, baseSha, headSha, reviewedSha };
}

test('publishReview links the Jira issue and the keys mentioned by the model', async (t) => {
  const review = {
    summary: 'PR закрывает TAS-252 и частично TAS-249.',
    manual_checks: ['Проверить переход TAS-252 в Done'],
    tests: 'Тесты есть.',
    previous_findings: [],
    comments: [{ severity: 'high', path: 'src/A.java', line: 20, body: 'Ломает сценарий из TAS-249: исключение теряется.' }],
  };
  const { repository, github, baseSha, headSha } = await setUpPublish(t, { review });
  const statePath = path.join(repository.directory, '.ai-review', 'state.json');
  const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  fs.writeFileSync(statePath, JSON.stringify({
    ...state,
    issue: { key: 'TAS-252', url: 'https://jira.example.dev/browse/TAS-252' },
    issueLinks: { browseUrl: 'https://jira.example.dev/browse/', projectKeys: ['TAS'] },
  }));

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha },
  }));

  const inline = github.calls.find(([name]) => name === 'pulls.createReview')[1].comments[0].body;
  assert.match(inline, /Ломает сценарий из \[TAS-249\]\(https:\/\/jira\.example\.dev\/browse\/TAS-249\)/);
  const summary = github.calls.at(-1)[1].body;
  assert.match(summary, /\*\*Задача:\*\* \[TAS-252\]\(https:\/\/jira\.example\.dev\/browse\/TAS-252\)\n/);
  assert.match(summary, /PR закрывает \[TAS-252\]\(https:\/\/jira\.example\.dev\/browse\/TAS-252\) и частично \[TAS-249\]/);
  assert.match(summary, /- Проверить переход \[TAS-252\]\(/);
});

test('renderSummary lists repeats of one finding as a single item', () => {
  const body = renderSummary({
    review: { summary: 's', taskAlignment: '', manualChecks: [], tests: 't' },
    plan: { inline: [], extra: [], duplicates: [], outOfScope: [] },
    open: [
      previousFinding({ id: 'F1', url: 'https://example.test/1' }),
      previousFinding({ id: 'F1', url: 'https://example.test/2', duplicateOf: 'T1', replies: [{ author: 'dev', body: '?' }] }),
      previousFinding({ id: null, threadId: 'T9', url: 'https://example.test/9' }),
    ],
    fixed: [],
    jira: null,
    provider: 'openai',
    headSha: 'c'.repeat(40),
    mode: 'full',
    lastReviewedSha: null,
    settings: { minSeverity: 'medium', maxInline: 8, autoResolve: false },
  });

  assert.match(body, /### Открытые замечания с прошлых ревью \(2\)/);
  assert.match(body, /\(https:\/\/example\.test\/1\) — .* 💬 · повторы: \[2\]\(https:\/\/example\.test\/2\)/);
  assert.match(body, /\(https:\/\/example\.test\/9\)/);
  assert.doesNotMatch(body, /Задача:/);
  assert.match(body, /полное ревью/);
});

test('publishReview posts new findings, marks fixed threads and stores the reviewed commit', async (t) => {
  const review = {
    summary: 'Итог.',
    task_alignment: '',
    manual_checks: [],
    comments: [
      { severity: 'high', path: 'src/A.java', line: 20, body: 'Новая ошибка: исключение при откате транзакции теряется и клиент получает успех.' },
      { severity: 'high', path: 'src/A.java', line: 20, body: CLOCK_SKEW_REWORDED },
      { severity: 'medium', path: 'src/A.java', line: 5, body: 'Старый код, который уже был проверен прошлым ревью и не менялся.' },
    ],
    previous_findings: [{ id: 'F1', status: 'fixed' }, { id: 'F2', status: 'open' }],
    tests: 'Тестов нет.',
  };
  const findings = [
    previousFinding({ id: 'F1', threadId: 'T1', commentId: 501, line: 21, text: 'Другая проблема про права доступа к проекту.', body: '<!-- ai-pr-review:aa -->\n🟠 **[high]** Другая проблема про права доступа к проекту.' }),
    previousFinding({
      id: 'F2',
      threadId: 'T2',
      commentId: 502,
      line: 19,
      body: `<!-- ai-pr-review:bb -->\n<!-- ai-review-status --> ✅ _Похоже, исправлено._\n\n🟠 **[high]** ${CLOCK_SKEW}`,
    }),
  ];
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings,
    issueComments: [{ id: 42, user: { type: 'Bot' }, body: '<!-- codex-pr-review -->\nold' }],
  });
  const core = createCore();

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core,
    env: { REVIEW_PROVIDER: 'deepseek', PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha },
  }));

  const names = github.calls.map(([name]) => name);
  assert.deepEqual(names, [
    'pulls.createReview',
    'pulls.updateReviewComment',
    'pulls.updateReviewComment',
    'issues.updateComment',
  ]);

  const [, createReview] = github.calls[0];
  assert.equal(createReview.commit_id, headSha);
  assert.equal(createReview.comments.length, 1);
  assert.equal(createReview.comments[0].line, 20);
  assert.match(createReview.comments[0].body, /^<!-- ai-pr-review:[0-9a-f]{20} -->\n🟠 \*\*\[high\]\*\* Новая ошибка/);

  const [, markFixed] = github.calls[1];
  assert.equal(markFixed.comment_id, 501);
  assert.match(markFixed.body, /✅ _Похоже, исправлено в `[0-9a-f]{7}` — можно отметить Resolve\._ <!-- ai-review-status -->/);

  const [, clearStale] = github.calls[2];
  assert.equal(clearStale.comment_id, 502);
  assert.doesNotMatch(clearStale.body, /ai-review-status/);

  const [, summary] = github.calls[3];
  assert.equal(summary.comment_id, 42);
  assert.equal(parseState(summary.body).sha, headSha);
  assert.match(summary.body, /### Открытые замечания с прошлых ревью \(1\)/);
  assert.match(summary.body, /### Похоже, исправлены \(1\)/);
  assert.match(core.infos.join('\n'), /1 inline, 0 in summary, 1 duplicates dropped, 1 outside the review scope dropped/);
});

test('publishReview resolves fixed threads when auto-resolve is enabled', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], comments: [], tests: 'Ок.',
    previous_findings: [{ id: 'F1', status: 'fixed' }],
  };
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding()],
  });

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha, AI_REVIEW_AUTO_RESOLVE: 'true' },
  }));

  assert.deepEqual(github.calls.map(([name]) => name), ['graphql', 'pulls.updateReviewComment', 'issues.createComment']);
  assert.deepEqual(github.calls[0][1], { threadId: 'T1' });
  assert.match(github.calls[1][1].body, /тред закрыт автоматически/);
  assert.match(github.calls[2][1].body, /Треды закрыты автоматически/);
});

test('publishReview keeps going when a thread cannot be resolved', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], comments: [], tests: 'Ок.',
    previous_findings: [{ id: 'F1', status: 'fixed' }],
  };
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding()],
    failResolve: true,
  });
  const core = createCore();

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core,
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha, AI_REVIEW_AUTO_RESOLVE: 'true' },
  }));

  assert.equal(core.warnings.length, 1);
  assert.match(github.calls[1][1].body, /можно отметить Resolve/);
  assert.equal(github.calls.at(-1)[0], 'issues.createComment');
  assert.match(github.calls.at(-1)[1].body, /Если согласны — отметьте Resolve/);
});

test('publishReview keeps marks of findings the model did not judge', async (t) => {
  const review = { summary: 'Итог.', manual_checks: [], comments: [], tests: 'Ок.', previous_findings: [] };
  const markedBody = '<!-- ai-pr-review:aa -->\n✅ _Похоже, исправлено._ <!-- ai-review-status -->\n\n🟠 **[high]** Проблема.';
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding({ id: null, body: markedBody }), previousFinding({ id: 'F2', body: markedBody })],
  });

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha },
  }));

  assert.deepEqual(github.calls.map(([name]) => name), ['issues.createComment']);
});

test('publishReview retries a rejected review one finding at a time', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], tests: 'Ок.', previous_findings: [],
    comments: [
      { severity: 'high', path: 'src/A.java', line: 20, body: 'Новая ошибка: исключение при откате транзакции теряется.' },
      { severity: 'critical', path: 'src/A.java', line: 5, body: 'SQL-инъекция через параметр сортировки, который подставляется в запрос.' },
    ],
  };
  const { repository, baseSha, headSha } = await setUpPublish(t, { review, mode: 'full' });
  const github = createGithub({ rejectLines: [5] });
  const core = createCore();

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core,
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha },
  }));

  const reviews = github.calls.filter(([name]) => name === 'pulls.createReview').map(([, params]) => params.comments.map((c) => c.line));
  assert.deepEqual(reviews, [[5, 20], [5], [20]]);
  const summary = github.calls.at(-1)[1].body;
  assert.match(summary, /🟠 1 high — опубликованы в diff/);
  // The rejected critical finding is shown in full, not collapsed.
  assert.match(summary, /🔴 1 critical — только здесь[^\n]*\n\n- 🔴 \*\*\[critical\]\*\* `src\/A\.java:5` — SQL-инъекция[^\n]*GitHub не принял/);
  assert.match(core.warnings[0], /retrying the findings one by one/);
});

test('renderSummary lists files cut from the diff and does not claim "no findings" when some are summary-only', () => {
  const body = renderSummary({
    review: { summary: 's', taskAlignment: '', manualChecks: [], tests: 't' },
    plan: { inline: [], extra: [comment({ severity: 'high', line: 99 })], duplicates: [], outOfScope: [] },
    open: [],
    fixed: [],
    jira: null,
    provider: 'deepseek',
    headSha: 'c'.repeat(40),
    unreviewedFiles: ['src/Big.java'],
    mode: 'incremental',
    lastReviewedSha: 'd'.repeat(40),
    settings: { minSeverity: 'medium', maxInline: 8, autoResolve: false },
  });

  assert.doesNotMatch(body, /Новых замечаний к изменённым строкам нет/);
  assert.match(body, /🟠 1 high — только здесь/);
  assert.match(body, /1 файлов не проверены[\s\S]*- `src\/Big\.java`/);
  assert.deepEqual(parseState(body), { sha: 'c'.repeat(40) });
});

test('publishReview keeps a finding open when the model calls it fixed but re-reports it nearby', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], tests: 'Ок.',
    previous_findings: [{ id: 'F1', status: 'fixed' }],
    comments: [{ severity: 'high', path: 'src/A.java', line: 20, body: CLOCK_SKEW_REWORDED }],
  };
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding({ line: 18 })],
  });

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha, AI_REVIEW_AUTO_RESOLVE: 'true' },
  }));

  // No resolve, no ✅ mark: the thread stays open and is listed as such.
  assert.deepEqual(github.calls.map(([name]) => name), ['issues.createComment']);
  assert.match(github.calls[0][1].body, /### Открытые замечания с прошлых ревью \(1\)/);
});

test('publishReview records the auto-resolve on a thread that already has a ✅ mark', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], comments: [], tests: 'Ок.',
    previous_findings: [{ id: 'F1', status: 'fixed' }],
  };
  const marked = '<!-- ai-pr-review:aa -->\n✅ _Похоже, исправлено в `abc1234` — можно отметить Resolve._ <!-- ai-review-status -->\n\n🟠 **[high]** Проблема.';
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding({ body: marked })],
  });

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha, AI_REVIEW_AUTO_RESOLVE: 'true' },
  }));

  assert.deepEqual(github.calls.map(([name]) => name), ['graphql', 'pulls.updateReviewComment', 'issues.createComment']);
  assert.match(github.calls[1][1].body, /тред закрыт автоматически/);
  assert.equal(github.calls[1][1].body.match(/ai-review-status/g).length, 1);
});

test('publishReview never re-resolves a thread the team reopened', async (t) => {
  const review = {
    summary: 'Итог.', manual_checks: [], comments: [], tests: 'Ок.',
    previous_findings: [{ id: 'F1', status: 'fixed' }],
  };
  const closedByBot = '<!-- ai-pr-review:aa -->\n✅ _Похоже, исправлено в `abc1234` — тред закрыт автоматически._ <!-- ai-review-status -->\n\n🟠 **[high]** Проблема.';
  const { repository, github, baseSha, headSha } = await setUpPublish(t, {
    review,
    findings: [previousFinding({ body: closedByBot, reopened: true })],
  });

  await inDirectory(repository.directory, () => publishReview({
    github,
    context: createContext(),
    core: createCore(),
    env: { PR_BASE_SHA: baseSha, PR_HEAD_SHA: headSha, AI_REVIEW_AUTO_RESOLVE: 'true' },
  }));

  assert.deepEqual(github.calls.map(([name]) => name), ['pulls.updateReviewComment', 'issues.createComment']);
  assert.match(github.calls[0][1].body, /↩️ _Тред переоткрыт вручную — бот больше не закрывает его сам\._ <!-- ai-review-status -->/);
  assert.match(github.calls[1][1].body, /### Открытые замечания с прошлых ревью \(1\)/);
});
