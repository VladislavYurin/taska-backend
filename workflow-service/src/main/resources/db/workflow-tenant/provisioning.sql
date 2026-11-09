SET LOCAL ROLE taska_workflow_migrator;
CREATE TABLE taska.workflow_provisioning_receipts (
 tenant_id uuid NOT NULL, operation_id uuid NOT NULL, template_id uuid NOT NULL,
 template_copy_id uuid NOT NULL, PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,template_copy_id) REFERENCES taska.tenant_workflow_templates(tenant_id,id)
);
CREATE TRIGGER immutable_provisioning_receipt BEFORE UPDATE OR DELETE ON taska.workflow_provisioning_receipts
 FOR EACH ROW EXECUTE FUNCTION taska.immutable_workflow_template();
