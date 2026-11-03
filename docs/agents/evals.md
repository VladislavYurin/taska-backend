# Регрессионные evals harness

TAS-456 проверяет наблюдаемое поведение CLI, E2E lifecycle и handoff validator на воспроизводимых локальных fixtures. Это проверки механики harness; они не подтверждают бизнес-приёмку продукта, реальную доставку email/Telegram, совместимость внешнего клиента или устойчивость языковой модели к prompt injection.

## Запуск и результаты

Нужны Python 3.9+ и Git. Docker, credentials, сеть и работающий Taska для этого набора не нужны. Требуются реализации TAS-453, TAS-454 и TAS-455 в проверяемом checkout.

```bash
python3 -B scripts/harness/eval_runner.py
python3 -B scripts/harness/eval_runner.py --case review-stale-head --case cleanup-foreign-owner
python3 scripts/harness.py check --check harness.evals
python3 scripts/harness.py check --group harness
```

`harness.evals` входит в versioned [verification manifest](../../scripts/harness/verification.json), в группу `harness`. PR CI выполняет ту же группу, поэтому для evals не существует отдельного списка обязательных cases в YAML. По умолчанию runner выполняет весь [каталог](../../scripts/harness/evals/cases.json); неизвестный `--case` отклоняется. Изменять workflow deployment или создавать периодические jobs не требуется.

Результат сохраняется в `target/harness-evals/<runId>/report.json`, с правами 0600; уникальный каталог имеет 0700. В stdout только санитизированное резюме и относительный путь. Отчёт и замеры не коммитятся. Каждый запуск записывает source head/tree, признак dirty, длительность cases и метрики. Dirty означает, что HEAD tree не описывает все рабочие изменения; для evidence финального PR повторить на чистом checkout его head.

Два статуса нужны для правильного чтения отрицательных кейсов:

- `observedOutcome` — фактическое решение harness: PASS/FAIL/PARTIAL/BLOCKED.
- `evaluationStatus` — совпало ли решение с ожиданием fixture и сохранены ли проверяемые инварианты. Например, отсутствие mailbox должно дать BLOCKED; корректное обнаружение этого пробела означает PASS самого eval.

Недоступная реализация C/D даёт BLOCKED eval, даже если конкретная fixture ожидает BLOCKED: отсутствие тестируемого компонента не считается успешным тестом. Неожиданное исключение — FAIL, без публикации текста исключения. Общий exit code: 0 при успехе всех выбранных evals, 1 при регрессии, 3 при отсутствующей реализации, 2 при неверном вызове/конфигурации. Пробелы не превращаются в skip.

## Что проверяется

| Fixtures | Проверяемая реализация и наблюдение |
|---|---|
| IT typo/zero/stale/skipped, позитивный отчёт | `harness.test_reports`: свежие XML, фактическое число тестов по каждому selector, причина неполной проверки |
| Stale review, truncated diff, отсутствующий/неприменимый обязательный check | `workflows.validate_handoff`: независимые ожидаемые head/check IDs, причины PARTIAL, отсутствие ложного PASS |
| Untrusted Jira/PR text | Текст остаётся данными `untrustedContext`; validator не выполняет вложенную команду и не выводит её/секрет-маркер. Это тест границы обработки данных, не LLM-eval |
| Подмена state/среды | `StateStore.load`: изменение подписанного JSON не допускается до cleanup |
| Подмена Docker daemon | `Local.verify_daemon`: смена Docker context не доказывает отсутствие собственных ресурсов; очистка отклоняется, исходный checkpoint сохраняется |
| Чужой Docker owner | `Local.cleanup`: весь список объектов проверяется до первой операции удаления; Docker transport подменён собственной in-memory fixture |
| Прерывание/повтор cleanup | Реальный дочерний Python сохраняет state и аварийно завершается через `os._exit`; новый экземпляр читает checkpoint и дважды выполняет cleanup через собственный fake Docker transport |
| Смена revision, mailbox отсутствует | Реальные `revision_result`/`capability_result`, PARTIAL/BLOCKED вместо PASS |
| appId/client_id | UUID внутреннего app ID и публичный URL client_id сохраняются отдельно; перестановка недопустимых типов отклоняется |
| Headers/пустой 403, business projection | Регистронезависимые headers; пустой body не разбирается как JSON; изменчивые presigned URLs исключены, бизнес-URL сохраняется |
| Timeout после записи | Fake HTTP transport фиксирует commit и возвращает timeout; `Http` выдаёт UnknownWrite и не повторяет POST автоматически |
| Redaction | Реальный неуспешный subprocess печатает синтетические secrets; общедоступная диагностика их не содержит |
| CI/local parity | Каждый check manifest попадает в реальные CI routes; проверка распознаёт используемые в CI прямые `run:` команды, исключает комментарии; новые shell/YAML формы требуют обновления recognizer |

Fixtures не внедряют сбои на stage. Имена/ID/email/секреты — синтетические; API адреса `.invalid` никогда не вызываются: HTTP transport заменён до запроса. State и temporary repos удаляются при выходе. Реальная локальная Compose E2E остаётся отдельной проверкой TAS-454.

## Baseline без вымышленных порогов

Первый полный запуск на чистом head служит baseline этого checkout/машины. Сохранить его локальный `report.json`, версию Python и сведения о машине рядом вне Git; последующие отчёты сравнивать при одинаковом составе cases. Порогов времени до измерений нет.

Runner измеряет длительность полного набора, время до первого завершённого fixture с проверяемым исходом (`secondsToFirstEvaluatedFixture`), долю положительных **fixture** cases с полностью подтверждённым результатом и число ложных PASS в отрицательных cases. Это не HTTP operation coverage и не доля позитивных продуктовых E2E; последние берутся из E2E отчёта отдельно. Метрика `manualActions` равна `null`: количество ручных действий при настройке и работе агента требует отдельного наблюдения человеком. Нельзя подставлять ноль, выдавая отсутствие измерений за результат.

Для agent-level baseline человек фиксирует отдельно: исходный clean SHA, prompt задачи, клиент/версию, время до первого полезного продуктового теста, число реально потребовавшихся вмешательств, выполненные позитивные сценарии и оставшиеся пробелы. Не публиковать transcripts с секретами; в PR достаточно санитизированного резюме и точного scope.

## Добавление case

1. В [cases.json](../../scripts/harness/evals/cases.json) добавить стабильный ID, область, ожидаемый исход и наблюдаемую цель. Входные fixtures хранятся в той же папке; реальные credentials запрещены.
2. Добавить статический обработчик в [eval_runner.py](../../scripts/harness/eval_runner.py), вызывающий production API harness. Не писать альтернативную копию проверяемого guard и не исполнять строки из fixtures.
3. Проверить успех, ожидаемый отказ и сохранность состояния. При удалении чужого объекта/записи после timeout проверять отсутствие побочного эффекта, а не только исключение.
4. Выполнить весь набор и `check --group harness`. В [test_evals.py](../../scripts/harness/test_evals.py) уже есть mutation-проверка: подмена реальных report/OAuth/HTTP guards на ложный PASS и удаление бизнес-URL из projection приводят к FAIL eval suite. Корректный отказ в негативном кейсе даёт `evaluationStatus=PASS`, но не увеличивает `falsePassCount`; неожиданный `observedOutcome=PASS` увеличивает счётчик и даёт `evaluationStatus=FAIL`.
5. Обновить карту выше и сравнить новый baseline. Файлы результатов, state и метрики оставить вне Git.
