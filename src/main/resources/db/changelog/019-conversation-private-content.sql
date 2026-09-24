--liquibase formatted sql

--changeset ownclaw:019-conversation-private-content
-- The text only the owner reads, beside the text everything else reads. An answer the local
-- model wrote from the owner's file is kept here, and content holds a note that it exists:
-- content feeds the prompt of every later task in the chat, the compressor's summary and search,
-- and all of those can reach the cloud. Only the owner's own chat, on reload, reads this column.
-- NULL means the row has no private text, which is every row written before this change.
ALTER TABLE conversations ADD COLUMN private_content TEXT;
