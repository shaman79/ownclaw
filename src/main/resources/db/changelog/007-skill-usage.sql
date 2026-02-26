--liquibase formatted sql

--changeset ownclaw:007-skill-usage
--comment: Track tool invocation metrics for skill curation analytics.

CREATE TABLE IF NOT EXISTS skill_usage (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name    TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    task_id      TEXT    NOT NULL DEFAULT '',
    success      INTEGER NOT NULL DEFAULT 1,
    duration_ms  INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_skill_usage_tool ON skill_usage (tool_name);
CREATE INDEX IF NOT EXISTS idx_skill_usage_created ON skill_usage (created_at);
