--liquibase formatted sql

--changeset ownclaw:012-performance-indexes
--comment: Add covering indexes for hot query paths (session list, compression checks).

-- Covers the correlated subquery in listSessions():
--   SELECT COUNT(*) FROM conversations c WHERE c.session_id = s.id AND c.role IN ('user','assistant')
-- Without this, SQLite scans the full conversations table per session.
CREATE INDEX IF NOT EXISTS idx_conv_session_role ON conversations(session_id, role);

-- Covers compressIfNeeded() COUNT(*) and ORDER BY timestamp ASC LIMIT queries
-- The existing idx_conv_user_session covers (user_id, session_id) but not role filtering.
CREATE INDEX IF NOT EXISTS idx_conv_session_role_ts ON conversations(user_id, session_id, role, timestamp);
