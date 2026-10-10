const { extractIssueKey, jiraRequest, parseProjectKeys } = require('./jira-context');

const MAX_LINK_TITLE = 250;

function sameName(left, right) {
  return String(left ?? '').trim().toLowerCase() === String(right ?? '').trim().toLowerCase();
}

function readSyncSettings(env) {
  return {
    baseUrl: env.JIRA_BASE_URL,
    token: env.JIRA_TOKEN,
    username: env.JIRA_USERNAME,
    projectKeys: parseProjectKeys(env.JIRA_PROJECT_KEYS || 'TAS'),
    inReviewStatus: env.JIRA_STATUS_IN_REVIEW || 'In Review',
    doneStatus: env.JIRA_STATUS_DONE || 'Done',
    doneBranches: String(env.JIRA_DONE_BRANCHES || 'develop,main,master')
      .split(/[\s,]+/)
      .filter(Boolean),
  };
}

/** Link text inside Jira wiki markup [text|url] must not contain its delimiters. */
function linkText(text) {
  return String(text ?? '').replace(/[[\]|{}]/g, ' ').replace(/\s+/g, ' ').trim();
}

/**
 * Decides what a pull request event means for its Jira issue: the status to
 * move it to (if any) and the comment to leave. Every comment starts with the
 * PR link: the AI review recognises and skips them (jira-context.js).
 */
function planSync(payload, settings) {
  const pr = payload.pull_request;
  if (!pr || pr.draft) {
    return null;
  }
  const prLink = `[PR #${pr.number} «${linkText(pr.title)}»|${pr.html_url}]`;
  const author = pr.user?.login ? ` от ${pr.user.login}` : '';
  const branches = `{{${pr.head?.ref}}} → {{${pr.base?.ref}}}`;

  switch (payload.action) {
    case 'opened':
      return { targetStatus: settings.inReviewStatus, message: `Открыт ${prLink}${author} (${branches}).` };
    // A push or a title edit only stands in for a missed "opened": it acts
    // once per PR, while the issue has no link to it yet.
    case 'synchronize':
      return { targetStatus: settings.inReviewStatus, message: `Открыт ${prLink}${author} (${branches}).`, firstSyncOnly: true };
    case 'edited':
      return payload.changes?.title
        ? { targetStatus: settings.inReviewStatus, message: `Открыт ${prLink}${author} (${branches}).`, firstSyncOnly: true }
        : null;
    case 'reopened':
      return { targetStatus: settings.inReviewStatus, message: `${prLink} переоткрыт (${branches}).` };
    case 'ready_for_review':
      return { targetStatus: settings.inReviewStatus, message: `${prLink}${author} готов к ревью (${branches}).` };
    case 'closed': {
      if (!pr.merged) {
        return { targetStatus: null, message: `${prLink} закрыт без мержа.` };
      }
      const commit = pr.merge_commit_sha ? `, merge-коммит {{${pr.merge_commit_sha.slice(0, 7)}}}` : '';
      const message = `${prLink} влит в {{${pr.base?.ref}}}${commit}.`;
      // Only a merge into a release branch finishes the work: a PR into
      // another feature branch (a stacked PR) does not.
      const finishes = settings.doneBranches.includes(pr.base?.ref);
      return { targetStatus: finishes ? settings.doneStatus : null, message };
    }
    default:
      return null;
  }
}

/**
 * Moves the issue to the status with the given name, through whichever
 * transition leads there. A finished issue is never moved back.
 */
async function moveIssue(request, key, targetStatus, settings) {
  const issue = await request({
    path: `rest/api/2/issue/${encodeURIComponent(key)}`,
    searchParams: { fields: 'status' },
  });
  const status = issue?.fields?.status ?? {};
  const current = status.name ?? '';
  if (sameName(current, targetStatus)) {
    return { changed: false, from: current, reason: 'already' };
  }
  const finished = status.statusCategory?.key === 'done' || sameName(current, settings.doneStatus);
  if (finished && !sameName(targetStatus, settings.doneStatus)) {
    return { changed: false, from: current, reason: 'finished' };
  }

  const { transitions = [] } = (await request({
    path: `rest/api/2/issue/${encodeURIComponent(key)}/transitions`,
  })) ?? {};
  const transition = transitions.find((item) => sameName(item.to?.name, targetStatus))
    ?? transitions.find((item) => sameName(item.name, targetStatus));
  if (!transition) {
    // Jira lists only the transitions this account may perform: an empty
    // list usually means a missing permission or a misspelled status name.
    return {
      changed: false,
      from: current,
      reason: 'no-transition',
      available: transitions.map((item) => item.to?.name ?? item.name),
    };
  }
  await request({
    method: 'POST',
    path: `rest/api/2/issue/${encodeURIComponent(key)}/transitions`,
    body: { transition: { id: String(transition.id) } },
  });
  return { changed: true, from: current, to: transition.to?.name ?? targetStatus };
}

function linkGlobalId(repository, pr) {
  return `github-pr:${repository}#${pr.number}`;
}

async function isLinked(request, key, globalId) {
  const links = await request({ path: `rest/api/2/issue/${encodeURIComponent(key)}/remotelink` });
  return Array.isArray(links) && links.some((link) => link.globalId === globalId);
}

/** Adds the PR to the issue's links; the globalId makes repeated events update it. */
function linkPullRequest(request, key, pr, repository) {
  return request({
    method: 'POST',
    idempotent: true,
    path: `rest/api/2/issue/${encodeURIComponent(key)}/remotelink`,
    body: {
      globalId: linkGlobalId(repository, pr),
      application: { type: 'com.github', name: 'GitHub' },
      relationship: 'pull request',
      object: {
        url: pr.html_url,
        title: `PR #${pr.number}: ${pr.title}`.slice(0, MAX_LINK_TITLE),
        icon: { url16x16: 'https://github.com/favicon.ico', title: 'GitHub' },
        status: { resolved: pr.state === 'closed' },
      },
    },
  });
}

/**
 * Keeps the Jira issue named in the PR title or branch in step with the PR:
 * In Review when the PR is opened, Done when it is merged into a release
 * branch, with a comment either way. Jira failures are warnings, never a
 * failed check.
 */
async function syncJiraIssue({ context, core, env = process.env, fetchImpl, retryDelays }) {
  const settings = readSyncSettings(env);
  if (!settings.baseUrl || !settings.token) {
    core.info('Jira sync is disabled: JIRA_BASE_URL or JIRA_TOKEN is not set.');
    return { skipped: 'not-configured' };
  }

  const pr = context.payload.pull_request;
  const key = extractIssueKey([pr?.title, pr?.head?.ref], settings.projectKeys);
  if (!key) {
    core.info('No Jira issue key found in the PR title or branch name.');
    return { skipped: 'no-key' };
  }
  const plan = planSync(context.payload, settings);
  if (!plan) {
    core.info(`Nothing to sync for "${context.payload.action}".`);
    return { skipped: 'no-action', key };
  }

  const request = (options) => jiraRequest({
    baseUrl: settings.baseUrl,
    token: settings.token,
    username: settings.username,
    fetchImpl,
    retryDelays,
    ...options,
  });
  const repository = `${context.repo.owner}/${context.repo.repo}`;

  if (plan.firstSyncOnly) {
    try {
      if (await isLinked(request, key, linkGlobalId(repository, pr))) {
        core.info(`${key} is already linked to PR #${pr.number}; nothing to do.`);
        return { skipped: 'already-synced', key };
      }
    } catch (error) {
      core.warning(`Could not read the links of ${key}: ${error.message}`);
      return { skipped: 'unknown-links', key };
    }
  }

  try {
    await linkPullRequest(request, key, pr, repository);
  } catch (error) {
    core.warning(`Could not link the PR to ${key}: ${error.message}`);
  }

  let move = null;
  if (plan.targetStatus) {
    try {
      move = await moveIssue(request, key, plan.targetStatus, settings);
      if (move.reason === 'no-transition') {
        core.warning(
          `No transition of ${key} from "${move.from}" to "${plan.targetStatus}"; available: `
          + `${move.available.join(', ') || 'none (does the Jira account have Transition Issues?)'}.`
        );
      } else if (!move.changed) {
        core.info(`${key} stays in "${move.from}" (${move.reason}).`);
      }
    } catch (error) {
      core.warning(`Could not move ${key} to "${plan.targetStatus}": ${error.message}`);
    }
  }

  const statusNote = move?.changed ? ` Статус: ${move.from} → ${move.to}.` : '';
  try {
    await request({
      method: 'POST',
      path: `rest/api/2/issue/${encodeURIComponent(key)}/comment`,
      body: { body: `${plan.message}${statusNote}` },
    });
  } catch (error) {
    core.warning(`Could not comment on ${key}: ${error.message}`);
  }

  core.info(`${key}: ${plan.message}${statusNote}`);
  return { key, plan, move };
}

module.exports = syncJiraIssue;
module.exports.moveIssue = moveIssue;
module.exports.planSync = planSync;
module.exports.readSyncSettings = readSyncSettings;
