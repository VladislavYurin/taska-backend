--liquibase formatted sql

-- changeset taska:0001-issue-links-modify
-- comment: Добавление полей project_id и deleted_at в taska.issue_links, а так же ограничений для taska.issue_history

-- Issue Link
ALTER TABLE taska.issue_links
    ADD COLUMN IF NOT EXISTS project_id uuid;

ALTER TABLE taska.issue_links
    ADD COLUMN IF NOT EXISTS deleted_at timestamptz;

ALTER TABLE taska.issue_links
    DROP CONSTRAINT IF EXISTS issue_links_link_type_chk;

ALTER TABLE taska.issue_links
    ADD CONSTRAINT issue_links_link_type_chk
        CHECK (link_type IN ('BLOCKS', 'RELATES_TO', 'DUPLICATES'));

ALTER TABLE taska.issue_links
    DROP CONSTRAINT IF EXISTS issue_links_unique;

CREATE UNIQUE INDEX IF NOT EXISTS issue_links_directed_unique_active_idx
    ON taska.issue_links (source_issue_id, target_issue_id, link_type)
    WHERE deleted_at IS NULL
        AND link_type IN ('BLOCKS', 'DUPLICATES');


CREATE UNIQUE INDEX IF NOT EXISTS issue_links_relates_unique_active_idx
    ON taska.issue_links (least(source_issue_id, target_issue_id), greatest(source_issue_id, target_issue_id))
    WHERE deleted_at IS NULL
        AND link_type = 'RELATES_TO';

CREATE INDEX IF NOT EXISTS issue_links_active_source_idx
    ON taska.issue_links (source_issue_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS issue_links_active_target_idx
    ON taska.issue_links (target_issue_id)
    WHERE deleted_at IS NULL;

-- Issue History
ALTER TABLE taska.issue_history
    DROP CONSTRAINT IF EXISTS issue_history_event_type_chk;

ALTER TABLE taska.issue_history
    ADD CONSTRAINT issue_history_event_type_chk
        CHECK (event_type IN
               ('CREATED', 'UPDATED', 'ASSIGNED', 'TRANSITIONED', 'DELETED',
                'LINK_CREATED', 'LINK_DELETED',
                'ATTACHMENT_UPLOADED', 'ATTACHMENT_DELETED',
                'COMMENT_CREATED', 'COMMENT_UPDATED', 'COMMENT_DELETED')
            );
