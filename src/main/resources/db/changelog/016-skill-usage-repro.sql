--liquibase formatted sql

--changeset ownclaw:016-skill-usage-repro
-- skill_usage recorded that an invocation failed, but not enough to do anything about it:
-- tool name, a success flag and a duration. A failure was a counter.
--
-- Self-healing needs a failure to be REPRODUCIBLE. With the parameters and the error message,
-- a failing invocation is already a test case -- no test-authoring step, no synthesised inputs
-- that fail for reasons the skill is not responsible for. The failures write the tests, and a
-- repair is verified by re-running the exact call that broke.
--
-- Parameters are stored redacted and truncated by the writer: values under obviously
-- secret-looking keys are replaced, because skill parameters can carry tokens and this database
-- holds the owner's conversation history.
ALTER TABLE skill_usage ADD COLUMN params_json TEXT;
ALTER TABLE skill_usage ADD COLUMN error TEXT;

-- Finding the failures for one skill is the query self-healing runs; make it cheap.
CREATE INDEX IF NOT EXISTS idx_skill_usage_tool_success
    ON skill_usage (tool_name, success, created_at);
