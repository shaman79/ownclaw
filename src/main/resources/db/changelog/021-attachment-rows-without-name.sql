--liquibase formatted sql

--changeset ownclaw:021-attachment-rows-without-name
-- The events row of a file sent with a message held the name the file was uploaded with, and
-- the ops API reads every events row, for sessions whose model runs in the cloud. A file's name
-- can say what it holds: a statement's carries its account number. Rows written from now on
-- hold the file's id instead, and the owner's task page looks the name up by it; this takes the
-- name out of the rows written before. Those have no id, so the page shows their files unnamed.
UPDATE events SET details = json_remove(details, '$.name') WHERE event_type = 'attachment';
