--liquibase formatted sql
--changeset taska:oauth-0001
CREATE TABLE oauth2_registered_client (
    id varchar(100) NOT NULL,
    client_id varchar(2048) NOT NULL,
    client_id_issued_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret varchar(200) DEFAULT NULL,
    client_secret_expires_at timestamptz DEFAULT NULL,
    client_name varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types varchar(1000) NOT NULL,
    redirect_uris text DEFAULT NULL,
    post_logout_redirect_uris text DEFAULT NULL,
    scopes varchar(1000) NOT NULL,
    client_settings text NOT NULL,
    token_settings text NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization (
    id varchar(100) NOT NULL,
    registered_client_id varchar(2048) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorization_grant_type varchar(100) NOT NULL,
    authorized_scopes varchar(1000) DEFAULT NULL,
    attributes text DEFAULT NULL,
    state varchar(500) DEFAULT NULL,
    authorization_code_value text DEFAULT NULL,
    authorization_code_issued_at timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata text DEFAULT NULL,
    access_token_value text DEFAULT NULL,
    access_token_issued_at timestamptz DEFAULT NULL,
    access_token_expires_at timestamptz DEFAULT NULL,
    access_token_metadata text DEFAULT NULL,
    access_token_type varchar(100) DEFAULT NULL,
    access_token_scopes varchar(1000) DEFAULT NULL,
    oidc_id_token_value text DEFAULT NULL,
    oidc_id_token_issued_at timestamptz DEFAULT NULL,
    oidc_id_token_expires_at timestamptz DEFAULT NULL,
    oidc_id_token_metadata text DEFAULT NULL,
    refresh_token_value text DEFAULT NULL,
    refresh_token_issued_at timestamptz DEFAULT NULL,
    refresh_token_expires_at timestamptz DEFAULT NULL,
    refresh_token_metadata text DEFAULT NULL,
    user_code_value text DEFAULT NULL,
    user_code_issued_at timestamptz DEFAULT NULL,
    user_code_expires_at timestamptz DEFAULT NULL,
    user_code_metadata text DEFAULT NULL,
    device_code_value text DEFAULT NULL,
    device_code_issued_at timestamptz DEFAULT NULL,
    device_code_expires_at timestamptz DEFAULT NULL,
    device_code_metadata text DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id varchar(2048) NOT NULL,
    principal_name varchar(200) NOT NULL,
    authorities varchar(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);

CREATE UNIQUE INDEX oauth_client_id_unique ON oauth2_registered_client(client_id);
CREATE TABLE oauth_registration_lock (id integer PRIMARY KEY);
INSERT INTO oauth_registration_lock(id) VALUES (1);
CREATE TABLE oauth_refresh_history (
    token_hash varchar(64) PRIMARY KEY,
    grant_id varchar(100) NOT NULL REFERENCES oauth2_authorization(id) ON DELETE CASCADE,
    used_at timestamptz NULL,
    family_expires_at timestamptz NOT NULL
);
CREATE INDEX oauth_refresh_history_grant_idx ON oauth_refresh_history(grant_id);

-- Opaque lookups must not scan grants on every MCP request or refresh/code exchange.
CREATE INDEX oauth_access_token_idx ON oauth2_authorization(access_token_value) WHERE access_token_value IS NOT NULL;
CREATE INDEX oauth_refresh_token_idx ON oauth2_authorization(refresh_token_value) WHERE refresh_token_value IS NOT NULL;
CREATE INDEX oauth_authorization_code_idx ON oauth2_authorization(authorization_code_value) WHERE authorization_code_value IS NOT NULL;
CREATE INDEX oauth_consent_state_idx ON oauth2_authorization(state) WHERE state IS NOT NULL;
CREATE INDEX oauth_grant_owner_idx ON oauth2_authorization(principal_name);
