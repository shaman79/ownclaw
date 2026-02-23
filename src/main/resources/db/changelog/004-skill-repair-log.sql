--liquibase formatted sql

--changeset ownclaw:004-skill-repair-log

-- Skill repair log: records every self-healing attempt so the system can learn
-- from past repairs and avoid repeating failed fixes.
CREATE TABLE skill_repair_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_name  TEXT NOT NULL,
    task_id     TEXT,
    user_id     TEXT NOT NULL REFERENCES users(id),
    category    TEXT,                  -- code_bug | missing_dependency | bad_params | network_error | timeout | permission_denied | external_service_error | data_format
    root_cause  TEXT,                  -- LLM-generated root cause description
    fixable     INTEGER DEFAULT 0,    -- 0=false, 1=true (SQLite boolean)
    confidence  REAL DEFAULT 0.0,     -- 0.0-1.0, diagnosis confidence
    success     INTEGER DEFAULT 0,    -- 0=false, 1=true — did the repair work?
    outcome     TEXT,                  -- summary of what happened
    lesson      TEXT,                  -- distilled lesson for future reference
    created_at  TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_skill_repair_log_skill ON skill_repair_log(skill_name);
CREATE INDEX idx_skill_repair_log_user  ON skill_repair_log(user_id);
