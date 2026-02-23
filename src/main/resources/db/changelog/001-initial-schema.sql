--liquibase formatted sql

--changeset ownclaw:001-initial-schema

-- Users
CREATE TABLE users (
    id          TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    telegram_id  INTEGER UNIQUE,
    timezone     TEXT DEFAULT 'UTC',
    language     TEXT DEFAULT 'en',
    settings     TEXT,  -- JSON: mentor prefs, executor prefs, sandbox prefs
    created_at   TEXT DEFAULT (datetime('now')),
    updated_at   TEXT DEFAULT (datetime('now'))
);

-- Conversations (chat messages)
CREATE TABLE conversations (
    id                 TEXT PRIMARY KEY,
    user_id            TEXT NOT NULL REFERENCES users(id),
    session_id         TEXT NOT NULL,
    role               TEXT NOT NULL,  -- user | executor | mentor | system | status
    content            TEXT NOT NULL,
    compressed_content TEXT,
    tokens_used        INTEGER DEFAULT 0,
    timestamp          TEXT DEFAULT (datetime('now')),
    metadata           TEXT  -- JSON
);

CREATE INDEX idx_conv_user_session ON conversations(user_id, session_id);
CREATE INDEX idx_conv_timestamp    ON conversations(user_id, timestamp);

-- Session summaries (compressed conversation history)
CREATE TABLE session_summaries (
    session_id          TEXT PRIMARY KEY,
    user_id             TEXT NOT NULL REFERENCES users(id),
    summary             TEXT NOT NULL,
    last_updated        TEXT DEFAULT (datetime('now')),
    total_messages      INTEGER DEFAULT 0,
    total_tokens_cloud  INTEGER DEFAULT 0,
    total_tokens_local  INTEGER DEFAULT 0
);

-- Event log (observability)
CREATE TABLE events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp   TEXT DEFAULT (datetime('now')),
    user_id     TEXT NOT NULL,
    task_id     TEXT,
    event_type  TEXT NOT NULL,   -- task.queued, step.started, mentor.called, etc.
    severity    TEXT NOT NULL,   -- info | warn | error
    summary     TEXT NOT NULL,
    details     TEXT,            -- JSON
    tokens_used INTEGER DEFAULT 0
);

CREATE INDEX idx_events_user_task ON events(user_id, task_id);
CREATE INDEX idx_events_type      ON events(event_type);
CREATE INDEX idx_events_timestamp ON events(user_id, timestamp);

-- Task state (for partial plan recovery after crash)
CREATE TABLE task_state (
    task_id        TEXT PRIMARY KEY,
    user_id        TEXT NOT NULL REFERENCES users(id),
    status         TEXT NOT NULL,  -- queued | planning | executing | reviewing | completed | failed
    plan           TEXT,           -- JSON: the full plan
    current_step   INTEGER,
    step_results   TEXT,           -- JSON: results of completed steps
    created_at     TEXT DEFAULT (datetime('now')),
    updated_at     TEXT DEFAULT (datetime('now'))
);

CREATE INDEX idx_task_state_status ON task_state(status);

-- Credential grants (per-user approval for skills accessing credentials)
CREATE TABLE credential_grants (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      TEXT NOT NULL REFERENCES users(id),
    skill_name   TEXT NOT NULL,
    credential   TEXT NOT NULL,   -- credential key pattern (e.g. SMTP_*)
    grant_type   TEXT NOT NULL,   -- permanent | one-time | declined
    granted_at   TEXT DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX idx_cred_grant_unique ON credential_grants(user_id, skill_name, credential);

-- Plan cache (cached plans for recurring tasks)
CREATE TABLE plan_cache (
    cache_key       TEXT PRIMARY KEY,
    plan            TEXT NOT NULL,  -- JSON
    hit_count       INTEGER DEFAULT 0,
    last_used       TEXT DEFAULT (datetime('now')),
    skill_versions  TEXT NOT NULL,  -- JSON: {"skill_name": version, ...}
    created_at      TEXT DEFAULT (datetime('now'))
);

-- Enable WAL mode for better concurrent read performance
PRAGMA journal_mode=WAL;
