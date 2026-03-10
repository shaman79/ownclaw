--liquibase formatted sql

--changeset ownclaw:011-scheduled-task-runs

-- Execution history for scheduled tasks.
-- Each row represents a single execution (run) of a scheduled task,
-- preserving full result/error history beyond just last_result/last_error.
CREATE TABLE IF NOT EXISTS scheduled_task_runs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     INTEGER NOT NULL,              -- FK to scheduled_tasks.id
    user_id     TEXT    NOT NULL,
    description TEXT    NOT NULL,              -- task description at time of execution
    task_type   TEXT    NOT NULL,              -- 'deferred' or 'recurring'
    status      TEXT    NOT NULL,              -- 'completed', 'failed'
    result      TEXT,                          -- full execution result (not truncated)
    error       TEXT,                          -- full error message (if failed)
    duration_ms INTEGER,                      -- execution duration in milliseconds
    run_number  INTEGER NOT NULL DEFAULT 1,   -- which run of this task (1, 2, 3...)
    executed_at TEXT    NOT NULL DEFAULT (datetime('now')),
    skills_used TEXT                           -- comma-separated list of skills invoked
);

CREATE INDEX IF NOT EXISTS idx_str_user   ON scheduled_task_runs(user_id, executed_at);
CREATE INDEX IF NOT EXISTS idx_str_task   ON scheduled_task_runs(task_id);
