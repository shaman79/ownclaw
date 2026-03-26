--liquibase formatted sql

--changeset ownclaw:013-task-run-tokens

-- Add token usage columns to scheduled_task_runs so that
-- cloud/local token consumption is visible per execution.
ALTER TABLE scheduled_task_runs ADD COLUMN cloud_tokens INTEGER DEFAULT 0;
ALTER TABLE scheduled_task_runs ADD COLUMN local_tokens INTEGER DEFAULT 0;
