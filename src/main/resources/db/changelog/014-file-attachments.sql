--liquibase formatted sql

--changeset ownclaw:014-file-attachments
CREATE TABLE IF NOT EXISTS file_attachments (
    id           TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    original_name TEXT NOT NULL,
    stored_name  TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes   INTEGER NOT NULL DEFAULT 0,
    uploaded_at  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_file_attachments_user ON file_attachments(user_id);

-- Junction table: which files are attached to which conversation message
CREATE TABLE IF NOT EXISTS message_attachments (
    message_id   TEXT NOT NULL,
    file_id      TEXT NOT NULL,
    PRIMARY KEY (message_id, file_id),
    FOREIGN KEY (message_id) REFERENCES conversations(id),
    FOREIGN KEY (file_id) REFERENCES file_attachments(id)
);
