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
  fs.writeFileSync(path.join(directory, 'example.txt'), 'first\n');
  git(directory, 'add', 'example.txt');
  git(directory, 'commit', '--quiet', '-m', 'base');
  const baseSha = git(directory, 'rev-parse', 'HEAD');
  git(directory, 'update-ref', 'refs/remotes/origin/develop', baseSha);
  fs.writeFileSync(path.join(directory, 'example.txt'), 'first\nsecond\nthird\n');
  git(directory, 'add', 'example.txt');
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
});

test('DeepSeek script does not print an API error response body', (t) => {
  const { directory, mockBin } = prepareRepository();
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

  const result = runReview(directory, mockBin, { MOCK_STATUS: '401' });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /HTTP 401/);
  assert.doesNotMatch(result.stderr, /LEAK_SENTINEL/);
});
