const assert = require('node:assert/strict');
const { execFileSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const repositoryRoot = path.resolve(__dirname, '../../..');

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

function prepareRepository() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-review-test-'));
  fs.mkdirSync(path.join(directory, '.github/codex/prompts'), { recursive: true });
  fs.mkdirSync(path.join(directory, '.github/codex/schemas'), { recursive: true });
  fs.mkdirSync(path.join(directory, '.github/codex/scripts'), { recursive: true });
  fs.copyFileSync(
    path.join(repositoryRoot, '.github/codex/prompts/review.md'),
    path.join(directory, '.github/codex/prompts/review.md')
  );
  fs.copyFileSync(
    path.join(repositoryRoot, '.github/codex/schemas/review-output.json'),
    path.join(directory, '.github/codex/schemas/review-output.json')
  );
  fs.copyFileSync(
    path.join(repositoryRoot, '.github/codex/scripts/deepseek-pr-review.sh'),
    path.join(directory, '.github/codex/scripts/deepseek-pr-review.sh')
  );

  git(directory, 'init', '--quiet');
  git(directory, 'config', 'user.email', 'test@example.com');
  git(directory, 'config', 'user.name', 'Test');
  git(directory, 'config', 'commit.gpgsign', 'false');
  fs.writeFileSync(path.join(directory, 'example.txt'), 'first\n');
  fs.writeFileSync(path.join(directory, 'zz-priority.txt'), 'first\n');
  git(directory, 'add', 'example.txt', 'zz-priority.txt');
  git(directory, 'commit', '--quiet', '-m', 'base');
  const baseSha = git(directory, 'rev-parse', 'HEAD');
  git(directory, 'update-ref', 'refs/remotes/origin/develop', baseSha);
  fs.writeFileSync(path.join(directory, 'example.txt'), 'first\nsecond\nthird\n');
  fs.writeFileSync(path.join(directory, 'zz-priority.txt'), 'first\nchanged since last review\n');
  git(directory, 'add', 'example.txt', 'zz-priority.txt');
  git(directory, 'commit', '--quiet', '-m', 'head');

  const mockBin = path.join(directory, 'mock-bin');
  fs.mkdirSync(mockBin);
  const mockCurl = path.join(mockBin, 'curl');
  fs.writeFileSync(mockCurl, `#!/usr/bin/env bash
set -euo pipefail
output=""
while (( $# > 0 )); do
  if [[ "$1" == "--output" ]]; then
    output="$2"
    shift 2
  elif [[ "$1" == "--data-binary" ]]; then
    cp "\${2#@}" "${path.join(directory, 'captured-request.json')}"
    shift 2
  else
    shift
  fi
done
if [[ "\${MOCK_STATUS:-200}" == "200" ]]; then
  printf '%s' '{"choices":[{"message":{"content":"{\\"summary\\":\\"OK\\",\\"manual_checks\\":[],\\"comments\\":[],\\"tests\\":\\"OK\\"}"}}]}' > "$output"
else
  printf '%s' '{"error":{"message":"LEAK_SENTINEL"}}' > "$output"
fi
printf '%s' "\${MOCK_STATUS:-200}"
`);
  fs.chmodSync(mockCurl, 0o755);

  return { directory, mockBin };
}

function runReview(directory, mockBin, extraEnv = {}) {
  return spawnSync(
    'bash',
    ['.github/codex/scripts/deepseek-pr-review.sh'],
    {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env,
        PATH: `${mockBin}${path.delimiter}${process.env.PATH}`,
        DEEPSEEK_API_KEY: 'test-secret',
        DEEPSEEK_MAX_DIFF_LINES: '1',
        PR_BASE_REF: 'develop',
        PR_NUMBER: '1',
        PR_TITLE: 'Test PR',
        ...extraEnv,
      },
    }
  );
}

test('DeepSeek script uses base-ref fallback and writes structured output', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = runReview(directory, mockBin);

  assert.equal(result.status, 0, result.stderr);
  assert.equal(
    JSON.parse(fs.readFileSync(path.join(directory, 'codex-output.json'))).summary,
    'OK'
  );
  assert.match(result.stderr, /diff was truncated/);
  // With a 1-line limit no file fits: both are left for the next run.
  assert.equal(fs.readFileSync(path.join(directory, '.ai-review/truncated'), 'utf8'), 'example.txt\nzz-priority.txt\n');
});

test('DeepSeek script does not report already reviewed context cut by the limit', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.mkdirSync(path.join(directory, '.ai-review'));
  fs.writeFileSync(path.join(directory, '.ai-review/priority-files.txt'), 'zz-priority.txt\n');

  // The priority diff is 7 lines and fits; example.txt after it is context.
  const result = runReview(directory, mockBin, { DEEPSEEK_MAX_DIFF_LINES: '8' });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(fs.readFileSync(path.join(directory, '.ai-review/truncated'), 'utf8'), '');
});

test('DeepSeek script shrinks the diff context before cutting files', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const body = Array.from({ length: 200 }, (_, index) => `line ${index + 1}`);
  fs.writeFileSync(path.join(directory, 'Long.java'), `${body.join('\n')}\n`);
  git(directory, 'add', '-A');
  git(directory, 'commit', '--quiet', '--amend', '--no-edit');
  const head = git(directory, 'rev-parse', 'HEAD');
  body[99] = 'changed';
  fs.writeFileSync(path.join(directory, 'Long.java'), `${body.join('\n')}\n`);
  git(directory, 'add', '-A');
  git(directory, 'commit', '--quiet', '-m', 'edit');
  git(directory, 'update-ref', 'refs/remotes/origin/develop', head);

  // With 40 lines of context the change needs ~87 lines, with 10 lines ~27.
  const result = runReview(directory, mockBin, { DEEPSEEK_MAX_DIFF_LINES: '40' });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(fs.existsSync(path.join(directory, '.ai-review/truncated')), false);
  const request = JSON.parse(fs.readFileSync(path.join(directory, 'captured-request.json'), 'utf8'));
  assert.match(request.messages[1].content, /^@@ -90,21 \+90,21 @@/m);
});

test('DeepSeek script keeps a renamed priority file as a rename', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const body = Array.from({ length: 200 }, (_, index) => `line ${index + 1}`).join('\n');
  fs.writeFileSync(path.join(directory, 'Old Name.java'), `${body}\n`);
  git(directory, 'add', '-A');
  git(directory, 'commit', '--quiet', '--amend', '--no-edit');
  const head = git(directory, 'rev-parse', 'HEAD');
  git(directory, 'update-ref', 'refs/remotes/origin/develop', `${head}~1`);
  // Recreate the base without the file, then rename it with a small edit.
  git(directory, 'mv', 'Old Name.java', 'New Name.java');
  fs.writeFileSync(path.join(directory, 'New Name.java'), `${body.replace('line 100', 'changed 100')}\n`);
  git(directory, 'add', '-A');
  git(directory, 'commit', '--quiet', '-m', 'rename');
  git(directory, 'update-ref', 'refs/remotes/origin/develop', head);
  fs.mkdirSync(path.join(directory, '.ai-review'));
  fs.writeFileSync(path.join(directory, '.ai-review/priority-files.txt'), 'New Name.java\n');

  const result = runReview(directory, mockBin, { DEEPSEEK_MAX_DIFF_LINES: '1000' });

  assert.equal(result.status, 0, result.stderr);
  const request = JSON.parse(fs.readFileSync(path.join(directory, 'captured-request.json'), 'utf8'));
  const diff = request.messages[1].content.split('## Diff\n')[1];
  assert.match(diff, /^rename from Old Name\.java$/m);
  assert.doesNotMatch(diff, /^deleted file mode/m);
  assert.ok(diff.split('\n').length < 200, 'the rename must not be expanded into whole-file hunks');
  assert.equal(fs.existsSync(path.join(directory, '.ai-review/truncated')), false);
});

test('DeepSeek script adds the review context and puts files in scope first', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  fs.mkdirSync(path.join(directory, '.ai-review'));
  fs.writeFileSync(path.join(directory, '.ai-review/context.md'), '# AI review context\n\nCONTEXT_SENTINEL\n');
  fs.writeFileSync(path.join(directory, '.ai-review/priority-files.txt'), 'zz-priority.txt\n');

  const result = runReview(directory, mockBin, { DEEPSEEK_MAX_DIFF_LINES: '1000' });

  assert.equal(result.status, 0, result.stderr);
  const request = JSON.parse(fs.readFileSync(path.join(directory, 'captured-request.json'), 'utf8'));
  const prompt = request.messages[1].content;
  assert.match(prompt, /CONTEXT_SENTINEL/);
  const diff = prompt.slice(prompt.indexOf('## Diff\n'));
  assert.ok(diff.indexOf('zz-priority.txt') < diff.indexOf('example.txt'), 'priority file must come first');
  assert.equal(diff.match(/^diff --git a\/zz-priority\.txt/gm).length, 1);
  assert.equal(diff.match(/^diff --git a\/example\.txt/gm).length, 1);
});

test('DeepSeek script does not print an API error response body', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = runReview(directory, mockBin, { MOCK_STATUS: '401' });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /HTTP 401/);
  assert.doesNotMatch(result.stderr, /LEAK_SENTINEL/);
});
