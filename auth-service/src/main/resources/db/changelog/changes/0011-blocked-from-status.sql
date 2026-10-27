--liquibase formatted sql

-- changeset taska:0011-blocked-from-status
ALTER TABLE taska.users ADD COLUMN blocked_from_status varchar(16);
ALTER TABLE taska.users ADD CONSTRAINT users_blocked_from_status_chk
    CHECK (blocked_from_status IN ('INVITED', 'ACTIVE'));

-- Existing blocks have no lifecycle snapshot. Interactive credentials prove activation;
-- API tokens alone do not. OAuth-created accounts are active without a password.
UPDATE taska.users u
SET blocked_from_status = CASE WHEN u.created_via='OAUTH' OR EXISTS (
    SELECT 1 FROM taska.credentials c WHERE c.user_id=u.id AND
        ((c.credential_type='PASSWORD' AND c.secret_hash IS NOT NULL)
         OR c.credential_type IN ('OAUTH','OIDC','SAML','WEBAUTHN')))
    THEN 'ACTIVE' ELSE 'INVITED' END
WHERE u.status='BLOCKED';
