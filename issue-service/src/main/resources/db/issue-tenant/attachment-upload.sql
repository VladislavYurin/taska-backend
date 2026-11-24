SET LOCAL ROLE taska_issue_migrator;
CREATE TABLE taska.attachment_uploads(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,actor_id uuid NOT NULL,operation_id uuid NOT NULL,
 project_id uuid NOT NULL,issue_id uuid NOT NULL,request_key varchar(128) NOT NULL,request_hash text NOT NULL,
 content_type varchar(255) NOT NULL,declared_bytes bigint NOT NULL CHECK(declared_bytes>0),file_name varchar(255) NOT NULL,
 expires_at timestamptz NOT NULL,put_not_after timestamptz NOT NULL,staging_key text NOT NULL,final_key text NOT NULL,
 staging_version text,final_version text,confirm_requested boolean NOT NULL DEFAULT false,copy_started boolean NOT NULL DEFAULT false,accepted boolean NOT NULL DEFAULT false,maximum_attachment_bytes bigint,policy_version bigint,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,actor_id,request_key),UNIQUE(tenant_id,operation_id),UNIQUE(tenant_id,staging_key),
 FOREIGN KEY(tenant_id,project_id,issue_id) REFERENCES taska.issues(tenant_id,project_id,id),
 CHECK(put_not_after<=expires_at),CHECK(NOT accepted OR (maximum_attachment_bytes IS NOT NULL AND policy_version IS NOT NULL AND maximum_attachment_bytes>0 AND policy_version>0)),
 CHECK(final_version IS NULL OR staging_version IS NOT NULL),CHECK(NOT copy_started OR staging_version IS NOT NULL)
);
CREATE TABLE taska.issue_attachments(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,project_id uuid NOT NULL,issue_id uuid NOT NULL,uploaded_by uuid NOT NULL,
 file_name varchar(255) NOT NULL,content_type varchar(255) NOT NULL,size_bytes bigint NOT NULL CHECK(size_bytes>0),
 checksum text NOT NULL CHECK(checksum ~ '^[0-9a-f]{64}$'),created_at timestamptz NOT NULL,object_key text NOT NULL,object_version text NOT NULL,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,id) REFERENCES taska.attachment_object_manifest(tenant_id,id),
 FOREIGN KEY(tenant_id,project_id,issue_id) REFERENCES taska.issues(tenant_id,project_id,id)
);
CREATE FUNCTION taska.guard_attachment_upload() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' OR ROW(NEW.id,NEW.tenant_id,NEW.actor_id,NEW.operation_id,NEW.project_id,NEW.issue_id,NEW.request_key,NEW.request_hash,NEW.content_type,NEW.declared_bytes,NEW.file_name,NEW.expires_at,NEW.put_not_after,NEW.staging_key,NEW.final_key)
 IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.actor_id,OLD.operation_id,OLD.project_id,OLD.issue_id,OLD.request_key,OLD.request_hash,OLD.content_type,OLD.declared_bytes,OLD.file_name,OLD.expires_at,OLD.put_not_after,OLD.staging_key,OLD.final_key)
 OR (OLD.accepted AND (NOT NEW.accepted OR NEW.maximum_attachment_bytes IS DISTINCT FROM OLD.maximum_attachment_bytes OR NEW.policy_version IS DISTINCT FROM OLD.policy_version))
 OR (OLD.copy_started AND NOT NEW.copy_started)
 OR (OLD.confirm_requested AND NOT NEW.confirm_requested)
 OR (OLD.staging_version IS NOT NULL AND NEW.staging_version IS DISTINCT FROM OLD.staging_version)
 OR (OLD.final_version IS NOT NULL AND NEW.final_version IS DISTINCT FROM OLD.final_version)
 THEN RAISE EXCEPTION 'Immutable upload identity'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER immutable_attachment_upload BEFORE UPDATE OR DELETE ON taska.attachment_uploads FOR EACH ROW EXECUTE FUNCTION taska.guard_attachment_upload();
SELECT set_config('taska.guard_service','issue',true),set_config('taska.guard_schema','taska',true),set_config('taska.guard_table','attachment_uploads',true),set_config('taska.guard_column','tenant_id',true),set_config('taska.guard_app_access','read_write',true);
