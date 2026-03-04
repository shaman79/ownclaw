--liquibase formatted sql

--changeset ownclaw:010-scheduled-tasks

-- Scheduled and deferred tasks.
-- Supports both one-shot deferred ("remind me in 2 hours") and
-- recurring cron-style ("backup every night at 3am") tasks.
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         TEXT    NOT NULL,
    task_type       TEXT    NOT NULL DEFAULT 'deferred',  -- 'deferred' (one-shot) or 'recurring'
    description     TEXT    NOT NULL,                      -- the user message / task to execute
    cron_expression TEXT,                                  -- cron expression for recurring tasks (null for deferred)
    next_run_at     TEXT    NOT NULL,                      -- ISO-8601 instant of next scheduled execution
    last_run_at     TEXT,                                  -- ISO-8601 instant of last execution (null if never run)
    status          TEXT    NOT NULL DEFAULT 'active',     -- 'active', 'paused', 'completed', 'cancelled', 'failed'
    run_count       INTEGER NOT NULL DEFAULT 0,            -- how many times this task has fired
    max_runs        INTEGER,                               -- optional limit for recurring tasks (null = unlimited)
    created_at      TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT    NOT NULL DEFAULT (datetime('now')),
    last_result     TEXT,                                  -- summary of last execution result
    last_error      TEXT,                                  -- error from last execution (if any)
    metadata        TEXT                                   -- optional JSON blob for extra context
);

CREATE INDEX IF NOT EXISTS idx_st_user_status  ON scheduled_tasks(user_id, status);
CREATE INDEX IF NOT EXISTS idx_st_next_run     ON scheduled_tasks(next_run_at, status);
