--liquibase formatted sql

-- changeset taska:0001-default-workflow-missing-transitions
-- comment: Недостающие переходы default workflow: IN_PROGRESS -> TODO, TODO -> DONE, DONE -> TODO

INSERT INTO taska.transitions (id, workflow_id, from_status_id, to_status_id, name, is_hidden, sort_order, created_at,
                               updated_at)
VALUES ('88888888-8888-8888-8888-888888888888'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '33333333-3333-3333-3333-333333333333'::uuid, '22222222-2222-2222-2222-222222222222'::uuid,
        'Stop Progress', false, 40, now(), now()),

       ('99999999-9999-9999-9999-999999999999'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '22222222-2222-2222-2222-222222222222'::uuid, '44444444-4444-4444-4444-444444444444'::uuid,
        'Close', false, 50, now(), now()),

       ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
        '44444444-4444-4444-4444-444444444444'::uuid, '22222222-2222-2222-2222-222222222222'::uuid,
        'Back to To Do', false, 60, now(), now())
ON CONFLICT (id) DO NOTHING;

-- changeset taska:0001-transitions-from-to-dedupe
-- comment: Перед уникальным индексом убрать дубли пары статусов, оставив самый ранний переход: через API дубль не создать, но ручная вставка не должна блокировать деплой

DELETE
FROM taska.transitions t
    USING taska.transitions keep
WHERE t.workflow_id = keep.workflow_id
  AND t.from_status_id = keep.from_status_id
  AND t.to_status_id = keep.to_status_id
  AND (keep.created_at, keep.id) < (t.created_at, t.id);

-- changeset taska:0001-transitions-from-to-uniq
-- comment: Один переход на пару статусов внутри workflow: переходы теперь добавляются по одному через API

CREATE UNIQUE INDEX IF NOT EXISTS transitions_workflow_from_to_uniq
    ON taska.transitions (workflow_id, from_status_id, to_status_id);
