ALTER TABLE taska.issues
    ADD COLUMN IF NOT EXISTS story_points               numeric(5, 2) NULL,
    ADD COLUMN IF NOT EXISTS start_date                 timestamptz   NULL,
    ADD COLUMN IF NOT EXISTS due_date                   timestamptz   NULL,
    ADD COLUMN IF NOT EXISTS original_estimate_minutes  int           NULL,
    ADD COLUMN IF NOT EXISTS remaining_estimate_minutes int           NULL