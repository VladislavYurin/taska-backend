You are reviewing a pull request for the Taska backend, a Java 21 Spring Boot multi-module Maven project.

Review only the changes introduced by this pull request. Use the environment variables `PR_BASE_SHA`, `PR_HEAD_SHA`, and `PR_BASE_REF` to compare the PR against its base. Start with:

```bash
git diff --stat "$PR_BASE_SHA...$PR_HEAD_SHA"
git diff --name-status "$PR_BASE_SHA...$PR_HEAD_SHA"
git diff --find-renames --unified=80 "$PR_BASE_SHA...$PR_HEAD_SHA"
```

If the SHA comparison is not available, fall back to `origin/$PR_BASE_REF...HEAD`.

Focus on concrete, actionable review findings:

- correctness bugs and regressions
- broken service/module contracts
- security issues, auth mistakes, unsafe data exposure
- concurrency, transaction, idempotency, and consistency risks
- API/gRPC/schema compatibility problems
- migrations and configuration risks
- tests that are missing for changed behavior

Do not comment on formatting or style unless it creates a real maintainability or correctness problem. Do not rewrite the code and do not edit files.

Write the review in Russian. Use this exact structure:

## Кратко
Briefly summarize what the PR changes and the highest-risk areas.

## Что проверить вручную
List manual checks only if they are relevant.

## Замечания
For each finding, include severity (`critical`, `high`, `medium`, or `low`), file path, line or method if you can identify it, why it matters, and a specific fix. If there are no actionable findings, write `Я не нашел блокирующих замечаний по измененному diff.`

## Тесты
Say which tests are present or missing for the changed behavior.
