-- TAS-332: new isolated database only; never reset, infer tenants or backfill legacy rows.
SET LOCAL ROLE taska_issue_migrator;
SET LOCAL search_path=pg_catalog;
DO $fresh$ BEGIN
 IF EXISTS(SELECT FROM pg_class WHERE relnamespace='taska'::regnamespace AND relkind IN ('r','p')) THEN
  RAISE EXCEPTION 'Issue tenant storage requires a fresh service database';
 END IF;
 IF NOT EXISTS(SELECT FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='pg_trgm' AND n.nspname='public') THEN
  RAISE EXCEPTION 'Operator must install pg_trgm in public before migration';
 END IF;
END $fresh$;
CREATE TABLE taska.project_counters(
  tenant_id uuid NOT NULL,
  project_id uuid NOT NULL,
  next_issue_number integer NOT NULL DEFAULT 1,
  lifetime_created bigint NOT NULL DEFAULT 0,
  version bigint NOT NULL DEFAULT 1,
  CHECK (next_issue_number >= 1),
  PRIMARY KEY (tenant_id, project_id),
  CHECK (lifetime_created>=0 AND version>0),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid)
);
CREATE INDEX project_counters_tenant_idx ON taska.project_counters USING btree (tenant_id);
CREATE TABLE taska.issues(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  project_id uuid NOT NULL,
  issue_number integer NOT NULL,
  issue_key text NOT NULL,
  issue_type text NOT NULL,
  summary text NOT NULL,
  description text,
  status_key text NOT NULL,
  priority text NOT NULL,
  assignee_id uuid,
  reporter_id uuid NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  version integer NOT NULL DEFAULT 1,
  deleted_at timestamp with time zone,
  story_points numeric(5,2),
  start_date date,
  due_date date,
  original_estimate_minutes integer,
  remaining_estimate_minutes integer,
  time_spent_minutes integer NOT NULL DEFAULT 0,
  CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date),
  UNIQUE (tenant_id, issue_key),
  CHECK (original_estimate_minutes IS NULL OR original_estimate_minutes >= 0),
  PRIMARY KEY (id),
  CHECK (priority = ANY (ARRAY['LOW'::text, 'MEDIUM'::text, 'HIGH'::text])),
  UNIQUE (tenant_id, project_id, issue_number),
  CHECK (remaining_estimate_minutes IS NULL OR remaining_estimate_minutes >= 0),
  CHECK (story_points IS NULL OR story_points >= 0::numeric),
  CHECK (time_spent_minutes >= 0),
  CHECK (issue_type ~ '^[A-Z][A-Z0-9_]{1,31}$'::text),
  CHECK (version >= 1),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id,project_id,id),
  CHECK(issue_number>0),
  CHECK(project_id<>'00000000-0000-0000-0000-000000000000'::uuid),
  FOREIGN KEY(tenant_id,project_id) REFERENCES taska.project_counters(tenant_id,project_id)
);
CREATE INDEX idx_issues_assignee_id ON taska.issues USING btree (tenant_id, assignee_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_description_trgm ON taska.issues USING gin (description public.gin_trgm_ops) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_issue_key ON taska.issues USING btree (tenant_id, issue_key) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_issue_type ON taska.issues USING btree (tenant_id, issue_type) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_priority ON taska.issues USING btree (tenant_id, priority) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_project_id ON taska.issues USING btree (tenant_id, project_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_reporter_id ON taska.issues USING btree (tenant_id, reporter_id) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_search_composite ON taska.issues USING btree (tenant_id, project_id, status_key, assignee_id, reporter_id, priority, issue_type) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_status_key ON taska.issues USING btree (tenant_id, status_key) WHERE (deleted_at IS NULL);
CREATE INDEX idx_issues_summary_trgm ON taska.issues USING gin (summary public.gin_trgm_ops) WHERE (deleted_at IS NULL);
CREATE INDEX issues_board_column_cursor_active_idx ON taska.issues USING btree (tenant_id, project_id, issue_type, status_key, created_at, id) WHERE (deleted_at IS NULL);
CREATE INDEX issues_id_active_idx ON taska.issues USING btree (tenant_id, id) WHERE (deleted_at IS NULL);
CREATE INDEX issues_project_assignee_idx ON taska.issues USING btree (tenant_id, project_id, assignee_id);
CREATE INDEX issues_project_priority_idx ON taska.issues USING btree (tenant_id, project_id, priority);
CREATE INDEX issues_project_status_idx ON taska.issues USING btree (tenant_id, project_id, status_key);
CREATE INDEX issues_project_type_idx ON taska.issues USING btree (tenant_id, project_id, issue_type);
CREATE INDEX issues_project_updated_at_idx ON taska.issues USING btree (tenant_id, project_id, updated_at);
CREATE INDEX issues_status_key_idx ON taska.issues USING btree (tenant_id, status_key);
CREATE INDEX issues_tenant_idx ON taska.issues USING btree (tenant_id);
CREATE TABLE taska.idempotency_keys(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  key text NOT NULL,
  user_id uuid NOT NULL,
  request_hash text NOT NULL,
  response jsonb NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  expires_at timestamp with time zone NOT NULL,
  operation_code text NOT NULL,
  state text NOT NULL,
  resource_id uuid,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, user_id, operation_code, key),
  CHECK(state IN ('PENDING','SUCCEEDED')),
  CHECK(expires_at>created_at),
  CHECK(state<>'SUCCEEDED' OR resource_id IS NOT NULL),
  FOREIGN KEY(tenant_id,resource_id) REFERENCES taska.issues(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idempotency_keys_expires_at_idx ON taska.idempotency_keys USING btree (tenant_id, expires_at);
CREATE INDEX idempotency_keys_tenant_idx ON taska.idempotency_keys USING btree (tenant_id);
CREATE TABLE taska.issue_history(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  issue_id uuid NOT NULL,
  event_type text NOT NULL,
  actor_user_id uuid NOT NULL,
  occurred_at timestamp with time zone NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  channel character varying(32),
  token_id uuid,
  CHECK (channel IS NULL OR (channel::text = ANY (ARRAY['UI'::character varying, 'API_TOKEN'::character varying, 'MCP'::character varying]::text[]))),
  CHECK (event_type = ANY (ARRAY['CREATED'::text, 'UPDATED'::text, 'ASSIGNED'::text, 'TRANSITIONED'::text, 'DELETED'::text, 'LINK_CREATED'::text, 'LINK_DELETED'::text, 'ATTACHMENT_UPLOADED'::text, 'ATTACHMENT_DELETED'::text, 'COMMENT_CREATED'::text, 'COMMENT_UPDATED'::text, 'COMMENT_DELETED'::text, 'LABEL_ADDED'::text, 'LABEL_REMOVED'::text, 'WORKLOG_ADDED'::text, 'WORKLOG_UPDATED'::text, 'WORKLOG_DELETED'::text])),
  FOREIGN KEY (tenant_id, issue_id) REFERENCES taska.issues(tenant_id, id) ON DELETE CASCADE,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, id)
);
CREATE INDEX issue_history_actor_occurred_idx ON taska.issue_history USING btree (tenant_id, actor_user_id, occurred_at);
CREATE INDEX issue_history_issue_occurred_idx ON taska.issue_history USING btree (tenant_id, issue_id, occurred_at);
CREATE INDEX issue_history_tenant_idx ON taska.issue_history USING btree (tenant_id);
CREATE TABLE taska.outbox_events(
  tenant_id uuid NOT NULL,
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'NEW'::text,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  published_at timestamp with time zone,
  attempts integer NOT NULL DEFAULT 0,
  last_error_message text,
  processing_started_at timestamp with time zone,
  request_id text,
  scope_kind text NOT NULL,
  aggregate_version bigint NOT NULL,
  schema_version integer NOT NULL DEFAULT 1,
  operation_id uuid,
  CHECK (attempts >= 0),
  PRIMARY KEY (id),
  CHECK (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text, 'PUBLISHED'::text, 'FAILED'::text])),
  UNIQUE (tenant_id, id),
  CHECK (scope_kind='TENANT'),
  CHECK (aggregate_version>0 AND schema_version>0),
  CHECK(aggregate_type='ISSUE'),
  FOREIGN KEY(tenant_id,aggregate_id) REFERENCES taska.issues(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX outbox_events_aggregate_idx ON taska.outbox_events USING btree (tenant_id, aggregate_type, aggregate_id);
CREATE INDEX outbox_events_processing_idx ON taska.outbox_events USING btree (tenant_id, status, processing_started_at) WHERE ((status = 'PROCESSING'::text) AND (processing_started_at IS NOT NULL));
CREATE INDEX outbox_events_status_created_idx ON taska.outbox_events USING btree (tenant_id, status, created_at) WHERE (status = ANY (ARRAY['NEW'::text, 'PROCESSING'::text]));
CREATE INDEX outbox_events_unpublished_idx ON taska.outbox_events USING btree (tenant_id, created_at) WHERE (published_at IS NULL);
CREATE INDEX outbox_events_tenant_idx ON taska.outbox_events USING btree (tenant_id);
