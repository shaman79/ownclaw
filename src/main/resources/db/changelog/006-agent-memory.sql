--liquibase formatted sql

--changeset ownclaw:006-agent-memory
--comment: Agent memory table for episodic and semantic memory storage.

CREATE TABLE IF NOT EXISTS agent_memory (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      TEXT    NOT NULL,
    task_id      TEXT    NOT NULL DEFAULT '',
    memory_type  TEXT    NOT NULL CHECK (memory_type IN ('episode', 'fact')),
    content      TEXT    NOT NULL,
    outcome      INTEGER NOT NULL DEFAULT 1,
    tags         TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_agent_memory_user_type ON agent_memory (user_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_agent_memory_tags ON agent_memory (tags);
