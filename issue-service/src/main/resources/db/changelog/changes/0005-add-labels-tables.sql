--liquibase formatted sql

-- changeset taska:0005-add-labels-tables
-- comment: Добавление таблицы меток (labels) и добавление меток в issue_history(event_type)

-- Таблица для меток (labels)
CREATE TABLE IF NOT EXISTS taska.project_labels
(
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL,
    name text NOT NULL,
    color text NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz NULL,

    -- Имя не должно быть пустым после trim
    CONSTRAINT check_label_name_not_empty CHECK ( length(trim(name)) >0 )
);
-- Уникальность имени внутри проекта (case-insensitive + trim)
-- Учитывает soft-delete: можно создать метку со старым именем, если прошлая удалена
-- ВАЖНО! При запросах ставить условие deleted_at IS NULL, иначе будут возвращаться удаленные лейблы
CREATE UNIQUE INDEX IF NOT EXISTS unique_project_label_active_name_index
    ON taska.project_labels (project_id,lower(trim(name)))
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_project_labels_project_id
    ON taska.project_labels (project_id);

-- Таблица связей меток с задачами (Many-to-Many)
CREATE TABLE IF NOT EXISTS taska.issue_labels
(
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id uuid NOT NULL REFERENCES taska.issues(id) ON DELETE CASCADE,
    label_id uuid NOT NULL REFERENCES taska.project_labels(id) ON DELETE CASCADE,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT unique_issue_label_pair UNIQUE (issue_id, label_id)
);
CREATE INDEX IF NOT EXISTS idx_issue_labels_label_id
    ON taska.issue_labels (label_id);

-- Issue History. Добавление в issue_history событий по меткам (лейблам): LABEL_ADDED, LABEL_REMOVED
ALTER TABLE taska.issue_history
    DROP CONSTRAINT IF EXISTS issue_history_event_type_chk;

ALTER TABLE taska.issue_history
    ADD CONSTRAINT issue_history_event_type_chk
        CHECK (event_type IN
               ('CREATED', 'UPDATED', 'ASSIGNED', 'TRANSITIONED', 'DELETED',
                'LINK_CREATED', 'LINK_DELETED',
                'ATTACHMENT_UPLOADED', 'ATTACHMENT_DELETED',
                'COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED',
                'LABEL_ADDED', 'LABEL_REMOVED')
            );