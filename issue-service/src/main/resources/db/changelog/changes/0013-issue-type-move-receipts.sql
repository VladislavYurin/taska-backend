--liquibase formatted sql
-- changeset taska:0013-issue-type-move-receipts
-- APPLIED receipts are permanent generation tombstones, retained with cancellation receipts.
CREATE TABLE taska.issue_type_move_receipts (
    operation_id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    source_code text NOT NULL,
    source_generation uuid NOT NULL,
    target_code text,
    target_generation uuid,
    state text NOT NULL CHECK (state IN ('APPLIED','CANCELLED')),
    moved_count bigint NOT NULL DEFAULT 0 CHECK (moved_count >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX issue_type_write_tombstone_idx
    ON taska.issue_type_move_receipts(project_id,source_code,source_generation) WHERE state='APPLIED';
