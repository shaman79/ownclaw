--liquibase formatted sql

--changeset ownclaw:020-chat-session-kind
-- What a chat is for. 'chat' is a conversation the owner has; 'scheduled' is the one pinned chat
-- per user that the results of scheduled runs are delivered into, so a morning digest lands in
-- that chat instead of whichever conversation happened to be open when it finished. Every row
-- that exists when this runs is a 'chat'.
ALTER TABLE chat_sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat';

--changeset ownclaw:020-fts-remove-only-indexed-rows
-- The full-text index holds user and assistant rows only (conversations_fts_insert), but the
-- delete and update triggers told it to remove every row. Removing a row an external-content
-- FTS5 index never had is corruption, and SQLite refuses the statement: deleting a chat that
-- held a command's reply (a 'system' row) failed with SQLITE_CORRUPT_VTAB and the chat stayed.
-- The index is now told to remove only what was put in it.
DROP TRIGGER conversations_fts_delete;

CREATE TRIGGER conversations_fts_delete AFTER DELETE ON conversations
WHEN OLD.role IN ('user', 'assistant')
BEGIN
    INSERT INTO conversations_fts(conversations_fts, rowid, content) VALUES('delete', OLD.rowid, OLD.content);
END;

DROP TRIGGER conversations_fts_update;

CREATE TRIGGER conversations_fts_update AFTER UPDATE OF content ON conversations
BEGIN
    INSERT INTO conversations_fts(conversations_fts, rowid, content)
        SELECT 'delete', OLD.rowid, OLD.content WHERE OLD.role IN ('user', 'assistant');
    INSERT INTO conversations_fts(rowid, content)
        SELECT NEW.rowid, NEW.content WHERE NEW.role IN ('user', 'assistant');
END;
