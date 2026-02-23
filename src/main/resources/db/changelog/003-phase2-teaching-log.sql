--liquibase formatted sql

--changeset ownclaw:003-phase2-teaching-log

-- Teaching log: accumulated observations about user preferences and corrections.
-- Executor distills these periodically into compact preferences.
CREATE TABLE teaching_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT NOT NULL REFERENCES users(id),
    observation TEXT NOT NULL,         -- what was observed (e.g. "user prefers bullet points")
    category    TEXT,                  -- communication | behavior | format | workflow
    confidence  REAL DEFAULT 0.5,     -- 0.0-1.0, grows with repeated observations
    occurrences INTEGER DEFAULT 1,    -- how many times this pattern was seen
    source      TEXT,                  -- task_id or session context that produced this
    created_at  TEXT DEFAULT (datetime('now')),
    updated_at  TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_teaching_log_user ON teaching_log(user_id);
CREATE INDEX idx_teaching_log_category ON teaching_log(user_id, category);

-- User preferences: distilled from teaching log by the Executor.
-- Stored as structured JSON, injected into Executor prompts.
CREATE TABLE user_preferences (
    user_id     TEXT PRIMARY KEY REFERENCES users(id),
    preferences TEXT NOT NULL DEFAULT '{}',  -- JSON: distilled preferences
    version     INTEGER DEFAULT 1,           -- increments on each distillation
    last_distilled TEXT,                      -- when last distillation ran
    updated_at  TEXT DEFAULT (datetime('now'))
);

-- Token usage tracking: daily aggregates per user (supplements events table).
CREATE TABLE token_usage (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT NOT NULL REFERENCES users(id),
    date        TEXT NOT NULL,               -- YYYY-MM-DD
    provider    TEXT NOT NULL,               -- ollama | openai | anthropic | etc.
    tokens_used INTEGER DEFAULT 0,
    requests    INTEGER DEFAULT 0,
    cost_usd    REAL DEFAULT 0.0,            -- estimated cost
    updated_at  TEXT DEFAULT (datetime('now')),
    UNIQUE(user_id, date, provider)
);

CREATE INDEX idx_token_usage_user_date ON token_usage(user_id, date);

-- Generated skills registry: tracks Mentor-generated skills and their status.
CREATE TABLE generated_skills (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_name  TEXT NOT NULL UNIQUE,
    description TEXT,
    version     INTEGER DEFAULT 1,
    status      TEXT DEFAULT 'active',       -- active | probationary | disabled | failed
    created_by  TEXT NOT NULL,               -- user_id who triggered creation
    executions  INTEGER DEFAULT 0,           -- total execution count
    failures    INTEGER DEFAULT 0,           -- failure count (for auto-disable)
    created_at  TEXT DEFAULT (datetime('now')),
    updated_at  TEXT DEFAULT (datetime('now'))
);

