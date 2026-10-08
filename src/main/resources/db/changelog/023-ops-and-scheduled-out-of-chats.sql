--liquibase formatted sql

--changeset ownclaw:023-ops-chats
-- A chat an ops check starts (POST /api/ops/agent/run with sessionId "new") is the operator's, not
-- the owner's: kind 'ops', which the owner's chat list and search leave out
-- (ConversationService.createOpsChat). Until now it was a 'chat' titled "Ops check" -- a title
-- only an ops check gives a chat -- and every check added one to the owner's list.
--
-- But one the owner went on in is the owner's: the chat a check opened on 2026-10-01 to re-run a
-- request of the owner's became the owner's conversation about the routers. Nothing in a row says
-- who sent it, so an "Ops check" chat with more than one message from the user -- each check run
-- so far asked one question -- or one that is the open chat stays the owner's, and takes the
-- title its first message gives it, as a new chat's first message does
-- (ConversationService.generateTitle: its first line with text on it). The rest are hidden, not
-- deleted.
UPDATE chat_sessions SET kind = 'ops'
WHERE kind = 'chat' AND title = 'Ops check'
  AND id NOT IN (SELECT session_id FROM active_session)
  AND (SELECT COUNT(*) FROM conversations c WHERE c.session_id = chat_sessions.id AND c.role = 'user') <= 1;

UPDATE chat_sessions SET title = (
    SELECT trim(substr(t, 1, instr(t || char(10), char(10)) - 1), ' ' || char(9))
    FROM (SELECT trim(replace(c.content, char(13), char(10)), ' ' || char(9) || char(10)) AS t
          FROM conversations c
          WHERE c.session_id = chat_sessions.id AND c.role = 'user' AND trim(c.content) != ''
          ORDER BY c.timestamp, c.rowid LIMIT 1))
WHERE kind = 'chat' AND title = 'Ops check'
  AND EXISTS (SELECT 1 FROM conversations c
              WHERE c.session_id = chat_sessions.id AND c.role = 'user' AND trim(c.content) != '');

--changeset ownclaw:023-scheduled-results-out-of-chats
-- Until 2026-10-01, when 020's pinned chat took them, a scheduled run's result was saved in
-- whichever chat was open when the run ended, so morning digests and lunch menus sit in the middle
-- of the owner's conversations, and in the prompts of their next turns. They move to the user's
-- pinned chat of scheduled results, where results are delivered now; each keeps the chat it was
-- in, as movedFrom in its metadata.
--
-- A row of a scheduled run is one of its task's (scheduled_task_runs.agent_task_id), or, saved
-- before rows carried their task, one under a header the scheduler wrote, in the weeks it wrote
-- it, matched case and all (GLOB): assistant rows "**Scheduled task: ...**" and "**Scheduled task
-- did not finish: ...**" (2026-09-20 to the last, on 2026-10-01), and system rows "U+1F4CB
-- **Scheduled task completed** (#n)" and "U+274C **Scheduled task failed** (#n)" (2026-03-04 to
-- 03-26). An answer that opens with such a header at another time is the model's, and stays. The
-- emoji are written as code points, char(...), so the match does not depend on how this file is
-- read; [*] in a GLOB is a literal asterisk.
CREATE TABLE scheduled_rows_in_chats AS
SELECT c.rowid AS row_id, c.user_id, c.session_id
FROM conversations c
JOIN chat_sessions s ON s.id = c.session_id
WHERE s.kind = 'chat'
  AND (CASE WHEN json_valid(c.metadata) THEN json_extract(c.metadata, '$.taskId') END IN
          (SELECT agent_task_id FROM scheduled_task_runs WHERE agent_task_id IS NOT NULL)
    OR (c.role = 'assistant' AND c.timestamp >= '2026-09-20' AND c.timestamp < '2026-10-02'
        AND (c.content GLOB '[*][*]Scheduled task: *'
          OR c.content GLOB '[*][*]Scheduled task did not finish: *'))
    OR (c.role = 'system' AND c.timestamp >= '2026-03-04' AND c.timestamp < '2026-04-01'
        AND (c.content GLOB (char(128203) || ' [*][*]Scheduled task completed[*][*] (#*')
          OR c.content GLOB (char(10060) || ' [*][*]Scheduled task failed[*][*] (#*'))));

-- A user with such rows and no pinned chat gets one, as the next delivery would create it
-- (ConversationService.scheduledSession, whose title this is).
INSERT INTO chat_sessions (id, user_id, title, kind)
SELECT lower(hex(randomblob(6))), u.user_id, char(128204) || ' Scheduled', 'scheduled'
FROM (SELECT DISTINCT user_id FROM scheduled_rows_in_chats) u
WHERE NOT EXISTS (SELECT 1 FROM chat_sessions p
                  WHERE p.user_id = u.user_id AND p.kind = 'scheduled' AND p.archived = 0);

UPDATE conversations SET
    metadata = CASE WHEN metadata IS NULL THEN json_object('movedFrom', session_id)
                    WHEN json_valid(metadata) THEN
                        CASE WHEN json_type(metadata) = 'object' THEN json_set(metadata, '$.movedFrom', session_id)
                             ELSE json_object('movedFrom', session_id, 'metadata', json(metadata)) END
                    ELSE json_object('movedFrom', session_id, 'metadata', metadata) END,
    session_id = (SELECT p.id FROM chat_sessions p
                  WHERE p.user_id = conversations.user_id AND p.kind = 'scheduled' AND p.archived = 0
                  ORDER BY p.created_at, p.rowid LIMIT 1)
WHERE rowid IN (SELECT row_id FROM scheduled_rows_in_chats);

-- A chat left with no rows -- it held nothing but scheduled results -- goes, as deleting it from
-- the list removes it (ConversationService.deleteSession), unless it is the open chat.
CREATE TABLE emptied_chats AS
SELECT DISTINCT r.session_id AS id FROM scheduled_rows_in_chats r
WHERE NOT EXISTS (SELECT 1 FROM conversations c WHERE c.session_id = r.session_id)
  AND r.session_id NOT IN (SELECT session_id FROM active_session);

DELETE FROM session_summaries WHERE session_id IN (SELECT id FROM emptied_chats);

DELETE FROM chat_sessions WHERE id IN (SELECT id FROM emptied_chats);

DROP TABLE emptied_chats;

DROP TABLE scheduled_rows_in_chats;
