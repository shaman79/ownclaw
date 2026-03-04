--liquibase formatted sql

--changeset ownclaw:009-long-running-tasks

-- Long-running task tracking with heartbeat and progress
CREATE TABLE long_running_tasks (
    task_id         TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    description     TEXT NOT NULL,           -- human-readable task description
    skill_name      TEXT,                    -- skill being executed (nullable for multi-skill agent tasks)
    status          TEXT NOT NULL DEFAULT 'running',  -- running | completed | failed | stalled | cancelled
    progress_pct    INTEGER,                 -- 0-100, null if unknown
    progress_msg    TEXT,                    -- latest progress message from skill
    heartbeat_at    TEXT DEFAULT (datetime('now')),   -- last heartbeat timestamp
    started_at      TEXT DEFAULT (datetime('now')),
    completed_at    TEXT,
    error_message   TEXT,                    -- set on failure
    result_summary  TEXT,                    -- brief summary on completion
    metadata        TEXT                     -- JSON: extra data (params, intermediate results)
);

CREATE INDEX idx_lrt_user_status ON long_running_tasks(user_id, status);
CREATE INDEX idx_lrt_heartbeat   ON long_running_tasks(status, heartbeat_at);
