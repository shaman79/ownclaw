--liquibase formatted sql

--changeset ownclaw:002-system-settings

-- System-wide key/value settings (wizard config, runtime overrides)
CREATE TABLE system_settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TEXT DEFAULT (datetime('now'))
);
