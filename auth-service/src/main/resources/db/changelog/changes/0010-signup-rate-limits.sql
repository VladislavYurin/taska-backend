--liquibase formatted sql
-- changeset taska:0010-signup-rate-limits
CREATE TABLE taska.signup_rate_limits (
    bucket text PRIMARY KEY,
    started_at timestamptz NOT NULL,
    attempts int NOT NULL CHECK (attempts > 0)
);
CREATE INDEX signup_rate_limits_cleanup_idx ON taska.signup_rate_limits(started_at);
