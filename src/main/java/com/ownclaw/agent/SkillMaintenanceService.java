package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.observability.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Retires generated skills that have stopped earning their place.
 *
 * <p>The library grows by design — the agent writes a new skill whenever nothing fits — but
 * nothing ever shrank it, so process residue accumulated: variants that never worked, debug
 * copies made while chasing a bug, one-shot installers, and narrower forms of a skill that
 * already did the job. That costs more than disk. Every registered skill is a line in the tool
 * manifest the model reads before each decision, and a near-duplicate of a working tool is an
 * invitation to pick the wrong one.
 *
 * <h2>Why "unused" is never the reason</h2>
 * The tempting rule — retire anything idle for months — is wrong here, and the production data
 * says so plainly: {@code web_fetch_and_parse} (36 runs, 32 successes) and
 * {@code imap_move_to_trash_by_sender} (18 for 18) had both been idle over six months. They are
 * not dead, they are capabilities waiting to be needed. Retiring them would force the agent to
 * write them again, which is exactly what the owner asked to prevent. Idleness therefore only
 * makes a skill <em>eligible to be examined</em>; the reason to retire it must be evidence about
 * the skill itself. Two such reasons exist, and no others:
 * <ol>
 *   <li>it has not earned its place — almost nothing it ever did worked;</li>
 *   <li>it is a narrower form of a skill that is still here and does the job better.</li>
 * </ol>
 *
 * <h2>What it will not touch</h2>
 * A skill used recently is kept. A skill any live scheduled task refers to is kept however old
 * its usage looks, because a task that fires monthly can be months idle and still essential. And
 * if the usage history is too thin to judge anything — an empty or freshly restored table — the
 * pass does nothing at all, because "no history" and "no successes" are indistinguishable from
 * the counters alone, and reading the first as the second would retire the entire library in one
 * go.
 *
 * <h2>Reversibility carries the risk</h2>
 * Retirement moves the directory to {@code quarantine/} with a note, and nothing is deleted.
 * That is what makes an automatic rule acceptable. But it is worth being precise about what
 * reversibility buys: the <em>bytes</em> are recoverable by moving a directory back, while the
 * agent's knowledge that the capability existed is not — nothing offers {@code quarantine/} back
 * to it, so a wrongly retired skill is eventually rewritten from scratch. A wrong retirement is
 * repaired by the owner, not by the system, which is precisely why the rules below demand
 * positive evidence against a skill rather than an absence of evidence for it.
 */
@Service
public class SkillMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(SkillMaintenanceService.class);

    /** Below this, a skill is in active service and is never examined. */
    static final int IDLE_DAYS_BEFORE_ELIGIBLE = 90;

    /**
     * How reliable a skill must be to count as a working capability, as a reciprocal: at least
     * one attempt in {@value} has to have succeeded.
     * <p>
     * There is deliberately no minimum number of attempts. An earlier version required two
     * before "it has never worked" counted as evidence, which exempted the single commonest
     * piece of residue in the real library — written once, failed once, abandoned — and so
     * spared whatever had been given up on fastest. One failed attempt with no successes is the
     * same evidence as five; the count changes the confidence, not the direction.
     */
    static final int MIN_SUCCESS_IN = 4;

    /** The actor recorded against automated retirements. {@code events.user_id} is NOT NULL. */
    private static final String SYSTEM_ACTOR = "system";

    private final DynamicSkillRegistry registry;
    private final JdbcTemplate jdbc;
    private final EventLogService eventLog;
    private final com.ownclaw.config.OwnClawConfig config;
    private final com.ownclaw.skills.PythonEnvironmentService pythonEnv;

    public SkillMaintenanceService(DynamicSkillRegistry registry, JdbcTemplate jdbc,
                                   EventLogService eventLog,
                                   com.ownclaw.config.OwnClawConfig config,
                                   com.ownclaw.skills.PythonEnvironmentService pythonEnv) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.eventLog = eventLog;
        this.config = config;
        this.pythonEnv = pythonEnv;
    }

    // ── environments that outlived their skill ──

    /**
     * Python environments belonging to no loaded skill — listed, or removed.
     * <p>
     * Retiring a skill moves its directory to quarantine; deleting one removes it; the agent
     * rewrites one under a new name. None of that ever touched {@code _envs/<name>}, so by
     * 2026-09-23 the production host held 45.7 GB of environments for skills that no longer
     * existed and was at 96% disk. An environment is a cache keyed on the requirements hash: a
     * quarantined skill that is restored simply provisions again on its next run — and a skill
     * that fails to load at boot is quarantined, so its environment is prunable by design.
     * <p>
     * The prune is explicit only — never from the daily pass. The first version ran it under
     * {@code auto-retire}, whose documented meaning is that retirement moves and nothing is
     * deleted; flipping that flag would then have deleted the environments of every skill the
     * same pass had just retired, plus 45 GB of older ones, with no dry run of the prune ever
     * shown. ({@code deleteSkill} is different: it removes the environment together with the code
     * it already deletes, as part of the same explicit deletion.)
     *
     * @param dryRun when true nothing is removed and the list comes back with sizes
     */
    public List<com.ownclaw.skills.PythonEnvironmentService.OrphanedEnv> pruneOrphanedEnvironments(
            boolean dryRun) {
        // Live is what is loaded OR what has a directory under generated/. The directory covers
        // the two windows the registry does not: a skill whose first pip install is still running
        // before register(), and a reload that has emptied the map and not yet refilled it.
        // Either alone was a way to delete a live environment.
        Set<String> live = new java.util.HashSet<>();
        registry.allDynamic().forEach(d -> live.add(d.name()));
        try (var dirs = java.nio.file.Files.list(
                java.nio.file.Path.of(config.getSkills().getGeneratedPath()))) {
            dirs.filter(java.nio.file.Files::isDirectory)
                .forEach(d -> live.add(d.getFileName().toString()));
        } catch (Exception e) {
            log.warn("Could not list generated skills for the prune: {}", e.getMessage());
        }
        var result = pythonEnv.pruneOrphans(live, dryRun);
        if (!dryRun) {
            for (var o : result) {
                try {
                    // One row per attempt, removed or not, as skill.retired records moved=true|false:
                    // a deletion of tens of GB with nothing saying what went is the failure the
                    // retirement half already learned from.
                    eventLog.log(SYSTEM_ACTOR, null, "skill.env_pruned", o.removed() ? "info" : "warn",
                            o.skill() + " — " + (o.bytes() / (1024 * 1024)) + " MB"
                                    + (o.removed() ? "" : " (still on disk)"),
                            "{\"dir\":\"" + o.dir() + "\",\"removed\":" + o.removed() + "}", 0);
                } catch (Exception e) {
                    log.warn("Could not record the prune of {}: {}", o.skill(), e.getMessage());
                }
            }
        }
        return result;
    }

    // ── facts ────────────────────────────────────────────────────────────────

    /**
     * What is known about one registered skill.
     *
     * @param runs      recorded invocations, ever
     * @param successes how many of them worked
     * @param idleDays  days since the last invocation — or, for a skill never invoked, days since
     *                  its files were last written
     */
    public record SkillFacts(String name, int runs, int successes, int idleDays) {
        public boolean everRan()    { return runs > 0; }
        public boolean everWorked() { return successes > 0; }

        /** Success rate in parts per thousand, so comparisons never turn on float rounding. */
        public int successRatePermille() {
            return runs == 0 ? 0 : (int) Math.round(1000.0 * successes / runs);
        }

        /**
         * Whether this skill has shown that it works. Integer arithmetic on purpose: at the
         * boundary, a floating comparison would make a 1-in-4 skill's fate depend on rounding.
         */
        public boolean earnedItsPlace() {
            return successes > 0 && successes * MIN_SUCCESS_IN >= runs;
        }
    }

    /** A proposed or performed retirement. */
    public record Retirement(String skill, String rule, String reason, boolean performed) {}

    /**
     * Usage facts for every registered skill.
     * <p>
     * Returns empty — meaning "retire nothing" — whenever the history cannot be trusted. That
     * covers a failed query and, just as importantly, a history too short to contain the idleness
     * these rules reason about: a fresh or restored database looks exactly like a library in
     * which nothing has ever succeeded, and reading it that way would quarantine everything.
     */
    public List<SkillFacts> facts() {
        Map<String, List<Map<String, Object>>> usage = new LinkedHashMap<>();
        try {
            Map<String, Object> span = jdbc.queryForMap(
                    "SELECT COUNT(*) c, "
                            + "CAST(julianday('now') - julianday(MIN(created_at)) AS INTEGER) span "
                            + "FROM skill_usage");
            int rows = num(span.get("c"));
            int spanDays = num(span.get("span"));
            if (rows == 0 || spanDays < IDLE_DAYS_BEFORE_ELIGIBLE) {
                log.info("Skill maintenance: usage history is too thin to judge ({} rows spanning "
                        + "{} days); retiring nothing.", rows, spanDays);
                return List.of();
            }
            // Raw rows, not an aggregate: each one has to be compared against the skill's own
            // birth date before it counts, and an aggregate has already thrown that away.
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT tool_name, created_at, success FROM skill_usage")) {
                usage.computeIfAbsent(String.valueOf(row.get("tool_name")),
                        k -> new ArrayList<>()).add(row);
            }
        } catch (Exception e) {
            log.warn("Skill maintenance cannot read usage history ({}); no skill will be retired.",
                    e.getMessage());
            return List.of();
        }

        List<SkillFacts> out = new ArrayList<>();
        for (DynamicSkill skill : registry.allDynamic()) {
            out.add(factsFor(skill, usage.getOrDefault(skill.name(), List.of())));
        }
        out.sort(Comparator.comparing(SkillFacts::name));
        return out;
    }

    /**
     * Count only the history that belongs to the skill as it exists now.
     * <p>
     * {@code skill_usage} is keyed on the tool's name and nothing else, so a name carries its
     * record across rewrites. That matters in two ways that both end badly. A skill the agent
     * repairs inherits the failures of the version it just replaced, and is retired for them
     * before the repair can be tried once. And a skill retired here and then written again from
     * scratch inherits the dead one's record <em>and</em> its months of idleness, so it is
     * retired again immediately — a write-and-retire loop that would never settle.
     * <p>
     * The skill's own files say when this version began. Usage older than that belongs to a
     * previous incarnation of the name and is not evidence about this one. A rewritten or
     * repaired skill therefore starts with a clean record and the full grace period, which is
     * also the right answer for repair: fixing something is precisely the claim that its past
     * failures no longer apply.
     */
    private static SkillFacts factsFor(DynamicSkill skill, List<Map<String, Object>> rows) {
        return factsFor(skill.name(), newestFileAt(skill.skillDir()), rows);
    }

    /** The counting itself, separated from the filesystem so it can be tested directly. */
    static SkillFacts factsFor(String name, Instant writtenAt, List<Map<String, Object>> rows) {
        int runs = 0, successes = 0;
        Instant lastUse = null;
        for (Map<String, Object> row : rows) {
            Instant at = parseInstant(String.valueOf(row.get("created_at")));
            if (at == null || at.isBefore(writtenAt)) continue;   // a previous incarnation's record
            runs++;
            if (num(row.get("success")) != 0) successes++;
            if (lastUse == null || at.isAfter(lastUse)) lastUse = at;
        }
        int idleDays = lastUse != null
                ? (int) Math.max(0, Duration.between(lastUse, Instant.now()).toDays())
                : (int) Math.max(0, Duration.between(writtenAt, Instant.now()).toDays());
        return new SkillFacts(name, runs, successes, idleDays);
    }

    /**
     * SQLite writes {@code datetime('now')} as "YYYY-MM-DD HH:MM:SS" in UTC. An unparseable value
     * returns null and the row is skipped, which counts it against nothing — the safe direction,
     * since the alternative is inventing evidence.
     */
    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank() || "null".equals(text)) return null;
        try {
            return Instant.parse(text.trim().replace(' ', 'T')
                    + (text.contains("Z") || text.contains("+") ? "" : "Z"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Days since anything in the skill's directory was last written.
     * <p>
     * The newest file wins, so a skill repaired yesterday reads as one day old however long ago
     * it was first created — a skill the agent has just fixed is not residue. The children are
     * listed rather than the directory stat'ed because on POSIX a directory's mtime tracks
     * entries added and removed, not a file rewritten in place, and rewriting skill.py in place
     * is exactly what updating a skill does. If the age cannot be read at all the answer is 0:
     * unknown age means too new to judge, the direction every other uncertainty here fails in.
     */
    /**
     * The files {@code skill_create} authors. Nothing else in the directory says when the skill
     * was written.
     * <p>
     * This list is the whole fix for the incident this class caused. The previous version took
     * the newest mtime across the entire skill directory, on the reasoning that rewriting
     * skill.py in place should count. But a skill directory is also a <em>working</em> directory:
     * DynamicSkill writes a {@code _runner_<id>.py} into it on every execution, and the virtualenv
     * lives there too. So the newest file tracked when the skill last <em>ran</em>, not when it
     * was last written — which put the supposed birth date after every usage row, discarded all
     * of them, and reported skills with perfect records as "never invoked once". Twenty-one of
     * thirty-one skills were retired on that reading.
     */
    private static final List<String> AUTHORED_FILES = List.of("skill.py", "SKILL.yaml");
    // requirements.txt is deliberately absent. It looks authored, and skill_create does write it,
    // but it is ALSO rewritten at runtime: the sandbox's self-heal appends a missing package and
    // retries when a skill dies on ModuleNotFoundError. A failing skill therefore touches it on
    // the way to failing, which would move this clock forward, discard the usage history that
    // proves it is failing, and make it look newly written and unjudgeable -- protecting exactly
    // the skills this pass exists to retire.

    /**
     * When this version of the skill was authored: the newest of its source files.
     * <p>
     * Returns now (i.e. "brand new, judge nothing") if none of them can be read, which is the
     * direction every uncertainty in this class fails in.
     */
    private static Instant newestFileAt(Path dir) {
        long newest = 0L;
        for (String name : AUTHORED_FILES) {
            newest = Math.max(newest, modifiedMillis(dir.resolve(name)));
        }
        if (newest == 0L) {
            log.warn("No authored files readable under {}; treating the skill as newly written.", dir);
            return Instant.now();
        }
        return Instant.ofEpochMilli(newest);
    }

    static int ageDays(Path dir) {
        Instant at = newestFileAt(dir);
        long days = Duration.between(at, Instant.now()).toDays();
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, days));
    }

    /** 0 when the file does not exist or cannot be read — it then contributes nothing to the max. */
    private static long modifiedMillis(Path p) {
        try {
            return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Skills that must never be retired because something still points at them.
     * <p>
     * Scheduled tasks name their skills in free text ("Fetch daily news digest using
     * daily_news_digest skill"), so this matches the description as well as the {@code
     * skills_used} recorded against past runs. It reads every task that is not cancelled — a
     * paused task is one the owner intends to resume.
     */
    public Set<String> protectedByScheduler() {
        Set<String> names = new TreeSet<>();
        Set<String> registered = new TreeSet<>();
        registry.allDynamic().forEach(s -> registered.add(s.name()));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT COALESCE(description,'') d FROM scheduled_tasks "
                            + "WHERE status <> 'cancelled' "
                            + "UNION ALL "
                            + "SELECT COALESCE(skills_used,'') d FROM scheduled_task_runs "
                            + "WHERE executed_at > datetime('now', '-365 day')");
            for (Map<String, Object> row : rows) {
                String text = String.valueOf(row.get("d")).toLowerCase(Locale.ROOT);
                for (String name : registered) {
                    if (text.contains(name.toLowerCase(Locale.ROOT))) names.add(name);
                }
            }
        } catch (Exception e) {
            // Cannot tell what the scheduler needs -> protect everything.
            log.warn("Skill maintenance cannot read scheduled tasks ({}); protecting all skills.",
                    e.getMessage());
            return registered;
        }
        return names;
    }

    // ── the decision ─────────────────────────────────────────────────────────

    /** Decide what should be retired, without touching anything. */
    public List<Retirement> plan() {
        List<SkillFacts> all = facts();
        if (all.isEmpty()) return List.of();
        return decide(all, protectedByScheduler());
    }

    /**
     * The rules themselves, as a pure function of the facts.
     * <p>
     * Separated from the database so they can be replayed against the real library exactly as it
     * stood, with no mocks: a retirement rule is only trustworthy if you can see what it does to
     * skills that actually exist.
     */
    static List<Retirement> decide(List<SkillFacts> all, Set<String> protectedNames) {
        Map<String, SkillFacts> byName = new LinkedHashMap<>();
        all.forEach(f -> byName.put(f.name(), f));

        List<Retirement> out = new ArrayList<>();
        for (SkillFacts f : all) {
            if (f.idleDays() < IDLE_DAYS_BEFORE_ELIGIBLE) continue;
            if (protectedNames.contains(f.name())) continue;

            // 1. It has not earned its place: nothing it ever did worked, or so little of it that
            //    offering it to the model mostly wastes a step. One clause covers what used to be
            //    two separate rules — a skill never invoked has no successes either — and the
            //    reason text keeps the shapes distinguishable for whoever reads the note later.
            if (!f.earnedItsPlace()) {
                out.add(new Retirement(f.name(), "unproven", unprovenReason(f), false));
                continue;
            }

            // 2. A narrower form of a skill that is still here and does the job at least as well.
            //    This is the shape the owner named: a specialisation sitting beside the general
            //    tool, competing with it in the manifest for no benefit.
            String general = supersededBy(f, byName);
            if (general != null) {
                SkillFacts g = byName.get(general);
                out.add(new Retirement(f.name(), "superseded",
                        "a narrower form of '" + general + "', which succeeds "
                                + pct(g) + " of the time against this one's " + pct(f), false));
            }
        }
        out.sort(Comparator.comparing(Retirement::skill));
        return out;
    }

    private static String unprovenReason(SkillFacts f) {
        if (!f.everRan()) {
            return "written " + f.idleDays() + " days ago and never invoked once";
        }
        if (!f.everWorked()) {
            return f.runs() == 1 ? "tried once, and it failed"
                    : f.runs() + " attempts, none successful";
        }
        return "only " + f.successes() + " of " + f.runs() + " attempts succeeded (" + pct(f) + ")";
    }

    /**
     * The registered skill this one is a strict specialisation of, or null.
     * <p>
     * Purely a name relationship: {@code imap_move_to_trash_by_sender_imaplib} extends
     * {@code imap_move_to_trash_by_sender}. The agent is instructed to name skills after what
     * they do, so a name that is another name plus a qualifier is a specialisation by
     * construction. The general one must itself work and be at least as reliable, so a working
     * narrow skill is never dropped in favour of a broad one that does not deliver.
     */
    private static String supersededBy(SkillFacts f, Map<String, SkillFacts> byName) {
        String best = null;
        for (SkillFacts other : byName.values()) {
            if (other.name().equals(f.name())) continue;
            if (!f.name().startsWith(other.name() + "_")) continue;
            // Redundant today — a general skill with no successes has rate 0, and the rate test
            // below already rejects it, while rule 1 has retired it before this runs. Kept because
            // it states the intent directly: never hand a skill's job to one that has never done it.
            if (!other.everWorked()) continue;
            if (other.successRatePermille() < f.successRatePermille()) continue;
            // Prefer the longest matching general name, i.e. the nearest ancestor.
            if (best == null || other.name().length() > best.length()) best = other.name();
        }
        return best;
    }

    private static String pct(SkillFacts f) {
        return Math.round(f.successRatePermille() / 10.0) + "%";
    }

    // ── acting on it ─────────────────────────────────────────────────────────

    /**
     * Carry out the plan.
     *
     * @param dryRun when true nothing is moved and the same list comes back with
     *               {@code performed=false} — how the ops endpoint shows its working before
     *               anything changes
     */
    public List<Retirement> run(boolean dryRun) {
        List<Retirement> plan = plan();
        if (plan.isEmpty()) {
            log.info("Skill maintenance: nothing to retire.");
            return plan;
        }
        if (dryRun) {
            log.info("Skill maintenance (dry run) would retire {}: {}", plan.size(),
                    plan.stream().map(Retirement::skill).toList());
            return plan;
        }

        List<Retirement> done = new ArrayList<>();
        for (Retirement r : plan) {
            boolean ok = registry.retire(r.skill(), r.rule() + " — " + r.reason()).isPresent();
            done.add(new Retirement(r.skill(), r.rule(), r.reason(), ok));
            try {
                // A non-null actor: events.user_id is NOT NULL, so passing null threw and the
                // catch below swallowed it, leaving every retirement with no audit trail at all.
                eventLog.log(SYSTEM_ACTOR, null, "skill.retired", ok ? "info" : "warn",
                        r.skill() + " — " + r.reason(),
                        "{\"rule\":\"" + r.rule() + "\",\"moved\":" + ok + "}", 0);
            } catch (Exception e) {
                log.warn("Could not record the retirement of {}: {}", r.skill(), e.getMessage());
            }
        }
        log.warn("Skill maintenance retired {} skill(s): {}", done.size(),
                done.stream().map(Retirement::skill).toList());
        return done;
    }

    /**
     * The automatic pass, daily.
     * <p>
     * Daily rather than hourly because nothing it acts on changes quickly: every input is a
     * 90-day-old fact. Running it more often would only add chances to get it wrong. It starts an
     * hour after boot so a restart never coincides with a retirement, which keeps the two easy to
     * tell apart in the log when something does go wrong.
     * <p>
     * It acts rather than only reporting, because a maintenance pass that needs a human to press
     * a button is not maintenance. Its safety comes from the rules demanding evidence and from
     * every retirement being a reversible move, not from asking first.
     */
    @Scheduled(initialDelay = 3_600_000L, fixedDelay = 86_400_000L)
    public void scheduledPass() {
        try {
            if (!config.getSkills().isAutoRetire()) {
                List<Retirement> would = plan();
                if (!would.isEmpty()) {
                    log.info("Skill maintenance is not enabled (ownclaw.skills.auto-retire). It "
                                    + "would retire {}: {}. Review with POST /api/ops/skills/"
                                    + "maintenance, then enable it.",
                            would.size(), would.stream().map(Retirement::skill).toList());
                }
                return;
            }
            run(false);
        } catch (Exception e) {
            // Never let maintenance take the scheduler's thread down with it.
            log.error("Skill maintenance pass failed: {}", e.getMessage(), e);
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
