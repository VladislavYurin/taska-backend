// Test helpers shared by the review script tests. Not a test file itself.
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

function createRepository() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-review-test-'));
  const git = (...args) => execFileSync('git', args, { cwd: directory, encoding: 'utf8' }).trim();
  git('init', '--quiet', '--initial-branch=develop');
  git('config', 'user.email', 'test@example.com');
  git('config', 'user.name', 'Test');
  git('config', 'commit.gpgsign', 'false');

  const commit = (message, files) => {
    for (const [file, content] of Object.entries(files)) {
      const fullPath = path.join(directory, file);
      if (content === null) {
        fs.rmSync(fullPath, { force: true });
        continue;
      }
      fs.mkdirSync(path.dirname(fullPath), { recursive: true });
      fs.writeFileSync(fullPath, content);
    }
    git('add', '-A');
    git('commit', '--quiet', '-m', message);
    return git('rev-parse', 'HEAD');
  };

  return {
    directory,
    git,
    commit,
    cleanup: () => fs.rmSync(directory, { recursive: true, force: true }),
  };
}

function numberedLines(count, prefix = 'line') {
  return `${Array.from({ length: count }, (_, index) => `${prefix} ${index + 1}`).join('\n')}\n`;
}

function replaceLines(text, replacements) {
  const lines = text.split('\n');
  for (const [lineNumber, value] of Object.entries(replacements)) {
    lines[Number(lineNumber) - 1] = value;
  }
  return lines.join('\n');
}

async function inDirectory(directory, callback) {
  const previous = process.cwd();
  process.chdir(directory);
  try {
    return await callback();
  } finally {
    process.chdir(previous);
  }
}

function createCore() {
  const core = {
    infos: [],
    warnings: [],
    outputs: {},
    info: (message) => core.infos.push(message),
    warning: (message) => core.warnings.push(message),
    setOutput: (name, value) => {
      core.outputs[name] = value;
    },
  };
  return core;
}

/** Minimal Octokit stand-in recording every write in call order. */
function createGithub({ issueComments = [], reviewComments = [], threads = [], failResolve = false, rejectLines = [] } = {}) {
  const calls = [];
  const record = (name) => async (params) => {
    calls.push([name, params]);
    return { data: {} };
  };
  const github = {
    calls,
    rest: {
      issues: {
        listComments: 'issues.listComments',
        createComment: record('issues.createComment'),
        updateComment: record('issues.updateComment'),
      },
      pulls: {
        listReviewComments: 'pulls.listReviewComments',
        createReview: async (params) => {
          calls.push(['pulls.createReview', params]);
          // Simulates GitHub refusing a line that is not part of its diff.
          if (params.comments.some((comment) => rejectLines.includes(comment.line))) {
            throw Object.assign(new Error('Unprocessable Entity: line must be part of the diff'), { status: 422 });
          }
          return { data: {} };
        },
        updateReviewComment: record('pulls.updateReviewComment'),
      },
    },
    paginate: async (method) => (method === 'issues.listComments' ? issueComments : reviewComments),
    graphql: async (query, variables) => {
      if (query.includes('reviewThreads(')) {
        return {
          repository: {
            pullRequest: {
              reviewThreads: { pageInfo: { hasNextPage: false, endCursor: null }, nodes: threads },
            },
          },
        };
      }
      calls.push(['graphql', variables]);
      if (failResolve) {
        throw new Error('Resource not accessible by integration');
      }
      return {};
    },
  };
  return github;
}

function createContext({ title = 'TAS-1: test', branch = 'feature/TAS-1', number = 7, isPrivate = false } = {}) {
  return {
    repo: { owner: 'acme', repo: 'taska' },
    payload: {
      pull_request: { number, title, head: { ref: branch } },
      repository: { private: isPrivate },
    },
  };
}

module.exports = {
  createContext,
  createCore,
  createGithub,
  createRepository,
  inDirectory,
  numberedLines,
  replaceLines,
};
