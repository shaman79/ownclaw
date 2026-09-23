--liquibase formatted sql

--changeset ownclaw:017-skill-usage-label
-- Whether a recorded invocation's error text may be shown to a cloud model.
--
-- The repair path reads recent failures of a skill -- parameters and error -- and puts them
-- into the code-generation prompt, which goes to the cloud. That was fine while every result
-- was public. A skill that declared credentials produces PRIVATE results, and its traceback is
-- one of them: a mail fetcher's error carries the mailbox. The error is still stored here, in
-- full, because it is the owner's diagnostic and he reads it through ops; what changes is that
-- the repair query only takes PUBLIC rows.
--
-- Rows written before this column exists are NULL, which the repair query treats as not
-- public. They age out of the three-row window on their own; the loss is three old failures.
ALTER TABLE skill_usage ADD COLUMN label TEXT;
