SET LOCAL ROLE taska_project_migrator;
CREATE TABLE taska.project_lifecycle_commands(
 tenant_id uuid NOT NULL,actor_id uuid NOT NULL,authority text NOT NULL,command_key uuid NOT NULL,
 project_id uuid NOT NULL,action text NOT NULL CHECK(action IN ('project.archive','project.restore')),
 expected_version integer NOT NULL CHECK(expected_version>=0),response bytea NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,actor_id,authority,command_key),
 FOREIGN KEY(tenant_id,project_id) REFERENCES taska.projects(tenant_id,id)
);
