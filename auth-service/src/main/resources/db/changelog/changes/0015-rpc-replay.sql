--liquibase formatted sql
-- changeset taska:0015-rpc-replay
-- comment: Durable recipient-scoped assertion consumption shared across all recipient replicas.
CREATE TABLE taska.rpc_assertion_replay (
    recipient text NOT NULL,
    issuer text NOT NULL,
    assertion_id uuid NOT NULL,
    retain_until timestamptz NOT NULL,
    PRIMARY KEY (recipient,issuer,assertion_id)
);
CREATE INDEX rpc_assertion_replay_expiry_idx ON taska.rpc_assertion_replay(retain_until);
