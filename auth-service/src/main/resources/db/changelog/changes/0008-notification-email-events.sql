--liquibase formatted sql
-- changeset taska:0008-notification-email-events splitStatements:false
-- Deploy the TAS-391 UserEmailChanged consumer before applying this changeset.
CREATE FUNCTION taska.valid_notification_email(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT value IS NOT NULL AND char_length(value) BETWEEN 1 AND 320
        AND value !~ '^[[:space:]]*$' AND value !~ U&'[\0001-\001F\007F-\009F]'
$$;

INSERT INTO taska.outbox_events(aggregate_type,aggregate_id,event_type,payload,request_id)
SELECT 'user',id,'UserEmailChanged',jsonb_build_object('userId',id,'email',email),'TAS-391-backfill'
FROM taska.users WHERE status IN ('ACTIVE','LOCKED') AND taska.valid_notification_email(email);

-- A database trigger covers future email writes, including repository.save and direct SQL,
-- in the same transaction. No email mutation API is introduced here.
CREATE FUNCTION taska.publish_user_email_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.email IS DISTINCT FROM NEW.email AND taska.valid_notification_email(NEW.email) THEN
        INSERT INTO taska.outbox_events(aggregate_type,aggregate_id,event_type,payload,request_id)
        VALUES ('user',NEW.id,'UserEmailChanged',jsonb_build_object('userId',NEW.id,'email',NEW.email),'TAS-391-email-change');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER users_email_changed AFTER UPDATE OF email ON taska.users
FOR EACH ROW EXECUTE FUNCTION taska.publish_user_email_changed();
