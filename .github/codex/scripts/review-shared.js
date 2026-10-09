const { execFileSync } = require('node:child_process');

const CONTEXT_DIR = '.ai-review';
const CONTEXT_FILE = `${CONTEXT_DIR}/context.md`;
const STATE_FILE = `${CONTEXT_DIR}/state.json`;
const PRIORITY_FILES_FILE = `${CONTEXT_DIR}/priority-files.txt`;
const TRUNCATED_FILE = `${CONTEXT_DIR}/truncated`;

const REVIEW_MARKER = '<!-- codex-pr-review -->';
const STATE_PATTERN = /<!-- ai-review-state:(\{[^\n]*?\}) -->/;
const INLINE_MARKER_PATTERN = /<!-- ai-pr-review:[0-9a-f]+ -->/;
const STATUS_LINE_MARKER = '<!-- ai-review-status -->';
const SEVERITY_PATTERN = /\*\*\[(critical|high|medium|low)\]\*\*\s*/;

const SEVERITIES = ['critical', 'high', 'medium', 'low'];
const SEVERITY_ICONS = { critical: '🔴', high: '🟠', medium: '🟡', low: '⚪' };

const SHA_PATTERN = /^[0-9a-f]{40}$/;
const MAX_PENDING_FILES = 300;
const MAX_NOTES = 20;
const MAX_NOTE_LENGTH = 300;

// Status line texts. The auto-resolve note lets the next run notice a thread
// that a human reopened after the bot closed it.
const AUTO_RESOLVED_NOTE = 'тред закрыт автоматически';
const REOPENED_NOTE = 'Тред переоткрыт вручную — бот больше не закрывает его сам.';

function git(args) {
  return execFileSync('git', ['-c', 'core.quotePath=false', ...args], {
    encoding: 'utf8',
    maxBuffer: 50 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

const C_ESCAPES = { a: 7, b: 8, t: 9, n: 10, v: 11, f: 12, r: 13, '"': 34, '\\': 92 };

/** Reverses git's C-style quoting of unusual paths ("b/we\"ird.java"). */
function unquoteGitPath(path) {
  if (!(path.length >= 2 && path.startsWith('"') && path.endsWith('"'))) {
    return path;
  }
  const body = path.slice(1, -1);
  const bytes = [];
  for (let index = 0; index < body.length; index += 1) {
    if (body[index] !== '\\') {
      const character = String.fromCodePoint(body.codePointAt(index));
      bytes.push(...Buffer.from(character, 'utf8'));
      index += character.length - 1;
      continue;
    }
    const next = body[index + 1];
    if (/[0-7]/.test(next ?? '')) {
      bytes.push(parseInt(body.slice(index + 1, index + 4), 8));
      index += 3;
    } else {
      bytes.push(C_ESCAPES[next] ?? next.charCodeAt(0));
      index += 1;
    }
  }
  return Buffer.from(bytes).toString('utf8');
}

function diffHeaderPath(diffLine, prefix) {
  // Git appends a TAB to ---/+++ headers of paths that contain spaces.
  const path = unquoteGitPath(diffLine.slice(4).replace(/\t$/, ''));
  if (path === '/dev/null') {
    return null;
  }
  return path.startsWith(prefix) ? path.slice(prefix.length) : path;
}

/**
 * Reads a unified diff: RIGHT-side line numbers of added lines per file, and
 * RIGHT-side ranges [start, end] around hunks that removed more lines than
 * they added (code was deleted there, not just edited).
 */
function parseUnifiedDiff(diff) {
  const addedLines = new Map();
  const deletionRanges = new Map();
  let oldPath = null;
  let currentPath = null;
  let currentLine = null;

  for (const diffLine of diff.split('\n')) {
    if (diffLine.startsWith('diff --git ')) {
      oldPath = null;
      currentPath = null;
      currentLine = null;
      continue;
    }

    if (currentLine === null && diffLine.startsWith('--- ')) {
      oldPath = diffHeaderPath(diffLine, 'a/');
      continue;
    }

    if (currentLine === null && diffLine.startsWith('+++ ')) {
      currentPath = diffHeaderPath(diffLine, 'b/');
      if (currentPath && !addedLines.has(currentPath)) {
        addedLines.set(currentPath, new Set());
      }
      continue;
    }

    if (diffLine.startsWith('@@ ')) {
      const match = diffLine.match(/^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
      currentLine = match ? Number(match[2]) : null;
      const oldCount = match ? Number(match[1] ?? 1) : 0;
      const newCount = match ? Number(match[3] ?? 1) : 0;
      const path = currentPath ?? oldPath;
      if (match && path && oldCount > newCount) {
        if (!deletionRanges.has(path)) {
          deletionRanges.set(path, []);
        }
        // A pure deletion (+c,0) sits between lines c and c + 1.
        deletionRanges.get(path).push([currentLine, currentLine + Math.max(newCount, 1)]);
      }
      continue;
    }

    if (currentLine === null || diffLine.startsWith('\\ ')) {
      continue;
    }

    if (diffLine.startsWith('+')) {
      if (currentPath !== null) {
        addedLines.get(currentPath).add(currentLine);
      }
      currentLine += 1;
    } else if (diffLine.startsWith('-')) {
      // Deleted lines do not advance the RIGHT side of the diff.
    } else {
      currentLine += 1;
    }
  }

  return { addedLines, deletionRanges };
}

function collectAddedLines(diff) {
  return parseUnifiedDiff(diff).addedLines;
}

function diffLines(...range) {
  return parseUnifiedDiff(
    git(['diff', '--unified=0', '--no-color', '--no-ext-diff', ...range])
  );
}

function diffAddedLines(...range) {
  return diffLines(...range).addedLines;
}

function diffChangedFiles(...range) {
  // -z keeps unusual paths unquoted.
  return new Set(
    git(['diff', '-z', '--name-only', '--no-color', '--no-ext-diff', ...range])
      .split('\0')
      .filter(Boolean)
  );
}

function isAncestor(ancestor, descendant) {
  try {
    git(['merge-base', '--is-ancestor', ancestor, descendant]);
    return true;
  } catch {
    return false;
  }
}

function commitExists(sha) {
  try {
    git(['cat-file', '-e', `${sha}^{commit}`]);
    return true;
  } catch {
    return false;
  }
}

/**
 * Lines a review may comment on. A full review covers every line the PR adds;
 * an incremental review covers only the PR lines touched since the last
 * reviewed commit, so already reviewed code is not re-reviewed on every push.
 *
 * Modes: `full`; `incremental`; `stale` when the event's head is the last
 * reviewed commit or older (a re-run of an old workflow run), which needs no
 * review.
 */
function computeReviewScope({ baseSha, headSha, lastReviewedSha, pendingFiles = [] }) {
  const prAddedLines = diffAddedLines(`${baseSha}...${headSha}`);
  const prFiles = diffChangedFiles(`${baseSha}...${headSha}`);

  const hasLastReviewed = typeof lastReviewedSha === 'string'
    && SHA_PATTERN.test(lastReviewedSha)
    && commitExists(lastReviewedSha);

  if (hasLastReviewed && (lastReviewedSha === headSha || isAncestor(headSha, lastReviewedSha))) {
    return {
      mode: 'stale',
      lastReviewedSha,
      prAddedLines,
      reviewableLines: new Map(),
      touchedFiles: new Set(),
      changedFiles: new Set(),
      deletionRanges: new Map(),
    };
  }

  if (!hasLastReviewed || !isAncestor(lastReviewedSha, headSha)) {
    return {
      mode: 'full',
      lastReviewedSha: null,
      prAddedLines,
      reviewableLines: prAddedLines,
      touchedFiles: prFiles,
      changedFiles: prFiles,
      deletionRanges: new Map(),
    };
  }

  const sinceLast = diffLines(lastReviewedSha, headSha);
  const changedSinceLast = diffChangedFiles(lastReviewedSha, headSha);
  const prFilesAtLast = diffChangedFiles(`${baseSha}...${lastReviewedSha}`);
  // Files of the PR (now or at the last review) changed since then. A file
  // reverted to base or removed from the PR still counts: its earlier
  // findings may be fixed now.
  const changedFiles = new Set(
    [...changedSinceLast].filter((path) => prFiles.has(path) || prFilesAtLast.has(path))
  );
  const touchedFiles = new Set([...changedSinceLast].filter((path) => prFiles.has(path)));
  const deletionRanges = new Map(
    [...sinceLast.deletionRanges].filter(([path]) => prFiles.has(path))
  );

  const reviewableLines = new Map();
  for (const [path, lines] of prAddedLines) {
    const addedSinceLast = sinceLast.addedLines.get(path);
    if (!addedSinceLast) {
      continue;
    }
    const intersection = new Set([...lines].filter((line) => addedSinceLast.has(line)));
    if (intersection.size > 0) {
      reviewableLines.set(path, intersection);
    }
  }

  // Files the previous run could not send to the model are reviewed in full.
  for (const path of pendingFiles) {
    if (!prFiles.has(path)) {
      continue;
    }
    changedFiles.add(path);
    touchedFiles.add(path);
    if (prAddedLines.get(path)?.size > 0) {
      reviewableLines.set(path, prAddedLines.get(path));
    }
  }

  return {
    mode: 'incremental',
    lastReviewedSha,
    prAddedLines,
    reviewableLines,
    touchedFiles,
    changedFiles,
    deletionRanges,
  };
}

function toRanges(lines) {
  const sorted = [...lines].sort((a, b) => a - b);
  const ranges = [];
  for (const line of sorted) {
    const last = ranges[ranges.length - 1];
    if (last && line === last[1] + 1) {
      last[1] = line;
    } else {
      ranges.push([line, line]);
    }
  }
  return ranges;
}

function formatRanges(lines) {
  return toRanges(lines)
    .map(([start, end]) => (start === end ? `${start}` : `${start}-${end}`))
    .join(', ');
}

/**
 * The hidden state in the summary comment: the last reviewed commit, files
 * that did not fit into the model's diff limit (`pending`, reviewed next
 * time) and findings that exist only in the summary (`notes`, no thread).
 */
function renderState({ sha, pending = [], notes = [] }) {
  const state = { sha };
  if (pending.length > 0) {
    state.pending = pending.slice(0, MAX_PENDING_FILES);
  }
  if (notes.length > 0) {
    state.notes = notes.slice(0, MAX_NOTES);
  }
  // "--" would end the HTML comment early; \u002d is the same JSON string.
  return `<!-- ai-review-state:${JSON.stringify(state).replace(/--/g, '-\\u002d')} -->`;
}

function parseState(body) {
  const match = typeof body === 'string' ? body.match(STATE_PATTERN) : null;
  if (!match) {
    return null;
  }
  let state;
  try {
    state = JSON.parse(match[1]);
  } catch {
    return null;
  }
  if (!state || typeof state.sha !== 'string' || !SHA_PATTERN.test(state.sha)) {
    return null;
  }
  const pending = (Array.isArray(state.pending) ? state.pending : [])
    .filter((path) => typeof path === 'string' && path.length > 0 && path.length <= 500)
    .slice(0, MAX_PENDING_FILES);
  const notes = (Array.isArray(state.notes) ? state.notes : [])
    .filter((note) =>
      note
      && typeof note.path === 'string'
      && Number.isInteger(note.line)
      && SEVERITIES.includes(note.severity)
      && typeof note.text === 'string'
    )
    .slice(0, MAX_NOTES)
    .map((note) => ({
      path: note.path,
      line: note.line,
      severity: note.severity,
      text: note.text.slice(0, MAX_NOTE_LENGTH),
    }));
  return { sha: state.sha, pending, notes };
}

function isBotAuthor(user) {
  return user?.type === 'Bot' || user?.__typename === 'Bot';
}

async function findSummaryComment(github, { owner, repo, pullNumber }) {
  const issueComments = await github.paginate(github.rest.issues.listComments, {
    owner,
    repo,
    issue_number: pullNumber,
    per_page: 100,
  });
  return issueComments.find((comment) =>
    isBotAuthor(comment.user) && comment.body?.includes(REVIEW_MARKER)
  ) ?? null;
}

/** Splits a published inline comment into its severity and the finding text. */
function parseInlineBody(body) {
  const lines = String(body ?? '')
    .split('\n')
    .filter((line) => !INLINE_MARKER_PATTERN.test(line) && !line.includes(STATUS_LINE_MARKER));
  const text = lines.join('\n').trim();
  const severityMatch = text.match(SEVERITY_PATTERN);
  const severity = severityMatch ? severityMatch[1] : 'medium';
  const withoutSeverity = severityMatch
    ? text.slice(severityMatch.index + severityMatch[0].length)
    : text;
  return { severity, text: withoutSeverity.trim() };
}

function setStatusLine(body, statusLine) {
  const lines = [];
  const original = String(body ?? '').split('\n');
  for (let index = 0; index < original.length; index += 1) {
    if (original[index].includes(STATUS_LINE_MARKER)) {
      // The status line is followed by a blank separator line.
      if (original[index + 1] === '') {
        index += 1;
      }
      continue;
    }
    lines.push(original[index]);
  }
  if (statusLine) {
    const markerIndex = lines.findIndex((line) => INLINE_MARKER_PATTERN.test(line));
    // The marker goes last: a line starting with an HTML comment is rendered
    // as raw HTML, so markdown after it would not be formatted.
    lines.splice(markerIndex + 1, 0, `${statusLine} ${STATUS_LINE_MARKER}`, '');
  }
  return lines.join('\n');
}

function severityRank(severity) {
  const rank = SEVERITIES.indexOf(severity);
  return rank === -1 ? SEVERITIES.length : rank;
}

function isAtLeast(severity, minSeverity) {
  return severityRank(severity) <= severityRank(minSeverity);
}

// Words that carry no signal about which problem a finding describes.
const STOP_WORDS = new Set([
  'это', 'этот', 'эта', 'эти', 'того', 'тогда', 'если', 'когда', 'может', 'можно',
  'нужно', 'надо', 'будет', 'будут', 'который', 'которая', 'которое', 'которые',
  'чтобы', 'только', 'также', 'потому', 'поэтому', 'всегда', 'через', 'после',
  'перед', 'между', 'например', 'стоит', 'лучше', 'добавить', 'добавьте', 'исправить',
  'использовать', 'используйте', 'используется', 'использует', 'метод', 'метода',
  'методе', 'класс', 'класса', 'строка', 'строке', 'значение', 'вызов', 'вызова',
  'есть', 'нет', 'или', 'для', 'при', 'что', 'как', 'так', 'уже', 'ещё', 'еще',
  'вместо', 'однако', 'сейчас', 'теперь', 'случае', 'проверить', 'проверьте',
  'убедитесь', 'код', 'кода', 'коде',
  'this', 'that', 'with', 'when', 'should', 'will', 'from', 'have', 'which', 'there',
  'true', 'false', 'null', 'void', 'return', 'public', 'private', 'final', 'static',
  'string', 'java',
]);

function tokenize(text) {
  const tokens = new Set();
  const words = String(text ?? '').toLowerCase().match(/[\p{L}\p{N}_]+/gu) ?? [];
  for (const word of words) {
    if (word.length < 4 || STOP_WORDS.has(word) || /^\d+$/.test(word)) {
      continue;
    }
    // Crude stemming for Russian word forms; identifiers are kept whole.
    tokens.add(/[а-яё]/.test(word) ? word.slice(0, 6) : word);
  }
  return tokens;
}

/** Overlap coefficient of two token sets: |A ∩ B| / min(|A|, |B|). */
function similarity(left, right) {
  const a = left instanceof Set ? left : tokenize(left);
  const b = right instanceof Set ? right : tokenize(right);
  if (a.size < 3 || b.size < 3) {
    return 0;
  }
  let common = 0;
  for (const token of a) {
    if (b.has(token)) {
      common += 1;
    }
  }
  return common / Math.min(a.size, b.size);
}

// Far apart in a file, only a near-verbatim repeat counts: same-class bugs
// in different methods are described with very similar words.
const DUPLICATE_SIMILARITY = 0.75;
const NEARBY_DUPLICATE_SIMILARITY = 0.45;
const NEARBY_LINES = 5;

/**
 * Deterministic safety net on top of the model's own deduplication: a finding
 * on the same file that reads like an earlier one is treated as a repeat.
 */
function findSimilarFinding(comment, candidates) {
  const tokens = tokenize(comment.body);
  let best = null;
  for (const candidate of candidates) {
    if (candidate.path !== comment.path) {
      continue;
    }
    const score = similarity(tokens, candidate.tokens ?? tokenize(candidate.text ?? candidate.body));
    const candidateLine = candidate.line ?? candidate.originalLine;
    const nearby = Number.isInteger(candidateLine)
      && Math.abs(candidateLine - comment.line) <= NEARBY_LINES;
    const threshold = nearby ? NEARBY_DUPLICATE_SIMILARITY : DUPLICATE_SIMILARITY;
    if (score >= threshold && (!best || score > best.score)) {
      best = { candidate, score };
    }
  }
  return best?.candidate ?? null;
}

function firstSentence(text, maxLength = 160) {
  const normalized = String(text ?? '').replace(/\s+/g, ' ').trim();
  const sentenceEnd = normalized.search(/[.!?](\s|$)/);
  const sentence = sentenceEnd > 0 ? normalized.slice(0, sentenceEnd + 1) : normalized;
  return sentence.length > maxLength ? `${sentence.slice(0, maxLength - 1).trimEnd()}…` : sentence;
}

function basename(path) {
  return String(path).split('/').pop();
}

module.exports = {
  AUTO_RESOLVED_NOTE,
  CONTEXT_DIR,
  MAX_NOTES,
  MAX_NOTE_LENGTH,
  CONTEXT_FILE,
  INLINE_MARKER_PATTERN,
  PRIORITY_FILES_FILE,
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
  diffAddedLines,
  diffChangedFiles,
  findSimilarFinding,
  findSummaryComment,
  firstSentence,
  formatRanges,
  git,
  isAtLeast,
  isBotAuthor,
  parseInlineBody,
  parseState,
  parseUnifiedDiff,
  renderState,
  setStatusLine,
  severityRank,
  similarity,
  tokenize,
  toRanges,
  unquoteGitPath,
};
