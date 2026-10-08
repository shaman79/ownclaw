--liquibase formatted sql

--changeset ownclaw:023-ops-chats
-- A chat an ops check starts (POST /api/ops/agent/run with sessionId "new") is the operator's, not
-- the owner's: kind 'ops', which the owner's chat list and search leave out
-- (ConversationService.createOpsChat). Until now it was a 'chat' titled "Ops check" -- a title
-- only an ops check gives a chat -- and every check added one to the owner's list.
UPDATE chat_sessions SET kind = 'ops' WHERE kind = 'chat' AND title = 'Ops check';

-- An open chat that is now an ops chat -- one the owner opened from the list -- gives way to the
-- owner's most recent chat, as a deleted open chat does; with none, to no open chat, and the next
-- message starts one (ConversationService.getCurrentSession).
UPDATE active_session SET session_id = (
    SELECT s.id FROM chat_sessions s
    WHERE s.user_id = active_session.user_id AND s.kind = 'chat' AND s.archived = 0
    ORDER BY s.updated_at DESC LIMIT 1)
WHERE session_id IN (SELECT id FROM chat_sessions WHERE kind = 'ops')
  AND EXISTS (SELECT 1 FROM chat_sessions s
              WHERE s.user_id = active_session.user_id AND s.kind = 'chat' AND s.archived = 0);

DELETE FROM active_session WHERE session_id IN (SELECT id FROM chat_sessions WHERE kind = 'ops');

--changeset ownclaw:023-scheduled-results-out-of-chats
-- Until 2026-09-30 (020) a scheduled run's result was saved in whichever chat was open when the
-- run ended, so morning digests and lunch menus sit in the middle of the owner's conversations,
-- and in the prompts of their next turns. They move to the user's pinned chat of scheduled
-- results, where results are delivered now; each keeps the chat it was in, as movedFrom in its
-- metadata.
--
-- A row of a scheduled run is one of its task's (scheduled_task_runs.agent_task_id), or, saved
-- before rows carried their task, one under a header only the scheduler wrote: "**Scheduled task:
-- ...**" and "**Scheduled task did not finish: ...**" (2026-09-20 to 09-30), and, as system rows,
-- "U+1F4CB **Scheduled task completed** (#n)" and "U+274C **Scheduled task failed** (#n)"
-- (2026-03-04 to 03-26). The emoji are written as code points, char(...), so the match does not
-- depend on how this file is read.
CREATE TABLE scheduled_rows_in_chats AS
SELECT c.rowid AS row_id, c.user_id
FROM conversations c
JOIN chat_sessions s ON s.id = c.session_id
WHERE s.kind = 'chat'
  AND ((json_valid(c.metadata) AND json_extract(c.metadata, '$.taskId') IN
          (SELECT agent_task_id FROM scheduled_task_runs WHERE agent_task_id IS NOT NULL))
    OR (c.role IN ('assistant', 'system') AND (
           c.content LIKE '**Scheduled task: %'
        OR c.content LIKE '**Scheduled task did not finish: %'
        OR c.content LIKE char(128203) || ' **Scheduled task completed** (#%'
        OR c.content LIKE char(10060) || ' **Scheduled task failed** (#%')));

-- A user with such rows and no pinned chat gets one, as the next delivery would create it
-- (ConversationService.scheduledSession, whose title this is).
INSERT INTO chat_sessions (id, user_id, title, kind)
SELECT lower(hex(randomblob(6))), u.user_id, char(128204) || ' Scheduled', 'scheduled'
FROM (SELECT DISTINCT user_id FROM scheduled_rows_in_chats) u
WHERE NOT EXISTS (SELECT 1 FROM chat_sessions p
                  WHERE p.user_id = u.user_id AND p.kind = 'scheduled' AND p.archived = 0);

UPDATE conversations SET
    metadata = CASE WHEN metadata IS NULL THEN json_object('movedFrom', session_id)
                    WHEN json_valid(metadata) THEN json_set(metadata, '$.movedFrom', session_id)
                    ELSE json_object('movedFrom', session_id, 'metadata', metadata) END,
    session_id = (SELECT p.id FROM chat_sessions p
                  WHERE p.user_id = conversations.user_id AND p.kind = 'scheduled' AND p.archived = 0
                  ORDER BY p.created_at, p.rowid LIMIT 1)
WHERE rowid IN (SELECT row_id FROM scheduled_rows_in_chats);

DROP TABLE scheduled_rows_in_chats;
