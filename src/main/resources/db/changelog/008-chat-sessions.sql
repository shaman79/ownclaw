--liquibase formatted sql

--changeset ownclaw:008-chat-sessions

-- Chat sessions: each user can have multiple named chat sessions
CREATE TABLE chat_sessions (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id),
    title       TEXT NOT NULL DEFAULT 'New Chat',
    preview     TEXT,          -- first user message snippet for quick display
    created_at  TEXT DEFAULT (datetime('now')),
    updated_at  TEXT DEFAULT (datetime('now')),
    archived    INTEGER DEFAULT 0  -- 0=active, 1=archived
);

CREATE INDEX idx_chat_sessions_user      ON chat_sessions(user_id, updated_at);
CREATE INDEX idx_chat_sessions_archived  ON chat_sessions(user_id, archived);

-- Track the currently active session per user
CREATE TABLE active_session (
    user_id     TEXT PRIMARY KEY REFERENCES users(id),
    session_id  TEXT NOT NULL REFERENCES chat_sessions(id)
);

-- Full-text search index on conversation content
CREATE VIRTUAL TABLE conversations_fts USING fts5(
    content,
    content='conversations',
    content_rowid='rowid'
);

-- Populate FTS from existing data
INSERT INTO conversations_fts(rowid, content)
SELECT rowid, content FROM conversations WHERE role IN ('user', 'assistant');

-- Triggers to keep FTS in sync
CREATE TRIGGER conversations_fts_insert AFTER INSERT ON conversations
WHEN NEW.role IN ('user', 'assistant')
BEGIN
    INSERT INTO conversations_fts(rowid, content) VALUES (NEW.rowid, NEW.content);
END;

CREATE TRIGGER conversations_fts_delete AFTER DELETE ON conversations
BEGIN
    INSERT INTO conversations_fts(conversations_fts, rowid, content) VALUES('delete', OLD.rowid, OLD.content);
END;

CREATE TRIGGER conversations_fts_update AFTER UPDATE OF content ON conversations
BEGIN
    INSERT INTO conversations_fts(conversations_fts, rowid, content) VALUES('delete', OLD.rowid, OLD.content);
    INSERT INTO conversations_fts(rowid, content) VALUES (NEW.rowid, NEW.content);
END;
