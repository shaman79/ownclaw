--liquibase formatted sql

--changeset ownclaw:024-fact-captures
-- The facts the local model found in a chat turn (FactCapture): each kept fact as it was written
-- and what it replaced, so the owner can take it back, and each fact found only in the answer he
-- was given, which is kept only when he says so (CapturedFacts). A kept fact itself lives in
-- agent_memory, where every fact does; memory_id is the row it wrote there, and replaces_id, for a
-- correction on offer, the row it would replace: kept only if that row is still the fact.
CREATE TABLE IF NOT EXISTS fact_captures (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ref         TEXT    NOT NULL UNIQUE,
    user_id     TEXT    NOT NULL,
    session_id  TEXT,
    task_id     TEXT,
    source      TEXT    NOT NULL CHECK (source IN ('owner', 'answer')),
    state       TEXT    NOT NULL CHECK (state IN ('kept', 'offered', 'undone')),
    fact_key    TEXT    NOT NULL,
    fact        TEXT    NOT NULL,
    previous    TEXT,
    memory_id   INTEGER,
    replaces_id INTEGER,
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    decided_at  TEXT
);

CREATE INDEX IF NOT EXISTS idx_fact_captures_user ON fact_captures (user_id, id);
