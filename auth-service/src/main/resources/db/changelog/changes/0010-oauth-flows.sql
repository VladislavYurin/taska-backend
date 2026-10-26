--liquibase formatted sql
-- changeset taska:0010-oauth-flows
CREATE TABLE taska.oauth_states (
 state_hash char(64) PRIMARY KEY,
 browser_hash char(64) NOT NULL,
 provider varchar(10) NOT NULL,
 nonce varchar(128) NOT NULL,
 verifier varchar(128) NOT NULL,
 return_to varchar(1024) NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE INDEX oauth_states_expiry_idx ON taska.oauth_states(expires_at);
CREATE TABLE taska.oauth_grants (
 code_hash char(64) PRIMARY KEY,
 kind varchar(10) NOT NULL CHECK(kind IN ('EXCHANGE','SIGNUP')),
 provider varchar(10) NOT NULL,
 subject varchar(512) NOT NULL,
 email varchar(320) NOT NULL,
 suggested_name varchar(128),
 return_to varchar(1024) NOT NULL,
 expires_at timestamptz NOT NULL
);
CREATE INDEX oauth_grants_expiry_idx ON taska.oauth_grants(expires_at);
