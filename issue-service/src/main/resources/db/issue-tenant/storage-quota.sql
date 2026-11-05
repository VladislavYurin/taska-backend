SET LOCAL ROLE taska_issue_migrator;
CREATE TABLE taska.resource_operation_receipts(
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,resource_kind text NOT NULL CHECK(resource_kind='STORAGE_BYTES'),resource_id uuid NOT NULL,
 reservation_id uuid NOT NULL,reservation_fence bigint NOT NULL CHECK(reservation_fence>0),state text NOT NULL CHECK(state IN ('COMMITTED','CANCELLED','DELETED')),
 amount bigint NOT NULL CHECK(amount>0),committed_at timestamptz,PRIMARY KEY(tenant_id,operation_id,resource_kind),
 UNIQUE(tenant_id,resource_kind,resource_id,reservation_id),UNIQUE(tenant_id,resource_kind,resource_id)
);
CREATE TABLE taska.attachment_object_manifest(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,project_id uuid NOT NULL,issue_id uuid NOT NULL,actor_id uuid NOT NULL,
 object_key text NOT NULL UNIQUE,staging_object_key text NOT NULL UNIQUE,reservation_id uuid NOT NULL,operation_id uuid NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),state text NOT NULL CHECK(state IN ('RESERVED','UPLOADED','CONFIRMED','DELETE_PENDING','DELETED','EXPIRED')),
 expected_bytes bigint NOT NULL CHECK(expected_bytes>0),confirmed_bytes bigint,content_type text NOT NULL,checksum_sha256 text,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL,delete_lease_id uuid,delete_lease_until timestamptz,
 staging_version_id text,final_object_version text,lease_epoch bigint NOT NULL DEFAULT 1 CHECK(lease_epoch>0),
 maximum_attachment_bytes bigint NOT NULL CHECK(maximum_attachment_bytes>0),policy_version bigint NOT NULL CHECK(policy_version>0),
 request_hash text NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),put_not_after timestamptz,reservation_version bigint NOT NULL CHECK(reservation_version>0),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,project_id,issue_id) REFERENCES taska.issues(tenant_id,project_id,id),
 CHECK(expected_bytes<=maximum_attachment_bytes),CHECK(confirmed_bytes IS NULL OR confirmed_bytes BETWEEN 1 AND expected_bytes),
 CHECK(checksum_sha256 IS NULL OR checksum_sha256 ~ '^[0-9a-f]{64}$'),
 CHECK(state NOT IN ('CONFIRMED','DELETE_PENDING','DELETED') OR (final_object_version IS NOT NULL AND staging_version_id IS NOT NULL AND checksum_sha256 IS NOT NULL AND confirmed_bytes IS NOT NULL)),
 CHECK(put_not_after IS NULL OR put_not_after<=expires_at)
);
CREATE INDEX storage_manifest_batch ON taska.attachment_object_manifest(tenant_id,id);
CREATE INDEX storage_manifest_cleanup ON taska.attachment_object_manifest(tenant_id,state,expires_at,id);
CREATE TABLE taska.storage_manifest_versions(tenant_id uuid PRIMARY KEY,version bigint NOT NULL DEFAULT 1 CHECK(version>0));
CREATE TABLE taska.storage_quota_outbox(tenant_id uuid NOT NULL,operation_id uuid NOT NULL,event_type text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),actor_id uuid,issuer text NOT NULL,PRIMARY KEY(tenant_id,operation_id,event_type));
CREATE FUNCTION taska.guard_storage_manifest_identity() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Storage manifest is retained until confirmed lifecycle purge'; END IF;
 IF (NEW.tenant_id,NEW.id,NEW.project_id,NEW.issue_id,NEW.actor_id,NEW.object_key,NEW.staging_object_key,NEW.reservation_id,NEW.operation_id,NEW.generation,NEW.expected_bytes,NEW.content_type,NEW.expires_at,NEW.created_at,NEW.maximum_attachment_bytes,NEW.policy_version,NEW.request_hash)
 IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.project_id,OLD.issue_id,OLD.actor_id,OLD.object_key,OLD.staging_object_key,OLD.reservation_id,OLD.operation_id,OLD.generation,OLD.expected_bytes,OLD.content_type,OLD.expires_at,OLD.created_at,OLD.maximum_attachment_bytes,OLD.policy_version,OLD.request_hash)
 OR (OLD.confirmed_bytes IS NOT NULL AND NEW.confirmed_bytes IS DISTINCT FROM OLD.confirmed_bytes)
 OR (OLD.final_object_version IS NOT NULL AND NEW.final_object_version IS DISTINCT FROM OLD.final_object_version)
 OR (OLD.staging_version_id IS NOT NULL AND NEW.staging_version_id IS DISTINCT FROM OLD.staging_version_id)
 OR (OLD.checksum_sha256 IS NOT NULL AND NEW.checksum_sha256 IS DISTINCT FROM OLD.checksum_sha256)
 OR (OLD.put_not_after IS NOT NULL AND NEW.put_not_after IS DISTINCT FROM OLD.put_not_after)
 OR (OLD.state IN ('DELETED','EXPIRED') AND NEW.state<>OLD.state)
 OR (OLD.state='CONFIRMED' AND NEW.state NOT IN ('CONFIRMED','DELETE_PENDING','DELETED'))
 OR (OLD.state='DELETE_PENDING' AND NEW.state NOT IN ('DELETE_PENDING','DELETED'))
 OR NEW.lease_epoch<OLD.lease_epoch
 THEN RAISE EXCEPTION 'Storage identity/measurement is immutable'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_storage_manifest_identity BEFORE UPDATE OR DELETE ON taska.attachment_object_manifest FOR EACH ROW EXECUTE FUNCTION taska.guard_storage_manifest_identity();
CREATE FUNCTION taska.guard_storage_resource_receipt() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $body$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Storage resource receipt must survive removal'; END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.resource_kind,NEW.resource_id,NEW.reservation_id,NEW.reservation_fence,NEW.amount,NEW.committed_at)
 IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.resource_kind,OLD.resource_id,OLD.reservation_id,OLD.reservation_fence,OLD.amount,OLD.committed_at)
 OR (OLD.state IN ('CANCELLED','DELETED') AND NEW IS DISTINCT FROM OLD)
 THEN RAISE EXCEPTION 'Storage resource receipt is immutable'; END IF;
 RETURN NEW;
END $body$;
CREATE TRIGGER guard_storage_resource_receipt BEFORE UPDATE OR DELETE ON taska.resource_operation_receipts FOR EACH ROW EXECUTE FUNCTION taska.guard_storage_resource_receipt();
