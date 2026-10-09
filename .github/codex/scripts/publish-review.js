const crypto = require('node:crypto');
const fs = require('node:fs');

const {
  AUTO_RESOLVED_NOTE,
  MAX_NOTES,
  MAX_NOTE_LENGTH,
  REOPENED_NOTE,
  REVIEW_MARKER,
  SEVERITIES,
  SEVERITY_ICONS,
  STATE_FILE,
  STATUS_LINE_MARKER,
  TRUNCATED_FILE,
  basename,
  collectAddedLines,
  computeReviewScope,
  findSimilarFinding,
  findSummaryComment,
  firstSentence,
  isAtLeast,
  renderState,
  setStatusLine,
  severityRank,
} = require('./review-shared');

const OUTPUT_FILE = 'codex-output.json';
const MAX_MODEL_COMMENTS = 20;
const DEFAULT_MAX_INLINE = 8;
const DEFAULT_MIN_SEVERITY = 'medium';
const LISTED_FINDINGS = 10;
const FINDING_ID_PATTERN = /^F\d+$/;
const NEAR_DELETION_LINES = 3;
const PREVIOUS_STATUSES = new Set(['open', 'fixed']);

const RESOLVE_THREAD_MUTATION = `
mutation($threadId: ID!) {
  resolveReviewThread(input: { threadId: $threadId }) { thread { id isResolved } }
}`;

function requireText(value, field, maxLength = 6000) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Review field '${field}' must be a non-empty string.`);
  }

  return value.trim().slice(0, maxLength);
}

function optionalText(value, field, maxLength = 3000) {
  if (value === undefined || value === null) {
    return '';
  }
  if (typeof value !== 'string') {
    throw new Error(`Review field '${field}' must be a string.`);
  }
  return value.trim().slice(0, maxLength);
}

function parseReview(rawReview) {
  const review = JSON.parse(rawReview);
  if (!review || typeof review !== 'object' || Array.isArray(review)) {
    throw new Error('Review output must be a JSON object.');
  }

  if (!Array.isArray(review.manual_checks) || !Array.isArray(review.comments)) {
    throw new Error("Review fields 'manual_checks' and 'comments' must be arrays.");
  }

  const comments = review.comments.slice(0, MAX_MODEL_COMMENTS).map((comment, index) => {
    if (!comment || typeof comment !== 'object' || Array.isArray(comment)) {
      throw new Error(`Review comment ${index + 1} must be an object.`);
    }

    const severity = requireText(comment.severity, `comments[${index}].severity`);
    if (!SEVERITIES.includes(severity)) {
      throw new Error(`Unsupported severity '${severity}' in review comment ${index + 1}.`);
    }

    const path = requireText(comment.path, `comments[${index}].path`, 300);
    if (path.includes('\n') || path.startsWith('/') || path.split('/').includes('..')) {
      throw new Error(`Unsafe path '${path}' in review comment ${index + 1}.`);
    }

    if (!Number.isInteger(comment.line) || comment.line < 1) {
      throw new Error(`Review comment ${index + 1} must have a positive integer line.`);
    }

    return {
      severity,
      path,
      line: comment.line,
      body: requireText(comment.body, `comments[${index}].body`, 1200),
    };
  });

  // Statuses of earlier findings are advisory: malformed entries are ignored
  // and the finding is then treated as still open.
  const previousFindings = (Array.isArray(review.previous_findings) ? review.previous_findings : [])
    .filter((entry) =>
      entry
      && typeof entry.id === 'string'
      && FINDING_ID_PATTERN.test(entry.id)
      && PREVIOUS_STATUSES.has(entry.status)
    )
    .map((entry) => ({ id: entry.id, status: entry.status }));

  return {
    summary: requireText(review.summary, 'summary'),
    taskAlignment: optionalText(review.task_alignment, 'task_alignment'),
    manualChecks: review.manual_checks.slice(0, 10).map((check, index) =>
      requireText(check, `manual_checks[${index}]`, 300)
    ),
    comments,
    previousFindings,
    tests: requireText(review.tests, 'tests'),
  };
}

function readSettings(env) {
  const minSeverity = SEVERITIES.includes(env.AI_REVIEW_INLINE_MIN_SEVERITY)
    ? env.AI_REVIEW_INLINE_MIN_SEVERITY
    : DEFAULT_MIN_SEVERITY;
  const maxInline = /^\d+$/.test(env.AI_REVIEW_MAX_INLINE ?? '')
    ? Math.min(Number(env.AI_REVIEW_MAX_INLINE), MAX_MODEL_COMMENTS)
    : DEFAULT_MAX_INLINE;
  return { minSeverity, maxInline, autoResolve: env.AI_REVIEW_AUTO_RESOLVE === 'true' };
}

/**
 * Decides where each new finding goes:
 * - `inline`: a review comment on a line in scope, severe enough, under the cap;
 * - `extra`: listed in the summary (below the severity threshold, over the cap,
 *   or not anchored to an added line of a file in scope);
 * - `duplicates`: repeats an earlier thread or another finding of this run;
 * - `outOfScope`: targets code that an earlier review already covered.
 */
function planComments(comments, {
  prAddedLines,
  reviewableLines,
  touchedFiles,
  deletionRanges = new Map(),
  findings = [],
  minSeverity = DEFAULT_MIN_SEVERITY,
  maxInline = DEFAULT_MAX_INLINE,
}) {
  const plan = { inline: [], extra: [], duplicates: [], outOfScope: [] };
  const accepted = [];
  const usedAnchors = new Set();
  const bySeverity = [...comments].sort((a, b) => severityRank(a.severity) - severityRank(b.severity));

  for (const comment of bySeverity) {
    const previous = findSimilarFinding(comment, findings);
    if (previous || findSimilarFinding(comment, accepted)) {
      plan.duplicates.push({ comment, previous });
      continue;
    }

    const inScope = reviewableLines.get(comment.path)?.has(comment.line) ?? false;
    const onPrLine = prAddedLines.get(comment.path)?.has(comment.line) ?? false;
    // A line reviewed earlier is out of scope, unless code next to it was
    // deleted since: the finding may be about that deletion.
    const nearDeletion = (deletionRanges.get(comment.path) ?? []).some(([start, end]) =>
      comment.line >= start - NEAR_DELETION_LINES && comment.line <= end + NEAR_DELETION_LINES
    );
    const reviewedEarlier = onPrLine && !nearDeletion;
    if (!inScope && (reviewedEarlier || !touchedFiles.has(comment.path))) {
      plan.outOfScope.push(comment);
      continue;
    }

    accepted.push(comment);
    const anchor = `${comment.path}:${comment.line}`;
    if (
      inScope
      && !usedAnchors.has(anchor)
      && isAtLeast(comment.severity, minSeverity)
      && plan.inline.length < maxInline
    ) {
      plan.inline.push(comment);
      usedAnchors.add(anchor);
    } else {
      plan.extra.push(comment);
    }
  }

  return plan;
}

/**
 * Splits unresolved earlier findings into still open and fixed. `status` keeps
 * the model's verdict: null when the finding was not in the prompt or the
 * model skipped it; such findings keep the mark an earlier run gave them.
 * A thread the team reopened after the bot closed it stays open.
 */
function classifyPreviousFindings(findings, statuses) {
  const statusById = new Map(statuses.map((entry) => [entry.id, entry.status]));
  const open = [];
  const fixed = [];
  for (const finding of findings) {
    if (finding.isResolved) {
      continue;
    }
    finding.status = finding.id ? statusById.get(finding.id) ?? null : null;
    const markedFixed = String(finding.body ?? '').includes(STATUS_LINE_MARKER);
    if (!finding.reopened && (finding.status === 'fixed' || (finding.status === null && markedFixed))) {
      fixed.push(finding);
    } else {
      open.push(finding);
    }
  }
  const bySeverity = (a, b) => severityRank(a.severity) - severityRank(b.severity);
  return { open: open.sort(bySeverity), fixed: fixed.sort(bySeverity) };
}

function inlineMarker(provider, commitSha, comment) {
  const digest = crypto
    .createHash('sha256')
    .update(`${provider}\0${commitSha}\0${comment.path}\0${comment.line}`)
    .digest('hex')
    .slice(0, 20);
  return `<!-- ai-pr-review:${digest} -->`;
}

function severityTally(comments) {
  return SEVERITIES
    .map((severity) => [severity, comments.filter((comment) => comment.severity === severity).length])
    .filter(([, count]) => count > 0)
    .map(([severity, count]) => `${SEVERITY_ICONS[severity]} ${count} ${severity}`)
    .join(' · ');
}

function findingLink(finding) {
  const line = finding.line ?? finding.originalLine;
  const label = `${basename(finding.path)}${line ? `:${line}` : ''}`;
  return finding.url ? `[\`${label}\`](${finding.url})` : `\`${label}\``;
}

/** Groups copies of one finding (they share the prompt id) under its first thread. */
function groupRepeats(findings) {
  const groups = new Map();
  for (const finding of findings) {
    const key = finding.id ?? `thread:${finding.threadId}`;
    if (groups.has(key)) {
      groups.get(key).repeats.push(finding);
    } else {
      groups.set(key, { head: finding, repeats: [] });
    }
  }
  return [...groups.values()];
}

function renderFindingItem({ head, repeats }) {
  const replies = [head, ...repeats].some((finding) => finding.replies?.length > 0) ? ' 💬' : '';
  const repeatLinks = repeats.length > 0
    ? ` · повторы: ${repeats.map((finding, index) => (finding.url ? `[${index + 2}](${finding.url})` : `${index + 2}`)).join(' ')}`
    : '';
  return `- ${SEVERITY_ICONS[head.severity] ?? ''} ${findingLink(head)} — ${firstSentence(head.text)}${replies}${repeatLinks}`;
}

function renderCollapsibleList(items, renderItem, summaryLabel) {
  const shown = items.slice(0, LISTED_FINDINGS).map(renderItem);
  const hidden = items.slice(LISTED_FINDINGS).map(renderItem);
  if (hidden.length === 0) {
    return shown;
  }
  return [...shown, '', `<details><summary>${summaryLabel(hidden.length)}</summary>`, '', ...hidden, '', '</details>'];
}

function renderDetails(summary, lines) {
  return ['', `<details><summary>${summary}</summary>`, '', ...lines, '', '</details>'];
}

function renderSummary({
  review,
  plan,
  open,
  fixed,
  jira,
  provider,
  headSha,
  pending = [],
  notes = [],
  mode,
  lastReviewedSha,
  settings,
}) {
  const lines = [
    REVIEW_MARKER,
    renderState({ sha: headSha, pending, notes }),
    '## 🤖 AI-ревью',
  ];

  if (jira) {
    const status = jira.status ? ` _(${jira.status})_` : '';
    lines.push('', `**Задача:** [${jira.key}](${jira.url}) — ${jira.summary}${status}`);
  }

  lines.push('', '### Кратко', review.summary);

  if (jira && review.taskAlignment) {
    lines.push('', '### Соответствие задаче', review.taskAlignment);
  }

  lines.push('', '### Новые замечания');
  lines.push(plan.inline.length > 0
    ? `${severityTally(plan.inline)} — опубликованы в diff.`
    : mode === 'incremental'
      ? 'Новых замечаний к изменённым строкам нет.'
      : 'Замечаний к строкам нет.');
  if (plan.extra.length > 0) {
    lines.push(...renderDetails(
      `Ещё ${plan.extra.length} без inline-комментария (ниже ${settings.minSeverity}, сверх лимита или вне добавленных строк)`,
      plan.extra.map((comment) =>
        `- ${SEVERITY_ICONS[comment.severity]} **[${comment.severity}]** \`${comment.path}:${comment.line}\` — ${comment.body.replace(/\s*\n\s*/g, ' ')}`
      )
    ));
  }

  if (open.length > 0) {
    const groups = groupRepeats(open);
    lines.push(
      '',
      `### Открытые замечания с прошлых ревью (${groups.length})`,
      'Всё ещё актуальны. Если не согласны — ответьте в треде и отметьте Resolve: повторно бот их не поднимет.',
      '',
      ...renderCollapsibleList(groups, renderFindingItem, (count) => `Ещё ${count}`)
    );
  }

  if (fixed.length > 0) {
    const groups = groupRepeats(fixed);
    const autoResolved = fixed.filter((finding) => finding.autoResolved).length;
    const resolvedNote = autoResolved === fixed.length
      ? 'Треды закрыты автоматически; если это не так — переоткройте.'
      : autoResolved > 0
        ? 'Часть тредов закрыта автоматически; остальные отметьте Resolve, если согласны.'
        : 'Если согласны — отметьте Resolve.';
    lines.push(
      '',
      `### Похоже, исправлены (${groups.length})`,
      resolvedNote,
      '',
      ...renderCollapsibleList(groups, renderFindingItem, (count) => `Ещё ${count}`)
    );
  }

  if (review.manualChecks.length > 0) {
    lines.push(...renderDetails(
      `Что проверить вручную (${review.manualChecks.length})`,
      review.manualChecks.map((check) => `- ${check}`)
    ));
  }

  lines.push(...renderDetails('Тесты', [review.tests]));

  const scopeNote = mode === 'incremental' && lastReviewedSha
    ? `изменения с \`${lastReviewedSha.slice(0, 7)}\``
    : 'полное ревью';
  const duplicatesNote = plan.duplicates.length > 0
    ? ` · отброшено повторов: ${plan.duplicates.length}`
    : '';
  if (pending.length > 0) {
    lines.push('', `> ⚠️ Diff не поместился в лимит модели: ${pending.length} файлов не попали в ревью и будут проверены при следующем пуше.`);
  }
  lines.push(
    '',
    '---',
    `<sub>Авто-ревью (${provider}) · коммит \`${headSha.slice(0, 7)}\` · ${scopeNote}${duplicatesNote} · ярлык \`ai-review:skip\` отключает ревью PR</sub>`
  );

  return lines.join('\n');
}

function readState(core) {
  if (!fs.existsSync(STATE_FILE)) {
    core.warning(`Review context '${STATE_FILE}' is missing; publishing as a full review without history.`);
    return { mode: 'full', lastReviewedSha: null, pendingFiles: [], findings: [], jira: null };
  }
  return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
}

/** Files the DeepSeek script could not fit into the model's diff limit. */
function readTruncatedFiles() {
  if (!fs.existsSync(TRUNCATED_FILE)) {
    return [];
  }
  return [...new Set(fs.readFileSync(TRUNCATED_FILE, 'utf8').split('\n').filter(Boolean))];
}

/**
 * Findings that exist only in the summary (over the inline cap, rejected by
 * GitHub, near deleted code) are carried in the summary state, so the next
 * run still tracks them. Minor ones are shown once.
 */
function collectNotes(open, extra, minSeverity) {
  const notes = [
    ...open
      .filter((finding) => finding.fromSummary)
      .map((finding) => ({ path: finding.path, line: finding.line, severity: finding.severity, text: finding.text })),
    ...extra
      .filter((comment) => isAtLeast(comment.severity, minSeverity))
      .map((comment) => ({ path: comment.path, line: comment.line, severity: comment.severity, text: comment.body })),
  ];
  return notes
    .sort((a, b) => severityRank(a.severity) - severityRank(b.severity))
    .slice(0, MAX_NOTES)
    .map((note) => ({ ...note, text: note.text.replace(/\s+/g, ' ').trim().slice(0, MAX_NOTE_LENGTH) }));
}

/**
 * Posts the findings as one review. GitHub rejects the whole review with 422
 * when any line is not commentable, so then each finding is retried alone.
 * Returns the findings GitHub would not accept.
 */
async function publishInlineComments({ github, core, owner, repo, pullNumber, provider, headSha, comments }) {
  if (comments.length === 0) {
    return [];
  }

  const existingInlineComments = await github.paginate(
    github.rest.pulls.listReviewComments,
    { owner, repo, pull_number: pullNumber, per_page: 100 }
  );
  const pending = comments
    .map((source) => {
      const marker = inlineMarker(provider, headSha, source);
      return {
        source,
        marker,
        comment: {
          path: source.path,
          line: source.line,
          side: 'RIGHT',
          body: `${marker}\n${SEVERITY_ICONS[source.severity]} **[${source.severity}]** ${source.body}`,
        },
      };
    })
    .filter(({ marker }) =>
      !existingInlineComments.some((comment) => comment.body?.includes(marker))
    );

  if (pending.length === 0) {
    core.info('Inline comments for this commit were already published.');
    return [];
  }

  const createReview = (items) => github.rest.pulls.createReview({
    owner,
    repo,
    pull_number: pullNumber,
    commit_id: headSha,
    event: 'COMMENT',
    body: `AI-ревью (${provider}): ${items.length} новых замечаний. Сводка и статус прошлых замечаний — в общем комментарии PR.`,
    comments: items.map((item) => item.comment),
  });

  try {
    await createReview(pending);
    return [];
  } catch (error) {
    if (error.status !== 422) {
      throw error;
    }
    core.warning(`GitHub rejected the inline review (${error.message}); retrying the findings one by one.`);
  }

  const rejected = [];
  for (const item of pending) {
    try {
      await createReview([item]);
    } catch (error) {
      if (error.status !== 422) {
        throw error;
      }
      rejected.push(item.source);
    }
  }
  return rejected;
}

/**
 * Marks fixed findings in their own thread (and optionally resolves the
 * thread) and clears a stale mark when a finding turns out to be open again.
 * Comment edits do not notify anyone, unlike replies.
 */
async function updatePreviousThreads({ github, core, owner, repo, open, fixed, headSha, autoResolve }) {
  const updateBody = async (finding, body) => {
    if (body === finding.body) {
      return;
    }
    try {
      await github.rest.pulls.updateReviewComment({ owner, repo, comment_id: finding.commentId, body });
    } catch (error) {
      core.warning(`Failed to update review comment ${finding.commentId}: ${error.message}`);
    }
  };

  // Findings from the summary have no thread to update.
  for (const finding of fixed.filter((item) => item.commentId)) {
    // Only a verdict from this run resolves a thread, never an older mark.
    if (autoResolve && finding.status === 'fixed') {
      try {
        await github.graphql(RESOLVE_THREAD_MUTATION, { threadId: finding.threadId });
        finding.autoResolved = true;
      } catch (error) {
        core.warning(`Failed to resolve review thread ${finding.threadId}: ${error.message}`);
      }
    }
    if (!finding.body.includes(STATUS_LINE_MARKER)) {
      const action = finding.autoResolved ? AUTO_RESOLVED_NOTE : 'можно отметить Resolve';
      await updateBody(
        finding,
        setStatusLine(finding.body, `✅ _Похоже, исправлено в \`${headSha.slice(0, 7)}\` — ${action}._`)
      );
    }
  }

  for (const finding of open.filter((item) => item.commentId)) {
    if (finding.reopened) {
      // Remember the team's decision, so the bot never closes it again.
      if (!finding.body.includes(REOPENED_NOTE)) {
        await updateBody(finding, setStatusLine(finding.body, `↩️ _${REOPENED_NOTE}_`));
      }
    } else if (finding.status === 'open' && finding.body.includes(STATUS_LINE_MARKER)) {
      // Only an explicit verdict clears a mark: findings left out of the
      // prompt were not re-checked.
      await updateBody(finding, setStatusLine(finding.body, null));
    }
  }
}

async function publishReview({ github, context, core, env = process.env }) {
  if (!fs.existsSync(OUTPUT_FILE)) {
    throw new Error(`AI review output file '${OUTPUT_FILE}' does not exist.`);
  }

  const review = parseReview(fs.readFileSync(OUTPUT_FILE, 'utf8'));
  const state = readState(core);
  const settings = readSettings(env);
  const provider = env.REVIEW_PROVIDER || 'unknown';
  const baseSha = env.PR_BASE_SHA;
  const headSha = env.PR_HEAD_SHA;
  if (!baseSha || !headSha) {
    throw new Error('PR_BASE_SHA and PR_HEAD_SHA are required to publish a review.');
  }

  const incremental = state.mode === 'incremental';
  const scope = computeReviewScope({
    baseSha,
    headSha,
    lastReviewedSha: incremental ? state.lastReviewedSha : null,
    pendingFiles: incremental ? state.pendingFiles ?? [] : [],
  });
  const findings = state.findings ?? [];
  const plan = planComments(review.comments, { ...scope, findings, ...settings });
  const { open, fixed } = classifyPreviousFindings(findings, review.previousFindings);
  core.info(
    `AI review plan: ${plan.inline.length} inline, ${plan.extra.length} in summary, `
    + `${plan.duplicates.length} duplicates dropped, ${plan.outOfScope.length} outside the review scope dropped; `
    + `previous findings: ${open.length} open, ${fixed.length} fixed.`
  );

  const { owner, repo } = context.repo;
  const pullNumber = context.payload.pull_request.number;

  // Inline comments go first: the summary stores the reviewed commit, so if
  // publishing fails midway the next run reviews the same changes again.
  const rejected = await publishInlineComments({
    github, core, owner, repo, pullNumber, provider, headSha, comments: plan.inline,
  });
  if (rejected.length > 0) {
    plan.inline = plan.inline.filter((comment) => !rejected.includes(comment));
    plan.extra.unshift(...rejected);
  }
  await updatePreviousThreads({
    github, core, owner, repo, open, fixed, headSha, autoResolve: settings.autoResolve,
  });

  // Files cut from a too large diff are reviewed by the next run.
  const pending = readTruncatedFiles();
  const notes = collectNotes(open, plan.extra, settings.minSeverity);
  const summaryBody = renderSummary({
    review,
    plan,
    open,
    fixed,
    jira: state.jira,
    provider,
    headSha,
    pending,
    notes,
    mode: scope.mode,
    lastReviewedSha: scope.lastReviewedSha,
    settings,
  });
  const existingSummary = await findSummaryComment(github, { owner, repo, pullNumber });
  if (existingSummary) {
    await github.rest.issues.updateComment({
      owner,
      repo,
      comment_id: existingSummary.id,
      body: summaryBody,
    });
  } else {
    await github.rest.issues.createComment({
      owner,
      repo,
      issue_number: pullNumber,
      body: summaryBody,
    });
  }
}

module.exports = publishReview;
module.exports.classifyPreviousFindings = classifyPreviousFindings;
module.exports.collectAddedLines = collectAddedLines;
module.exports.collectNotes = collectNotes;
module.exports.parseReview = parseReview;
module.exports.planComments = planComments;
module.exports.readSettings = readSettings;
module.exports.renderSummary = renderSummary;
