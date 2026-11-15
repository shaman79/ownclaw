--liquibase formatted sql

--changeset ownclaw:027-drop-unused-tables
-- What no code reads or writes, and has not for months.
--
-- Six tables of the first design: the saved task state and the plan cache (001), the teaching log,
-- the preferences distilled from it and the registry of generated skills (003), and the first
-- repair log (004; 026's skill_repairs is the one in use). The code that used them was removed in
-- February 2026, days after the first commit. Their indexes go with them.
--
-- Two columns of conversations, left by the summariser that was removed on 2026-09-30, when a task
-- began to read its chat whole: compressed, the mark it put on a row it had summarised (015), and
-- compressed_content (001), which nothing ever wrote. SQLite refuses to drop a column an index
-- names, so 015's index goes first. The full-text triggers (008, 020) name role, rowid and content
-- only, and dropping a column moves no row: each keeps the rowid the full-text index knows it by.
--
-- session_summaries, the summariser's own table, is emptied and stays. The release before this one
-- still deletes a chat's summary as it deletes the chat (ConversationService.deleteSession), and
-- when this release does not come up, the deploy puts that release's JAR back -- after these
-- statements have run -- and its deletion must find the table. It is emptied because nothing
-- reads its rows, and a chat deleted from now on would leave its summary behind. The table itself
-- is dropped in a later release.
--
-- All of it is one changeset, so all of it happens or none of it: a statement SQLite refuses on
-- some database leaves that database as the earlier JAR knows it.
DROP TABLE IF EXISTS task_state;
DROP TABLE IF EXISTS plan_cache;
DROP TABLE IF EXISTS teaching_log;
DROP TABLE IF EXISTS user_preferences;
DROP TABLE IF EXISTS generated_skills;
DROP TABLE IF EXISTS skill_repair_log;

DELETE FROM session_summaries;

DROP INDEX IF EXISTS idx_conversations_session_compressed;

ALTER TABLE conversations DROP COLUMN compressed;

ALTER TABLE conversations DROP COLUMN compressed_content;
