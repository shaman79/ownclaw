--liquibase formatted sql

--changeset ownclaw:005-phase2-vault-auth

-- Auth support: add password_hash and encryption_salt to users
ALTER TABLE users ADD COLUMN password_hash TEXT;
ALTER TABLE users ADD COLUMN encryption_salt TEXT;

-- Credential vault: encrypted secret storage (AES-256-GCM)
CREATE TABLE credential_vault (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         TEXT NOT NULL REFERENCES users(id),
    credential_key  TEXT NOT NULL,
    encrypted_value TEXT NOT NULL,   -- Base64-encoded AES-256-GCM ciphertext
    iv              TEXT NOT NULL,   -- Base64-encoded initialization vector
    created_at      TEXT DEFAULT (datetime('now')),
    updated_at      TEXT DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX idx_vault_user_key ON credential_vault(user_id, credential_key);
