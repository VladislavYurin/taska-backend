SET ROLE taska_project_migrator;
CREATE TABLE taska.projects (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_key text NOT NULL,
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,project_key)
);
CREATE TABLE taska.issues (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  issue_number bigint NOT NULL,
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,project_id,issue_number),
  FOREIGN KEY (tenant_id,project_id) REFERENCES taska.projects(tenant_id,id)
);
CREATE INDEX issues_tenant_project_idx ON taska.issues(tenant_id,project_id);
-- Global identity/catalog tables are explicit exclusions: no implicit NULL tenant exemption.
CREATE TABLE taska.global_catalog (id uuid PRIMARY KEY,name text NOT NULL);
RESET ROLE;
