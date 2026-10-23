package com.ownclaw.privacy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.ownclaw.agent.AgentContext;
import com.ownclaw.agent.SkillCuratorService;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.llm.EgressContext;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every door a text leaves by, other than a task's answer and the cloud call, scrubbed of the
 * secret vault values the vault holds now ({@link VaultSecrets}): the page's status frames, the
 * long-running task rows, the skill usage rows and the log -- each of which held a password a
 * skill had printed, as printed.
 */
class VaultScrubbingTest {

    @TempDir Path tmp;

    /** A value of more than one line, as an SSH private key is. */
    static final String SECRET = "Zq7-first-line\nZq7 second line\n\tZq7-third";
    static final String MARK = "«vault:SSH_PRIVATE_KEY»";

    @AfterEach
    void forget() {
        VaultSecrets.clear();
    }

    private static void holds(String userId) {
        VaultSecrets.set(userId, Map.of("SSH_PRIVATE_KEY", SECRET));
    }

    private static void assertClean(Object where) {
        String text = String.valueOf(where);
        for (String line : SECRET.split("\n")) assertFalse(text.contains(line.strip()), "holds the secret: " + text);
    }

    @Test
    @DisplayName("a value inside another is taken after it, so no part of the longer one is left")
    void longestFirst() {
        var vault = new LinkedHashMap<String, String>();
        vault.put("PIN", "1234abcd");
        vault.put("PASSWORD", "1234abcd-and-the-rest");
        var out = Redactor.scrubVault("pin 1234abcd, password 1234abcd-and-the-rest", vault);
        assertEquals("pin «vault:PIN», password «vault:PASSWORD»", out.text());
        assertEquals(2, out.count());
    }

    @Test
    @DisplayName("the vault puts a user's secret values where they are scrubbed with: as it starts, on a store, on a delete")
    void theVaultPublishes() throws Exception {
        JdbcTemplate jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        var vault = new CredentialVault(jdbc);
        vault.init();
        vault.storeCredential("u1", "SSH_PRIVATE_KEY", SECRET);
        vault.storeCredential("u1", "SMTP_HOST", "mail.example.org");
        assertEquals(Map.of("SSH_PRIVATE_KEY", SECRET), VaultSecrets.of("u1"),
                "the secret, and not the server's name, which is no secret");

        VaultSecrets.clear();
        var restarted = new CredentialVault(jdbc);
        restarted.init();
        assertEquals(Map.of("SSH_PRIVATE_KEY", SECRET), VaultSecrets.of("u1"), "loaded as the vault starts");

        restarted.deleteCredential("u1", "SSH_PRIVATE_KEY");
        assertEquals(Map.of(), VaultSecrets.of("u1"));
    }

    @Test
    @DisplayName("a value stored while a task runs is scrubbed from that task's text, beside the ones it started with")
    void aRunningTaskSeesANewValue() {
        var ctx = new AgentContext("u1", "t1", "check the server");
        ctx.setSecretValues(Map.of("ROUTER_PASSWORD", "router-pw-77"));
        holds("u1");
        assertEquals(Map.of("ROUTER_PASSWORD", "router-pw-77", "SSH_PRIVATE_KEY", SECRET), ctx.secretValues());
        assertTrue(ctx.egress("test").secretValues().containsKey("SSH_PRIVATE_KEY"), "and the cloud call's door has it");
    }

    @Test
    @DisplayName("a status frame is scrubbed in its text and in every text of its data, and so is what is kept to show the next tab")
    void statusFrames() {
        holds("u1");
        var emitter = new ChatStatusEmitter();
        var seen = new ArrayList<StatusMessage>();
        emitter.subscribe("u1", "test", seen::add);
        emitter.running("u1", "t1", () -> null);

        var data = new LinkedHashMap<String, Object>();
        data.put("output", "ssh said: " + SECRET);
        data.put("params", Map.of("command", List.of("ssh", "-i", SECRET)));
        data.put("step", 3);
        emitter.emitForTask("u1", "t1", StatusMessage.Type.STEP, "read " + SECRET, data);
        emitter.emit("u1", StatusMessage.Type.DEBUG, "TOOL RESULT: " + SECRET);

        assertEquals(2, seen.size());
        for (var m : seen) assertClean(m);
        assertEquals("ssh said: " + MARK, seen.getFirst().data().get("output"));
        assertEquals(3, seen.getFirst().data().get("step"));
        assertClean(emitter.doing("u1"));
    }

    @Test
    @DisplayName("a long-running task's row keeps its progress, result and error scrubbed -- also once its heartbeat is gone")
    void longRunningRows() throws Exception {
        JdbcTemplate jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        holds("u1");
        var emitter = new ChatStatusEmitter();
        var seen = new ArrayList<StatusMessage>();
        emitter.subscribe("u1", "test", seen::add);
        var config = new OwnClawConfig();
        var manager = new LongRunningTaskManager(jdbc, emitter, new EventLogService(jdbc), config);

        manager.register("t1", "u1", "scan", "net_scan");
        manager.reportProgress("t1", "logged in with " + SECRET, 50);
        manager.complete("t1", "done: " + SECRET);
        manager.register("t2", "u1", "scan", "net_scan");
        manager.fail("t2", "refused: " + SECRET);

        config.getTasks().setStallTimeout(-1);   // every task counts as stalled at once
        manager.register("t3", "u1", "scan", "net_scan");
        manager.detectStalledTasks();             // drops the heartbeat the user is read from
        manager.reportProgress("t3", "still " + SECRET, 60);

        var rows = jdbc.queryForList("SELECT task_id, progress_msg, result_summary, error_message FROM long_running_tasks");
        assertEquals(3, rows.size());
        assertClean(rows);
        assertTrue(String.valueOf(rows).contains(MARK), String.valueOf(rows));
        assertClean(seen);
    }

    @Test
    @DisplayName("a skill usage row keeps no secret value: not in its parameters, written as JSON, nor in its error")
    void skillUsageRows() throws Exception {
        JdbcTemplate jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        holds("u1");
        var curator = new SkillCuratorService(jdbc, new ToolRegistry(List.of()),
                new DynamicSkillRegistry(null, null, null, null, null, null, null));
        curator.recordUsage("ssh_uptime", "u1", "t1", false, 5,
                Map.of("command", "ssh -i '" + SECRET + "' host", "nested", Map.of("key", SECRET)),
                "failed: " + SECRET, null);
        var row = jdbc.queryForMap("SELECT params_json, error FROM skill_usage");
        assertClean(row);
        assertTrue(String.valueOf(row.get("params_json")).contains(MARK), String.valueOf(row));
    }

    @Test
    @DisplayName("a cloud call's context names its secret values but does not print them")
    void egressContextPrintsNoValue() {
        var egress = new EgressContext("u1", "t1", "test", null, Map.of("SSH_PRIVATE_KEY", SECRET), null, null);
        assertClean(egress.toString());
        assertTrue(egress.toString().contains("SSH_PRIVATE_KEY"));
    }

    @Test
    @DisplayName("the log, as logback-spring.xml writes it, holds no secret value: not in a message, not in an exception")
    void theLog() throws Exception {
        holds("u1");
        Path file = tmp.resolve("ownclaw.log");
        var context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        context.putProperty("LOG_FILE", file.toString());
        var configurator = new JoranConfigurator();
        configurator.setContext(context);
        try (var xml = VaultScrubbingTest.class.getResourceAsStream("/logback-spring.xml")) {
            assertNotNull(xml, "logback-spring.xml is not on the classpath");
            configurator.doConfigure(xml);
        }
        var log = context.getLogger("com.ownclaw.agent.AgentLoop");
        log.info("Reasoning: the key is {}", SECRET);
        log.error("Skill failed", new IllegalArgumentException("Invalid environment variable value: \"" + SECRET + "\""));
        context.stop();

        String written = Files.readString(file);
        assertClean(written);
        assertTrue(written.contains("INFO") && written.contains("com.ownclaw.agent.AgentLoop")
                && written.contains("Reasoning: the key is " + MARK), "Spring Boot's own line, scrubbed: " + written);
        assertTrue(written.contains("java.lang.IllegalArgumentException: Invalid environment variable value: \"" + MARK + "\""),
                "the exception, scrubbed: " + written);
        assertTrue(written.contains("\tat "), "with its stack: " + written);
    }
}
