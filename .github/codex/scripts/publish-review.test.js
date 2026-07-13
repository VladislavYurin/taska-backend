const assert = require('node:assert/strict');
const test = require('node:test');

const {
  collectAddedLines,
  parseReview,
  splitCommentsByAnchor,
} = require('./publish-review');

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

test('parseReview validates and normalizes the model response', () => {
  const review = parseReview(JSON.stringify({
    summary: ' Summary ',
    manual_checks: ['Run smoke test'],
    comments: [{
      severity: 'high',
      path: 'src/Foo.java',
      line: 10,
      body: 'Fix the race.',
    }],
    tests: 'Tests are missing.',
  }));

  assert.equal(review.summary, 'Summary');
  assert.equal(review.comments[0].line, 10);
  assert.throws(() => parseReview('{"summary":"broken"}'));
});

test('splitCommentsByAnchor keeps invalid anchors out of inline review', () => {
  const comments = [
    { path: 'src/Foo.java', line: 10, severity: 'high', body: 'Valid' },
    { path: 'src/Foo.java', line: 11, severity: 'low', body: 'Invalid' },
  ];
  const addedLines = new Map([['src/Foo.java', new Set([10])]]);

  const result = splitCommentsByAnchor(comments, addedLines);

  assert.equal(result.anchored.length, 1);
  assert.equal(result.unanchored.length, 1);
});
