--liquibase formatted sql

--changeset ownclaw:021-privacy-placeholders
-- The placeholders the privacy filter (privacy.Redactor) writes for identifiers in what a cloud
-- model is sent -- <email_3>, <ssid_1> -- and the values they stand for, per user: the same
-- value gets the same placeholder in every request, task and restart, and a placeholder in a
-- reply is put back to its value. Read and written on this machine only; never sent anywhere.
CREATE TABLE privacy_placeholders (
    user_id TEXT    NOT NULL,
    kind    TEXT    NOT NULL,
    n       INTEGER NOT NULL,
    value   TEXT    NOT NULL,
    PRIMARY KEY (user_id, kind, n),
    UNIQUE (user_id, kind, value)
);
