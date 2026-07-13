const crypto = require('node:crypto');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

const REVIEW_MARKER = '<!-- codex-pr-review -->';
const SEVERITIES = new Set(['critical', 'high', 'medium', 'low']);
const MAX_INLINE_COMMENTS = 20;

function requireText(value, field, maxLength = 6000) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Review field '${field}' must be a non-empty string.`);
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

  const comments = review.comments.slice(0, MAX_INLINE_COMMENTS).map((comment, index) => {
    if (!comment || typeof comment !== 'object' || Array.isArray(comment)) {
      throw new Error(`Review comment ${index + 1} must be an object.`);
    }

    const severity = requireText(comment.severity, `comments[${index}].severity`);
    if (!SEVERITIES.has(severity)) {
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

  return {
    summary: requireText(review.summary, 'summary'),
    manualChecks: review.manual_checks.slice(0, 20).map((check, index) =>
      requireText(check, `manual_checks[${index}]`, 300)
    ),
    comments,
    tests: requireText(review.tests, 'tests'),
  };
}

function collectAddedLines(diff) {
  const addedLines = new Map();
  let currentPath = null;
  let currentLine = null;

  for (const diffLine of diff.split('\n')) {
    if (diffLine.startsWith('diff --git ')) {
      currentPath = null;
      currentLine = null;
      continue;
    }

    if (diffLine.startsWith('+++ ')) {
      const path = diffLine.slice(4);
      currentPath = path === '/dev/null'
        ? null
        : path.startsWith('b/') ? path.slice(2) : path;
      if (currentPath && !addedLines.has(currentPath)) {
        addedLines.set(currentPath, new Set());
      }
      continue;
    }

    if (diffLine.startsWith('@@ ')) {
      const match = diffLine.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
      currentLine = match ? Number(match[1]) : null;
      continue;
    }

    if (currentPath === null || currentLine === null || diffLine.startsWith('\\ ')) {
      continue;
    }

    if (diffLine.startsWith('+')) {
      addedLines.get(currentPath).add(currentLine);
      currentLine += 1;
    } else if (diffLine.startsWith('-')) {
      // Deleted lines do not advance the RIGHT side of the diff.
    } else {
      currentLine += 1;
    }
  }

  return addedLines;
}

function splitCommentsByAnchor(comments, addedLines) {
  const anchored = [];
  const unanchored = [];
  const seenAnchors = new Set();

  for (const comment of comments) {
    const anchor = `${comment.path}:${comment.line}`;
    if (addedLines.get(comment.path)?.has(comment.line) && !seenAnchors.has(anchor)) {
      anchored.push(comment);
      seenAnchors.add(anchor);
    } else {
      unanchored.push(comment);
    }
  }

  return { anchored, unanchored };
}

function renderSummary(review, anchoredCount, unanchored, provider, shortSha) {
  const manualChecks = review.manualChecks.length > 0
    ? review.manualChecks.map((check) => `- ${check}`).join('\n')
    : 'Нет.';
  const findings = anchoredCount > 0
    ? `${anchoredCount} замечаний опубликовано непосредственно в diff.`
    : 'Inline-замечаний по добавленным строкам нет.';
  const unanchoredSection = unanchored.length > 0
    ? [
        '',
        '### Без привязки к строке',
        ...unanchored.map((comment) =>
          `- **[${comment.severity}] \`${comment.path}:${comment.line}\`** ${comment.body}`
        ),
      ].join('\n')
    : '';

  return [
    REVIEW_MARKER,
    '## Кратко',
    review.summary,
    '',
    '## Что проверить вручную',
    manualChecks,
    '',
    '## Замечания',
    findings,
    unanchoredSection,
    '',
    '## Тесты',
    review.tests,
    '',
    '---',
    `_Авто-ревью (${provider}) обновлено для коммита \`${shortSha}\`._`,
  ].join('\n');
}

function inlineMarker(provider, commitSha, comment) {
  const digest = crypto
    .createHash('sha256')
    .update(`${provider}\0${commitSha}\0${comment.path}\0${comment.line}`)
    .digest('hex')
    .slice(0, 20);
  return `<!-- ai-pr-review:${digest} -->`;
}

async function publishReview({ github, context, core }) {
  const outputPath = 'codex-output.json';
  if (!fs.existsSync(outputPath)) {
    throw new Error(`AI review output file '${outputPath}' does not exist.`);
  }

  const review = parseReview(fs.readFileSync(outputPath, 'utf8'));
  const provider = process.env.REVIEW_PROVIDER || 'unknown';
  const baseSha = process.env.PR_BASE_SHA;
  const headSha = process.env.PR_HEAD_SHA;
  if (!baseSha || !headSha) {
    throw new Error('PR_BASE_SHA and PR_HEAD_SHA are required to publish a review.');
  }

  const diff = execFileSync(
    'git',
    [
      '-c',
      'core.quotePath=false',
      'diff',
      '--unified=0',
      '--no-color',
      '--no-ext-diff',
      `${baseSha}...${headSha}`,
    ],
    { encoding: 'utf8', maxBuffer: 50 * 1024 * 1024 }
  );
  const { anchored, unanchored } = splitCommentsByAnchor(
    review.comments,
    collectAddedLines(diff)
  );

  const { owner, repo } = context.repo;
  const pullNumber = context.payload.pull_request.number;
  const shortSha = headSha.slice(0, 7);
  const summaryBody = renderSummary(
    review,
    anchored.length,
    unanchored,
    provider,
    shortSha
  );

  const issueComments = await github.paginate(github.rest.issues.listComments, {
    owner,
    repo,
    issue_number: pullNumber,
    per_page: 100,
  });
  const existingSummary = issueComments.find((comment) =>
    comment.user?.type === 'Bot' && comment.body?.includes(REVIEW_MARKER)
  );

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

  if (anchored.length === 0) {
    core.info('AI review has no comments anchored to added diff lines.');
    return;
  }

  const existingInlineComments = await github.paginate(
    github.rest.pulls.listReviewComments,
    { owner, repo, pull_number: pullNumber, per_page: 100 }
  );
  const newComments = anchored
    .map((comment) => {
      const marker = inlineMarker(provider, headSha, comment);
      return {
        marker,
        path: comment.path,
        line: comment.line,
        side: 'RIGHT',
        body: `${marker}\n**[${comment.severity}]** ${comment.body}`,
      };
    })
    .filter(({ marker }) =>
      !existingInlineComments.some((comment) => comment.body?.includes(marker))
    )
    .map(({ marker: _marker, ...comment }) => comment);

  if (newComments.length === 0) {
    core.info('Inline comments for this commit were already published.');
    return;
  }

  await github.rest.pulls.createReview({
    owner,
    repo,
    pull_number: pullNumber,
    commit_id: headSha,
    event: 'COMMENT',
    body: `AI-ревью (${provider}): ${newComments.length} замечаний по строкам.`,
    comments: newComments,
  });
}

module.exports = publishReview;
module.exports.collectAddedLines = collectAddedLines;
module.exports.parseReview = parseReview;
module.exports.renderSummary = renderSummary;
module.exports.splitCommentsByAnchor = splitCommentsByAnchor;
