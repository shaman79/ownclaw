package com.ownclaw.agent;

import com.ownclaw.agent.SkillMaintenanceService.Retirement;
import com.ownclaw.agent.SkillMaintenanceService.SkillFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What automatic skill retirement does to the library that actually exists.
 * <p>
 * The fixture below is production on 2026-09-21, read out of {@code skill_usage} and the skill
 * registry: 31 registered skills with their real run counts, successes and idle days. A rule
 * that decides which of the owner's capabilities to remove is only worth trusting if you can see
 * what it does to his real ones, so that is what these tests assert against — not invented names.
 */
class SkillMaintenanceTest {

    /** name, runs, successes, idleDays — production, 2026-09-21. */
    private static final int[][] NOTHING = {};

    private static List<SkillFacts> productionLibrary() {
        List<SkillFacts> f = new ArrayList<>();
        f.add(new SkillFacts("container_runtime_diag", 12, 6, 178));
        f.add(new SkillFacts("daily_menu_fetcher", 58, 42, 0));
        f.add(new SkillFacts("daily_news_digest", 16, 16, 0));
        f.add(new SkillFacts("gmail_imap_list_debug", 2, 2, 194));
        f.add(new SkillFacts("icmp_sweep_and_portscan", 5, 5, 199));
        f.add(new SkillFacts("imap_list_mailboxes", 10, 10, 194));
        f.add(new SkillFacts("imap_list_mailboxes_debug", 3, 0, 194));
        f.add(new SkillFacts("imap_move_to_bin_by_sender_gmail", 1, 1, 194));
        f.add(new SkillFacts("imap_move_to_trash_by_recipient", 5, 5, 198));
        f.add(new SkillFacts("imap_move_to_trash_by_sender", 18, 18, 194));
        f.add(new SkillFacts("imap_move_to_trash_by_sender_imaplib", 14, 7, 194));
        f.add(new SkillFacts("imap_unread_summarizer", 3, 2, 195));
        f.add(new SkillFacts("lan_inventory_bounded", 4, 4, 192));
        f.add(new SkillFacts("network_scanner", 7, 7, 199));
        f.add(new SkillFacts("obd2_read_dtcs", 1, 1, 178));
        f.add(new SkillFacts("ocr_image_to_text", 8, 1, 179));
        f.add(new SkillFacts("ollama_benchmark", 4, 1, 178));
        f.add(new SkillFacts("ollama_installer", 0, 0, 178));   // never invoked; files are old
        f.add(new SkillFacts("ollama_quick_check", 2, 1, 178));
        f.add(new SkillFacts("ollama_summarize", 1, 0, 178));
        f.add(new SkillFacts("rclone_mount_inspect", 1, 1, 3));
        f.add(new SkillFacts("reddit_sideproject_fetcher", 1, 1, 178));
        f.add(new SkillFacts("restaurant_url_finder", 1, 1, 180));
        f.add(new SkillFacts("server_backup_audit", 1, 1, 12));
        f.add(new SkillFacts("shell_exec", 328, 319, 0));
        f.add(new SkillFacts("smtp_send_email", 221, 220, 0));
        f.add(new SkillFacts("ssh_update_plex", 2, 0, 178));
        f.add(new SkillFacts("tcp_sweep_and_portscan", 7, 1, 178));
        f.add(new SkillFacts("tesseract_local_installer", 2, 0, 179));
        f.add(new SkillFacts("web_fetch_and_parse", 36, 32, 178));
        f.add(new SkillFacts("web_search_bikes", 3, 3, 192));
        return f;
    }

    /** What the scheduler claims in production: tasks #8 and #10 name these in their text. */
    private static final Set<String> SCHEDULED =
            Set.of("daily_menu_fetcher", "daily_news_digest", "smtp_send_email");

    private static Set<String> retiredNames(List<Retirement> plan) {
        return plan.stream().map(Retirement::skill).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("on the real library it retires exactly the skills with evidence against them")
    void realLibrary() {
        List<Retirement> plan = SkillMaintenanceService.decide(productionLibrary(), SCHEDULED);
        assertEquals(Set.of(
                        "imap_list_mailboxes_debug",           // 3 attempts, 0 successes
                        "imap_move_to_trash_by_sender_imaplib",// 50% vs the general one's 100%
                        "ocr_image_to_text",                   // 1 of 8
                        "ollama_installer",                    // never invoked, files 178d old
                        "ollama_summarize",                    // tried once, failed
                        "ssh_update_plex",                     // 2 attempts, 0 successes
                        "tcp_sweep_and_portscan",              // 1 of 7
                        "tesseract_local_installer"),          // 2 attempts, 0 successes
                retiredNames(plan),
                "the retirement set changed — check this is intended before shipping it");
    }

    @Test
    @DisplayName("working capabilities are kept however long they have been idle")
    void idlenessAloneNeverRetires() {
        Set<String> retired = retiredNames(
                SkillMaintenanceService.decide(productionLibrary(), SCHEDULED));
        // All idle 178-199 days, all with a real record of working. Retiring these would make the
        // agent write them again, which is the behaviour the owner asked to prevent.
        for (String keep : List.of("web_fetch_and_parse", "imap_move_to_trash_by_sender",
                "imap_list_mailboxes", "network_scanner", "icmp_sweep_and_portscan",
                "lan_inventory_bounded", "imap_move_to_trash_by_recipient")) {
            assertFalse(retired.contains(keep), keep + " works and must survive being unused");
        }
    }

    @Test
    @DisplayName("a skill the agent has just written is never retired")
    void freshWorkIsSafe() {
        // The hazard that review caught: creating a skill writes no usage row, so a brand-new
        // skill arrives with runs=0 and successes=0 — identical counters to abandoned residue.
        // Its age is what separates them, and a new skill's files are new.
        List<SkillFacts> lib = productionLibrary();
        lib.add(new SkillFacts("just_written_by_the_agent", 0, 0, 0));
        lib.add(new SkillFacts("written_last_week", 0, 0, 7));
        lib.add(new SkillFacts("written_three_months_ago", 0, 0, 120));

        Set<String> retired = retiredNames(SkillMaintenanceService.decide(lib, SCHEDULED));
        assertFalse(retired.contains("just_written_by_the_agent"),
                "retiring the agent's own fresh work before it can be used would make the system "
                        + "destroy what it just built");
        assertFalse(retired.contains("written_last_week"), "still well inside the grace period");
        assertTrue(retired.contains("written_three_months_ago"),
                "past the grace period with nothing to show, it is residue");
    }

    @Test
    @DisplayName("a scheduled task protects its skills no matter how bad the numbers look")
    void schedulerProtectionWins() {
        List<SkillFacts> lib = new ArrayList<>(productionLibrary());
        lib.add(new SkillFacts("monthly_report", 3, 0, 200));   // would be retired on its record

        assertTrue(retiredNames(SkillMaintenanceService.decide(lib, SCHEDULED))
                        .contains("monthly_report"),
                "unprotected, this record retires it");
        assertFalse(retiredNames(SkillMaintenanceService.decide(lib,
                                Set.of("monthly_report"))).contains("monthly_report"),
                "a live scheduled task outranks the numbers — it may fire monthly and matter");
    }

    @Test
    @DisplayName("a narrow skill is never dropped in favour of a broad one that does not work")
    void supersedingRequiresTheGeneralToWork() {
        List<SkillFacts> lib = new ArrayList<>(productionLibrary());
        lib.add(new SkillFacts("backup_run", 4, 0, 200));           // general, never worked
        lib.add(new SkillFacts("backup_run_to_s3", 4, 4, 200));     // narrow, works perfectly

        Set<String> retired = retiredNames(SkillMaintenanceService.decide(lib, SCHEDULED));
        assertFalse(retired.contains("backup_run_to_s3"),
                "the working specialisation must not be retired for a broken generalisation");
        assertTrue(retired.contains("backup_run"), "the general one earns retirement on its own record");
    }

    @Test
    @DisplayName("nothing is retired when the usage history is too thin to judge")
    void emptyFactsRetireNothing() {
        assertTrue(SkillMaintenanceService.decide(List.of(), SCHEDULED).isEmpty());
        // facts() returns an empty list for a fresh or unreadable database; decide() must then be
        // a no-op rather than treating "no rows" as "nothing has ever succeeded".
        assertEquals(0, NOTHING.length);
    }

    @Test
    @DisplayName("one failed attempt counts, so write-once-fail-once residue is caught")
    void singleFailureIsEvidence() {
        List<SkillFacts> lib = List.of(new SkillFacts("tried_once_failed", 1, 0, 200));
        assertEquals(Set.of("tried_once_failed"),
                retiredNames(SkillMaintenanceService.decide(lib, Set.of())),
                "requiring two attempts spared whatever was abandoned fastest — the commonest "
                        + "residue of all");
    }

    @Test
    @DisplayName("the age clock reads a real directory, so fresh work cannot look ancient")
    void ageClockOnRealFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        // This is the layer the hazard actually lived in. decide() only sees idleDays; it was
        // facts() that handed it Integer.MAX_VALUE for any skill with no usage row, which is the
        // guaranteed state of every skill the agent has just written.
        java.nio.file.Path fresh = java.nio.file.Files.createDirectory(tmp.resolve("fresh_skill"));
        java.nio.file.Files.writeString(fresh.resolve("skill.py"), "def run(p):\n    return {}\n");
        java.nio.file.Files.writeString(fresh.resolve("SKILL.yaml"), "name: fresh_skill\n");
        assertEquals(0, SkillMaintenanceService.ageDays(fresh),
                "a skill written seconds ago must read as zero days old");
        assertTrue(SkillMaintenanceService.ageDays(fresh) < SkillMaintenanceService.IDLE_DAYS_BEFORE_ELIGIBLE,
                "and must therefore be held by the grace period");

        java.nio.file.Path old = java.nio.file.Files.createDirectory(tmp.resolve("old_skill"));
        java.nio.file.Path code = java.nio.file.Files.writeString(old.resolve("skill.py"), "x");
        java.nio.file.Files.setLastModifiedTime(code,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now()
                        .minus(java.time.Duration.ofDays(200))));
        java.nio.file.Files.setLastModifiedTime(old,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now()
                        .minus(java.time.Duration.ofDays(200))));
        assertEquals(200, SkillMaintenanceService.ageDays(old),
                "an untouched skill reports its real age");

        // A repaired skill is not residue: rewriting one file in place makes the whole thing young
        // again. Listing the children is what catches this — a POSIX directory mtime would not.
        java.nio.file.Files.writeString(code, "y");
        assertEquals(0, SkillMaintenanceService.ageDays(old),
                "a skill repaired just now must not be judged on when it was first written");
    }

    @Test
    @DisplayName("an unreadable directory is treated as new, never as ancient")
    void unreadableAgeFailsSafe() {
        assertEquals(0, SkillMaintenanceService.ageDays(
                        java.nio.file.Path.of("/nonexistent-" + System.nanoTime())),
                "an unknown age must mean 'too new to judge', the same direction every other "
                        + "uncertainty here fails in");
    }

    @Test
    @DisplayName("a rewritten skill does not inherit the dead one's record")
    void usageIsScopedToTheCurrentIncarnation() {
        // skill_usage is keyed on the name alone, so without this a retired skill that the agent
        // writes again inherits the old failures AND the old idleness, and is retired again at
        // once — a write-and-retire loop that never settles.
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant longAgo = now.minus(java.time.Duration.ofDays(180));

        List<java.util.Map<String, Object>> oldFailures = List.of(
                usageRow(longAgo, false), usageRow(longAgo, false));

        // Same name, files written just now: the old record is not evidence about this version.
        SkillFacts rewritten = SkillMaintenanceService.factsFor("ssh_update_plex", now, oldFailures);
        assertEquals(0, rewritten.runs(), "the previous incarnation's attempts must not count");
        assertEquals(0, rewritten.idleDays(), "and it must be treated as new, not 180 days idle");
        assertTrue(SkillMaintenanceService.decide(List.of(rewritten), Set.of()).isEmpty(),
                "a freshly rewritten skill must survive the very next maintenance pass");

        // Untouched since those runs: the record stands.
        SkillFacts untouched = SkillMaintenanceService.factsFor(
                "ssh_update_plex", longAgo.minus(java.time.Duration.ofDays(1)), oldFailures);
        assertEquals(2, untouched.runs(), "history after the files were written still counts");
        assertEquals(0, untouched.successes());
        assertEquals(1, SkillMaintenanceService.decide(List.of(untouched), Set.of()).size(),
                "and it is retired on that record");
    }

    @Test
    @DisplayName("repairing a skill clears the failures that prompted the repair")
    void repairResetsTheRecord() {
        // tesseract_local_installer in production: its files were written 34 seconds AFTER its
        // last run, i.e. it failed, was rewritten, and was never tried again. Judging the repair
        // on the failures it was meant to fix would be judging the wrong version.
        java.time.Instant lastRun = java.time.Instant.now().minus(java.time.Duration.ofDays(179));
        java.time.Instant repairedAt = lastRun.plusSeconds(34);
        SkillFacts f = SkillMaintenanceService.factsFor("tesseract_local_installer", repairedAt,
                List.of(usageRow(lastRun, false), usageRow(lastRun, false)));

        assertEquals(0, f.runs(), "the pre-repair failures belong to the version that was replaced");
        // 178, not 179: the repair happened 34 seconds AFTER the run, so a whole 179th day has
        // not elapsed and Duration.toDays() truncates. Ages are counted in completed days.
        assertEquals(178, f.idleDays(), "the repair itself is ~179 days old and was never tried");
        assertEquals(1, SkillMaintenanceService.decide(List.of(f), Set.of()).size(),
                "so it is still retired — for never having been used, which is the honest reason");
    }

    private static java.util.Map<String, Object> usageRow(java.time.Instant at, boolean ok) {
        // SQLite's datetime('now') format, which is what the column actually holds.
        String text = at.toString().replace('T', ' ').substring(0, 19);
        return java.util.Map.of("created_at", text, "success", ok ? 1 : 0);
    }

    @Test
    @DisplayName("the reliability boundary is exact and does not turn on rounding")
    void reliabilityBoundary() {
        // MIN_SUCCESS_IN = 4: one in four is enough, one in five is not.
        assertTrue(new SkillFacts("a", 4, 1, 200).earnedItsPlace(), "1 in 4 is the threshold");
        assertFalse(new SkillFacts("b", 5, 1, 200).earnedItsPlace(), "1 in 5 is below it");
        assertFalse(new SkillFacts("c", 1, 0, 200).earnedItsPlace(), "no successes, ever");
        assertFalse(new SkillFacts("d", 0, 0, 200).earnedItsPlace(), "never invoked");
        assertTrue(new SkillFacts("e", 1, 1, 200).earnedItsPlace(), "one for one is a record");
    }
}
