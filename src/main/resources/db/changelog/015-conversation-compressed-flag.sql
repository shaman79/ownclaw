--liquibase formatted sql

--changeset ownclaw:015-conversation-compressed-flag
-- Conversation compression used to DELETE the rows it had summarised, so the owner's own
-- chat history disappeared from the UI and from search as soon as a session passed 16
-- messages — and it deleted them even when the local model returned an empty summary.
-- Rows are now marked instead. The LLM context query skips them (so the prompt still sees
-- summary + recent messages only, exactly as before), while the UI and FTS keep them.
ALTER TABLE conversations ADD COLUMN compressed INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_conversations_session_compressed
    ON conversations(user_id, session_id, compressed);
