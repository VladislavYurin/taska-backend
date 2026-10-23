const assert = require('node:assert/strict');
const test = require('node:test');

const {
  collectAddedLines,
  computeReviewScope,
  diffChangedFiles,
  findSimilarFinding,
  formatRanges,
  parseInlineBody,
  parseState,
  parseUnifiedDiff,
  renderState,
  setStatusLine,
  similarity,
  unquoteGitPath,
} = require('./review-shared');
const { createRepository, inDirectory, numberedLines, replaceLines } = require('./git-fixture');

const SHA = 'a'.repeat(40);

test('collectAddedLines returns only RIGHT-side added lines', () => {
  const diff = [
    'diff --git a/src/Foo.java b/src/Foo.java',
    '--- a/src/Foo.java',
    '+++ b/src/Foo.java',
    '@@ -10,2 +10,3 @@',
    '-old line',
    '+new line',
    ' context',
    '+another new line',
  ].join('\n');

  const addedLines = collectAddedLines(diff);

  assert.deepEqual([...addedLines.get('src/Foo.java')], [10, 12]);
});

test('collectAddedLines normalises paths with spaces and quoted characters', () => {
  const diff = [
    'diff --git a/src/my file.java b/src/my file.java',
    '--- a/src/my file.java\t',
    '+++ b/src/my file.java\t',
    '@@ -2,0 +3 @@',
    '+added',
    'diff --git "a/src/we\\"ird.java" "b/src/we\\"ird.java"',
    '--- "a/src/we\\"ird.java"',
    '+++ "b/src/we\\"ird.java"',
    '@@ -0,0 +1 @@',
    '+added',
  ].join('\n');

  const addedLines = collectAddedLines(diff);

  assert.deepEqual([...addedLines.keys()], ['src/my file.java', 'src/we"ird.java']);
  assert.equal(unquoteGitPath('"b/\\321\\204\\320\\260\\320\\271\\320\\273.txt"'), 'b/файл.txt');
  assert.equal(unquoteGitPath('b/plain.txt'), 'b/plain.txt');
});

test('parseUnifiedDiff locates deleted code and is not fooled by deleted SQL comments', () => {
  const diff = [
    'diff --git a/db/0001.sql b/db/0001.sql',
    '--- a/db/0001.sql',
    '+++ b/db/0001.sql',
    '@@ -3,2 +2,0 @@',
    '--- old comment',
    '-DROP TABLE users;',
    'diff --git a/gone.txt b/gone.txt',
    '--- a/gone.txt',
    '+++ /dev/null',
    '@@ -1 +0,0 @@',
    '-bye',
    'diff --git a/src/A.java b/src/A.java',
    '--- a/src/A.java',
    '+++ b/src/A.java',
    '@@ -1,0 +2 @@',
    '+++counter;',
    '@@ -9 +10 @@',
    '-edited',
    '+edited again',
  ].join('\n');

  const { addedLines, deletionRanges } = parseUnifiedDiff(diff);

  assert.deepEqual([...deletionRanges.entries()].sort(), [['db/0001.sql', [[2, 3]]], ['gone.txt', [[0, 1]]]]);
  assert.deepEqual([...addedLines.get('db/0001.sql')], []);
  assert.deepEqual([...addedLines.get('src/A.java')], [2, 10]);
  // An edited line is not deleted code.
  assert.equal(deletionRanges.has('src/A.java'), false);
});

test('formatRanges collapses consecutive lines', () => {
  assert.equal(formatRanges(new Set([12, 3, 4, 5, 10, 11])), '3-5, 10-12');
  assert.equal(formatRanges(new Set([7])), '7');
});

test('review state round-trips through the summary comment', () => {
  const body = `<!-- codex-pr-review -->\n${renderState({ sha: SHA })}\n## AI`;

  assert.deepEqual(parseState(body), { sha: SHA });
  assert.equal(parseState('<!-- codex-pr-review -->'), null);
  assert.equal(parseState('<!-- ai-review-state:{"sha":"not-a-sha"} -->'), null);
  assert.equal(parseState('<!-- ai-review-state:{broken -->'), null);
});

test('parseInlineBody strips markers, status line and severity', () => {
  const body = [
    '<!-- ai-pr-review:0123456789abcdef0123 -->',
    '<!-- ai-review-status --> ✅ _Похоже, исправлено._',
    '',
    '🟠 **[high]** Гонка при обновлении статуса.',
  ].join('\n');

  assert.deepEqual(parseInlineBody(body), { severity: 'high', text: 'Гонка при обновлении статуса.' });
  assert.deepEqual(parseInlineBody('<!-- ai-pr-review:ab -->\n**[low]** Мелочь.'), { severity: 'low', text: 'Мелочь.' });
  assert.equal(
    parseInlineBody('<!-- ai-pr-review:ab -->\n**[high]** Нарушает [TAS-1](https://jira.example.dev/browse/TAS-1).').text,
    'Нарушает TAS-1.'
  );
});

test('setStatusLine adds, replaces and removes the status line', () => {
  const original = '<!-- ai-pr-review:abc123 -->\n🟠 **[high]** Гонка.';

  const marked = setStatusLine(original, '✅ fixed in 1234567');
  assert.match(marked, /^<!-- ai-pr-review:abc123 -->\n✅ fixed in 1234567 <!-- ai-review-status -->\n\n🟠/);

  const remarked = setStatusLine(marked, '✅ fixed in 7654321');
  assert.equal(remarked.match(/ai-review-status/g).length, 1);
  assert.match(remarked, /7654321/);

  assert.equal(setStatusLine(marked, null), original);
});

// Real repeats published by the old workflow on PR #174.
const CLOCK_SKEW_V1 = '`unlockIfExpired` использует `now()` из Postgres, тогда как `AccountLockService.resolveLockState` сравнивает `lockedUntil` с `Instant.now(clock)` приложения. При рассинхронизации часов возможна ситуация, когда сервис считает окно истёкшим, а UPDATE не срабатывает.';
const CLOCK_SKEW_V2 = 'unlockIfExpired использует now() БД, а AccountLockService сравнивает lockedUntil с Instant.now() JVM. При рассинхроне часов БД и приложения возможны ситуации, когда сервис считает окно истёкшим, а UPDATE по условию locked_until < now() не срабатывает.';
const MIGRATION_ROLLBACK = 'Миграция не имеет rollback-блока. При откате деплоя колонка credentials.locked_until уже удалена, а данные перенесены в users — без rollback восстановить исходное состояние невозможно.';

test('similarity recognises a reworded repeat of the same finding', () => {
  assert.ok(similarity(CLOCK_SKEW_V1, CLOCK_SKEW_V2) >= 0.6);
  assert.ok(similarity(CLOCK_SKEW_V1, MIGRATION_ROLLBACK) < 0.3);
  assert.equal(similarity('Коротко.', CLOCK_SKEW_V1), 0);
});

test('findSimilarFinding matches by file, wording and distance', () => {
  const previous = [
    { path: 'auth/UserRepository.java', line: 57, body: CLOCK_SKEW_V1 },
    { path: 'auth/0004-user-locked-until.sql', line: 17, body: MIGRATION_ROLLBACK },
  ];

  const repeat = { path: 'auth/UserRepository.java', line: 60, body: CLOCK_SKEW_V2 };
  assert.equal(findSimilarFinding(repeat, previous), previous[0]);

  const otherFile = { path: 'auth/AccountLockService.java', line: 60, body: CLOCK_SKEW_V2 };
  assert.equal(findSimilarFinding(otherFile, previous), null);

  const differentProblem = {
    path: 'auth/UserRepository.java',
    line: 58,
    body: 'Метод findByEmail не экранирует символы подстановки LIKE, поэтому поиск по адресу с процентом вернёт чужие аккаунты.',
  };
  assert.equal(findSimilarFinding(differentProblem, previous), null);

  // Same-class bugs in different methods read alike but are different findings.
  const getIssue = { path: 'svc/Service.java', line: 40, body: 'Метод getIssue вызывает issueRepository.findById(id).get() без проверки, при отсутствии записи будет NoSuchElementException вместо IssueNotFoundException.' };
  const getProject = { path: 'svc/Service.java', line: 120, body: 'Метод getProject вызывает projectRepository.findById(id).get() без проверки, при отсутствии записи будет NoSuchElementException вместо ProjectNotFoundException.' };
  assert.equal(findSimilarFinding(getProject, [getIssue]), null);

  // Outdated threads have no current line; the original line is used instead.
  const outdated = [{ path: 'auth/UserRepository.java', line: null, originalLine: 61, body: CLOCK_SKEW_V1 }];
  assert.equal(findSimilarFinding(repeat, outdated), outdated[0]);
});

test('computeReviewScope narrows an incremental review to lines changed since the last review', async (t) => {
  const repository = createRepository();
  t.after(repository.cleanup);
  const original = numberedLines(30);
  const baseSha = repository.commit('base', { 'src/A.java': original, 'src/B.java': original });

  repository.git('checkout', '--quiet', '-b', 'feature');
  const firstEdit = replaceLines(original, { 5: 'feature 5', 6: 'feature 6' });
  const reviewedSha = repository.commit('first', { 'src/A.java': firstEdit });
  const headSha = repository.commit('second', {
    'src/A.java': replaceLines(firstEdit, { 20: 'feature 20' }),
    'src/B.java': replaceLines(original, { 3: 'feature 3' }),
  });

  await inDirectory(repository.directory, () => {
    const full = computeReviewScope({ baseSha, headSha, lastReviewedSha: null });
    assert.equal(full.mode, 'full');
    assert.deepEqual([...full.reviewableLines.get('src/A.java')], [5, 6, 20]);

    const incremental = computeReviewScope({ baseSha, headSha, lastReviewedSha: reviewedSha });
    assert.equal(incremental.mode, 'incremental');
    assert.deepEqual([...incremental.reviewableLines.get('src/A.java')], [20]);
    assert.deepEqual([...incremental.reviewableLines.get('src/B.java')], [3]);
    assert.deepEqual([...incremental.prAddedLines.get('src/A.java')], [5, 6, 20]);
    assert.deepEqual([...incremental.touchedFiles].sort(), ['src/A.java', 'src/B.java']);

    // A re-run of the workflow for an older head after a newer one was reviewed.
    assert.equal(computeReviewScope({ baseSha, headSha, lastReviewedSha: headSha }).mode, 'stale');
    assert.equal(computeReviewScope({ baseSha, headSha: reviewedSha, lastReviewedSha: headSha }).mode, 'stale');
  });
});

test('computeReviewScope keeps deletions and reverts in an incremental review', async (t) => {
  const repository = createRepository();
  t.after(repository.cleanup);
  const original = numberedLines(30);
  const baseSha = repository.commit('base', { 'src/A.java': original, 'src/B.java': original });
  repository.git('checkout', '--quiet', '-b', 'feature');
  const reviewedSha = repository.commit('first', {
    'src/A.java': replaceLines(original, { 5: 'feature 5', 20: 'feature 20' }),
    'src/B.java': replaceLines(original, { 3: 'feature 3' }),
    'src/New.java': 'new file\n',
  });
  const headSha = repository.commit('second', {
    'src/A.java': replaceLines(original, { 5: 'feature 5', 20: 'feature 20' }).replace('line 10\n', ''),
    'src/B.java': original,
    'src/New.java': null,
  });

  await inDirectory(repository.directory, () => {
    const scope = computeReviewScope({ baseSha, headSha, lastReviewedSha: reviewedSha });
    assert.equal(scope.mode, 'incremental');
    assert.equal(scope.reviewableLines.size, 0);
    assert.deepEqual([...scope.changedFiles].sort(), ['src/A.java', 'src/B.java', 'src/New.java']);
    assert.deepEqual([...scope.touchedFiles], ['src/A.java']);
    // "line 10" was removed: the gap sits between new lines 9 and 10.
    assert.deepEqual([...scope.deletionRanges.entries()], [['src/A.java', [[9, 10]]]]);
  });
});

test('diffChangedFiles returns unusual paths unquoted', async (t) => {
  const repository = createRepository();
  t.after(repository.cleanup);
  const baseSha = repository.commit('base', { 'README.md': 'x\n' });
  const headSha = repository.commit('spaces', { 'src/my file.java': 'x\n', 'src/файл.java': 'x\n' });

  await inDirectory(repository.directory, () => {
    assert.deepEqual([...diffChangedFiles(`${baseSha}...${headSha}`)].sort(), ['src/my file.java', 'src/файл.java']);
  });
});

test('computeReviewScope falls back to a full review after a history rewrite', async (t) => {
  const repository = createRepository();
  t.after(repository.cleanup);
  const original = numberedLines(10);
  const baseSha = repository.commit('base', { 'src/A.java': original });
  repository.git('checkout', '--quiet', '-b', 'feature');
  const rewrittenSha = repository.commit('old', { 'src/A.java': replaceLines(original, { 2: 'old' }) });
  repository.git('reset', '--quiet', '--hard', baseSha);
  const headSha = repository.commit('new', { 'src/A.java': replaceLines(original, { 2: 'new' }) });

  await inDirectory(repository.directory, () => {
    assert.equal(computeReviewScope({ baseSha, headSha, lastReviewedSha: rewrittenSha }).mode, 'full');
    assert.equal(computeReviewScope({ baseSha, headSha, lastReviewedSha: 'b'.repeat(40) }).mode, 'full');
  });
});

test('computeReviewScope ignores base branch changes merged into the PR', async (t) => {
  const repository = createRepository();
  t.after(repository.cleanup);
  const original = numberedLines(10);
  repository.commit('base', { 'src/A.java': original, 'src/Other.java': original });
  repository.git('checkout', '--quiet', '-b', 'feature');
  const reviewedSha = repository.commit('feature', { 'src/A.java': replaceLines(original, { 2: 'feature' }) });
  repository.git('checkout', '--quiet', 'develop');
  const baseSha = repository.commit('develop moves on', { 'src/Other.java': replaceLines(original, { 4: 'develop' }) });
  repository.git('checkout', '--quiet', 'feature');
  repository.git('merge', '--quiet', '--no-edit', 'develop');
  const headSha = repository.git('rev-parse', 'HEAD');

  await inDirectory(repository.directory, () => {
    const scope = computeReviewScope({ baseSha, headSha, lastReviewedSha: reviewedSha });
    assert.equal(scope.mode, 'incremental');
    assert.equal(scope.touchedFiles.size, 0);
    assert.equal(scope.reviewableLines.size, 0);
  });
});
