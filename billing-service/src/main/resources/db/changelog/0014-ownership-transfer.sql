SET LOCAL ROLE taska_billing_migrator;
CREATE TABLE taska.ownership_transfer_fences (
 tenant_id uuid NOT NULL,operation_id uuid NOT NULL,old_owner_id uuid NOT NULL,
 old_owner_version bigint NOT NULL,valid_until timestamptz NOT NULL,
 entitlement_version bigint NOT NULL,administrative boolean NOT NULL,
 state text NOT NULL CHECK(state IN ('PREPARED','FINISHED','CANCELLED')),
 created_at timestamptz NOT NULL,finished_at timestamptz,
 PRIMARY KEY(tenant_id,operation_id),CHECK(valid_until>created_at),
 CHECK(old_owner_version>0 AND entitlement_version>0)
);
ALTER TABLE taska.subscriptions ADD COLUMN ownership_transfer_id uuid;
ALTER TABLE taska.subscriptions ADD COLUMN ownership_transfer_until timestamptz;
ALTER TABLE taska.subscriptions ADD CHECK((ownership_transfer_id IS NULL)=(ownership_transfer_until IS NULL));

-- Изменение самого fence допускается отдельно от финансовых полей подписки.
CREATE FUNCTION taska.guard_subscription_ownership_transfer() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF OLD.ownership_transfer_id IS NOT NULL AND
   (to_jsonb(NEW)-ARRAY['ownership_transfer_id','ownership_transfer_until']) IS DISTINCT FROM
   (to_jsonb(OLD)-ARRAY['ownership_transfer_id','ownership_transfer_until']) THEN
  RAISE EXCEPTION 'OWNERSHIP_TRANSFER_PENDING' USING ERRCODE='40001';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ownership_transfer_subscription BEFORE UPDATE ON taska.subscriptions
 FOR EACH ROW EXECUTE FUNCTION taska.guard_subscription_ownership_transfer();
CREATE FUNCTION taska.guard_period_ownership_transfer() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE target uuid; pending uuid;
BEGIN
 target:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
 SELECT ownership_transfer_id INTO pending FROM taska.subscriptions WHERE tenant_id=target FOR UPDATE;
 IF pending IS NOT NULL THEN
  RAISE EXCEPTION 'OWNERSHIP_TRANSFER_PENDING' USING ERRCODE='40001';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ownership_transfer_period BEFORE INSERT OR UPDATE OR DELETE ON taska.subscription_periods
 FOR EACH ROW EXECUTE FUNCTION taska.guard_period_ownership_transfer();
