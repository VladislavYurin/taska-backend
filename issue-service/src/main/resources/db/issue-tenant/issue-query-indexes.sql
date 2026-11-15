-- Порядок DONE-колонки внутри арендатора/проекта/типа/статуса, без переписывания прежних индексов.
CREATE INDEX issues_board_updated_cursor_active_idx ON taska.issues
 (tenant_id,project_id,issue_type,status_key,updated_at DESC,id DESC) WHERE deleted_at IS NULL;
