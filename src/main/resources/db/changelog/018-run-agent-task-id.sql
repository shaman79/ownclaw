--liquibase formatted sql

--changeset ownclaw:018-run-agent-task-id
-- Which agent task a scheduled run was, so the run can be opened as that task's steps and cloud
-- calls. The value is the 8-character events.task_id. recordRun always had it in hand and dropped
-- it; runs recorded before this change have NULL and simply have no link.
ALTER TABLE scheduled_task_runs ADD COLUMN agent_task_id TEXT;
