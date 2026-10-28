--liquibase formatted sql

-- changeset taska:0012-transitions-sort-order-dedupe
-- comment: Перед уникальным индексом убрать дубли переходов с одинаковым sort_order

-- Переход с validator_rules не удаляется никогда; из дублей остаётся переход с правилами,
-- иначе самый ранний. Если правила есть у двух дублей одного sort_order, они остаются,
-- индекс ниже не создастся, и такой случай разбирается вручную.
WITH ranked AS (SELECT t.id,
                       EXISTS (SELECT 1 FROM taska.validator_rules r WHERE r.transition_id = t.id) AS has_rules,
                       row_number() OVER (
                           PARTITION BY t.workflow_id, t.sort_order
                           ORDER BY EXISTS (SELECT 1 FROM taska.validator_rules r WHERE r.transition_id = t.id) DESC,
                               t.created_at, t.id)                                                 AS rn
                FROM taska.transitions t)
DELETE
FROM taska.transitions t
    USING ranked
WHERE t.id = ranked.id
  AND ranked.rn > 1
  AND NOT ranked.has_rules;

-- changeset taska:0012-transitions-sort-order-uniq
-- preconditions onFail:HALT onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM (SELECT 1 FROM taska.transitions GROUP BY workflow_id, sort_order HAVING COUNT(*) > 1) duplicates
-- comment: Уникальный sort_order переходов внутри workflow

-- Если миграция остановилась здесь с "SQL Precondition failed. Expected '0' got 'N'": после
-- 0012-transitions-sort-order-dedupe остались N пар дублей, у которых validator_rules есть больше
-- чем у одного перехода. Найти их: SELECT workflow_id, sort_order, array_agg(id) FROM taska.transitions
-- GROUP BY 1, 2 HAVING COUNT(*) > 1; решить, чьи правила оставить, удалить лишние переходы
-- и перезапустить.

CREATE UNIQUE INDEX IF NOT EXISTS transitions_workflow_sort_order_uniq
    ON taska.transitions (workflow_id, sort_order);

-- changeset taska:0012-statuses-sort-order-dedupe
-- comment: Перед уникальным индексом убрать дубли статусов с одинаковым sort_order

-- Статус TODO не удаляется: issue-service создаёт в нём задачи. Статус, на который ссылаются
-- переходы, тоже не удаляется (ON DELETE CASCADE унёс бы переходы). Из дублей остаётся TODO,
-- иначе статус с переходами, иначе самый ранний. Если так нельзя удалить все лишние дубли,
-- индекс ниже не создастся, и такой случай разбирается вручную.
WITH ranked AS (SELECT s.id,
                       (s.status_key = 'TODO')              AS is_todo,
                       EXISTS (SELECT 1
                               FROM taska.transitions t
                               WHERE t.from_status_id = s.id
                                  OR t.to_status_id = s.id) AS has_transitions,
                       row_number() OVER (
                           PARTITION BY s.workflow_id, s.sort_order
                           ORDER BY (s.status_key = 'TODO') DESC,
                               EXISTS (SELECT 1
                                       FROM taska.transitions t
                                       WHERE t.from_status_id = s.id
                                          OR t.to_status_id = s.id) DESC,
                               s.created_at, s.id)          AS rn
                FROM taska.statuses s)
DELETE
FROM taska.statuses s
    USING ranked
WHERE s.id = ranked.id
  AND ranked.rn > 1
  AND NOT ranked.is_todo
  AND NOT ranked.has_transitions;

-- changeset taska:0012-statuses-sort-order-uniq
-- preconditions onFail:HALT onError:HALT
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM (SELECT 1 FROM taska.statuses GROUP BY workflow_id, sort_order HAVING COUNT(*) > 1) duplicates
-- comment: Уникальный sort_order статусов внутри workflow

-- Если миграция остановилась здесь: после 0012-statuses-sort-order-dedupe остались дубли,
-- которые нельзя безопасно удалить автоматически (TODO или статус с переходами). Найти их:
-- SELECT s.workflow_id, s.sort_order, array_agg(s.id), bool_or(s.status_key='TODO')
-- FROM taska.statuses s GROUP BY 1, 2 HAVING COUNT(*) > 1;
-- Перенести/удалить переходы, проверить задачи в issue-service, затем удалить лишний статус
-- и перезапустить миграцию.

CREATE UNIQUE INDEX IF NOT EXISTS statuses_workflow_sort_order_uniq
    ON taska.statuses (workflow_id, sort_order);
