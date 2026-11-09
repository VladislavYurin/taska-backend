SET LOCAL ROLE taska_tenant_migrator;
ALTER TABLE taska.quota_counters ADD COLUMN task_lifetime_version bigint NOT NULL DEFAULT 0 CHECK(task_lifetime_version>=0);
