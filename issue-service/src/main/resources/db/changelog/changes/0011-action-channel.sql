--liquibase formatted sql
--changeset taska:0011-action-channel
ALTER TABLE taska.issue_history ADD COLUMN channel varchar(32) NULL,
    ADD COLUMN token_id uuid NULL,
    ADD CONSTRAINT issue_history_channel_chk CHECK (channel IS NULL OR channel IN ('UI','API_TOKEN','MCP'));
ALTER TABLE taska.issue_comments ADD COLUMN channel varchar(32) NULL,
    ADD COLUMN token_id uuid NULL,
    ADD CONSTRAINT issue_comments_channel_chk CHECK (channel IS NULL OR channel IN ('UI','API_TOKEN','MCP'));
