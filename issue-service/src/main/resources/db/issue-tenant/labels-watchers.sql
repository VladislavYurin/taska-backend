SET LOCAL ROLE taska_issue_migrator;

-- Составные ключи не позволяют связать ресурсы разных проектов даже напрямую через app LOGIN.
ALTER TABLE taska.issues ADD CONSTRAINT issues_tenant_project_id UNIQUE(tenant_id,project_id,id);
ALTER TABLE taska.project_labels ADD CONSTRAINT project_labels_tenant_project_id UNIQUE(tenant_id,project_id,id);
CREATE TABLE taska.issue_labels (
 tenant_id uuid NOT NULL,project_id uuid NOT NULL,issue_id uuid NOT NULL,label_id uuid NOT NULL,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,issue_id,label_id),
 FOREIGN KEY(tenant_id,project_id,issue_id) REFERENCES taska.issues(tenant_id,project_id,id),
 FOREIGN KEY(tenant_id,project_id,label_id) REFERENCES taska.project_labels(tenant_id,project_id,id)
);
CREATE INDEX issue_labels_project_label ON taska.issue_labels(tenant_id,project_id,label_id,issue_id);
CREATE TABLE taska.issue_watchers (
 tenant_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),project_id uuid NOT NULL,
 issue_id uuid NOT NULL,user_id uuid NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,issue_id,user_id),
 FOREIGN KEY(tenant_id,project_id,issue_id) REFERENCES taska.issues(tenant_id,project_id,id)
);
CREATE INDEX issue_watchers_project_user ON taska.issue_watchers(tenant_id,project_id,user_id,issue_id);
