--liquibase formatted sql

--changeset ownclaw:022-chat-options
-- What a chat has chosen of the owner's two defaults on the settings page, next to its message
-- box: time vs cost ('fast', 'cheaper' or 'free') and the thinking effort ('low', 'medium' or
-- 'high'). NULL is no choice: the chat follows the default. A message sends the chat's choice
-- with it, which is saved here and stays for the chat's next messages, from Telegram too. Every
-- chat that exists when this runs follows the defaults, as it did.
ALTER TABLE chat_sessions ADD COLUMN cost_mode TEXT;
ALTER TABLE chat_sessions ADD COLUMN thinking_effort TEXT;
