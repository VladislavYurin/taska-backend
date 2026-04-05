-- liquibase formatted sql

-- changeset taska:0011-seed-default-workflow
-- comment: Seed дефолтного workflow (TODO -> IN_PROGRESS -> DONE + REOPEN)
INSERT INTO taska.workflows (id, name, version, is_active, created_at, updated_at)
VALUES ('11111111-1111-1111-1111-111111111111'::uuid, 'Default workflow', 1, TRUE, now(), now())
ON CONFLICT (id) DO NOTHING;

-- comment: Seed статусов дефолтного workflow
INSERT INTO taska.statuses (id, workflow_id, status_key, name, category, sort_order, created_at, updated_at)
VALUES
    ('22222222-2222-2222-2222-222222222222'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'TODO',        'To Do',       'TODO',        10, now(), now()),
    ('33333333-3333-3333-3333-333333333333'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'IN_PROGRESS', 'In Progress', 'IN_PROGRESS', 20, now(), now()),
    ('44444444-4444-4444-4444-444444444444'::uuid, '11111111-1111-1111-1111-111111111111'::uuid, 'DONE',        'Done',        'DONE',        30, now(), now())
ON CONFLICT (id) DO NOTHING;

-- comment: Seed переходов дефолтного workflow
INSERT INTO taska.transitions (id, workflow_id, from_status_id, to_status_id, name, is_hidden, sort_order, created_at, updated_at)
VALUES
    ('55555555-5555-5555-5555-555555555555'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
     '22222222-2222-2222-2222-222222222222'::uuid, '33333333-3333-3333-3333-333333333333'::uuid,
     'Start Progress', FALSE, 10, now(), now()),

    ('66666666-6666-6666-6666-666666666666'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
     '33333333-3333-3333-3333-333333333333'::uuid, '44444444-4444-4444-4444-444444444444'::uuid,
     'Complete', FALSE, 20, now(), now()),

    ('77777777-7777-7777-7777-777777777777'::uuid, '11111111-1111-1111-1111-111111111111'::uuid,
     '44444444-4444-4444-4444-444444444444'::uuid, '33333333-3333-3333-3333-333333333333'::uuid,
     'Reopen', FALSE, 30, now(), now())
ON CONFLICT (id) DO NOTHING;


-- changeset taska:0012-seed-default-workflow-bindings
-- comment: Seed привязок дефолтного workflow к projectId=0 для TASK/BUG/STORY
INSERT INTO taska.workflow_bindings (project_id, issue_type, workflow_id, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000000'::uuid, 'TASK',  '11111111-1111-1111-1111-111111111111'::uuid, now(), now()),
    ('00000000-0000-0000-0000-000000000000'::uuid, 'BUG',   '11111111-1111-1111-1111-111111111111'::uuid, now(), now()),
    ('00000000-0000-0000-0000-000000000000'::uuid, 'STORY', '11111111-1111-1111-1111-111111111111'::uuid, now(), now())
ON CONFLICT (project_id, issue_type) DO NOTHING;