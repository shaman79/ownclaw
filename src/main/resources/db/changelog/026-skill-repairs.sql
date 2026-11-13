--liquibase formatted sql

--changeset ownclaw:026-skill-repairs
-- Every self-repair of a skill (SkillSelfRepair): unattended work showed that the skill did not do
-- its whole job, and a background task was started to repair it -- or, when the last repair of it
-- changed nothing and the problem came back, none was, and the owner was told (outcome 'gave_up').
--
-- 004's skill_repair_log is not reused: it was made for a model's diagnosis (category, root cause,
-- confidence, lesson), has no task ids, no code hashes and no state, and nothing ever wrote to it.
--
-- outcome: running (queued or under way) | fixed (changed, run again, the problem gone) |
-- unverified (changed, not run again: the skill has side effects, or the repair did not run it) |
-- not_fixed (changed, and the check still showed the problem) | unchanged (ended without changing
-- the code, and finished) | gave_up (no repair started: the last one changed nothing) | interrupted
-- (OwnClaw stopped while it ran) | not_started (the queue never ran it) | stopped (Stopped before it
-- changed anything) | not_finished (ended before it finished -- an outage, the stall watchdog, the
-- window, a question -- without changing anything). The last four are no verdict on the skill.
-- side_effects: what the skill's SKILL.yaml said as the repair began; a repair runs it only on 'false'.
-- goal / evidence: what the repair was given -- its request, written by code, and (JSON) the
-- results it was handed as its first steps: the problem, the skill's public outputs, a report.
-- declared: (JSON) what the skill declared as the repair began -- side effects, network,
-- credentials, system packages, container image -- the bounds of what the repair may do.
-- resumed_at / code_at_resume: when a repair cut off by a restart was started again, and its own
-- change of the skill then; one cut off again with no change of its own since is not started again.
-- local_only: the repair ran on the local model only (the work it repairs did); its report is never
-- handed to a repair that may use the cloud.
-- code_before / code_after: the sha256 of the skill's skill.py when the repair was started and when
-- it ended. previous_id: the repair whose report this one was given (it changed the code and the
-- problem came back), or for a 'gave_up' row the repair that changed nothing. cleared_at: a later
-- unattended run used the skill with no problem, so a repair that changed nothing no longer stops
-- the next one.
CREATE TABLE IF NOT EXISTS skill_repairs (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          TEXT    NOT NULL,
    skill_name       TEXT    NOT NULL,
    trigger_task_id  TEXT,
    trigger_message  TEXT,
    trigger_at       TEXT,
    repair_task_id   TEXT,
    problem          TEXT    NOT NULL,
    added_problems   TEXT,
    goal             TEXT,
    evidence         TEXT,
    declared         TEXT,
    side_effects     TEXT    NOT NULL CHECK (side_effects IN ('true', 'false', 'undeclared')),
    code_before      TEXT,
    code_after       TEXT,
    outcome          TEXT    NOT NULL CHECK (outcome IN ('running', 'fixed', 'unverified', 'not_fixed',
                                                         'unchanged', 'gave_up', 'interrupted', 'not_started',
                                                         'stopped', 'not_finished')),
    report           TEXT,
    previous_id      INTEGER,
    local_only       INTEGER NOT NULL DEFAULT 0,
    cleared_at       TEXT,
    resumed_at       TEXT,
    code_at_resume   TEXT,
    started_at       TEXT    NOT NULL DEFAULT (datetime('now')),
    ended_at         TEXT
);

CREATE INDEX IF NOT EXISTS idx_skill_repairs_skill ON skill_repairs (skill_name, id);
CREATE INDEX IF NOT EXISTS idx_skill_repairs_repair_task ON skill_repairs (repair_task_id);
