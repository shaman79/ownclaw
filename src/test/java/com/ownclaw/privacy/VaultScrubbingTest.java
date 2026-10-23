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

        VaultSecrets.set("u9", Map.of("OLD_PASS", "from-before-a-restart"));
        var restarted = new CredentialVault(jdbc);
        restarted.init();
        assertEquals(Map.of("SSH_PRIVATE_KEY", SECRET), VaultSecrets.of("u1"), "loaded as the vault starts");
        assertEquals(Map.of(), VaultSecrets.of("u9"), "and nothing the vault does not hold");

        restarted.deleteCredential("u1", "SSH_PRIVATE_KEY");
        assertEquals(Map.of(), VaultSecrets.of("u1"),
                "deleting is how a value stored by mistake stops being replaced in every text");
        restarted.storeCredential("u1", "ROUTER_PASSWORD", "admin");
        assertTrue(VaultSecrets.of("u1").containsValue("admin"));
        restarted.deleteCredential("u1", "ROUTER_PASSWORD");
        assertFalse(VaultSecrets.of("u1").containsValue("admin"), "a short one alike");
    }

    @Test
    @DisplayName("a changed value's old one stays scrubbed, at every door and in a running task")
    void rotation() {
        VaultSecrets.set("u1", Map.of("ROUTER_PASSWORD", "old-pass-123"));
        VaultSecrets.set("u1", Map.of("ROUTER_PASSWORD", "new-pass-456"));
        assertEquals(Map.of("ROUTER_PASSWORD", "new-pass-456", "ROUTER_PASSWORD~prev", "old-pass-123"),
                VaultSecrets.of("u1"));
        assertEquals("old \u00abvault:ROUTER_PASSWORD~prev\u00bb, new \u00abvault:ROUTER_PASSWORD\u00bb",
                VaultSecrets.scrub("u1", "old old-pass-123, new new-pass-456"));
        VaultSecrets.set("u1", Map.of("ROUTER_PASSWORD", "third-pass-789"));
        assertEquals(3, VaultSecrets.of("u1").size(), "each value it held: " + VaultSecrets.of("u1"));
        VaultSecrets.set("u1", Map.of());
        assertEquals(Map.of(), VaultSecrets.of("u1"), "deleted: every value it held goes with it");
        VaultSecrets.set("u2", Map.of("ROUTER_PIN", "4821"));
        VaultSecrets.set("u2", Map.of());
        assertEquals(Map.of(), VaultSecrets.of("u2"), "a short one goes: \"4821\" is in many a text");

        VaultSecrets.clear();
        var ctx = new AgentContext("u1", "t1", "check the router");
        ctx.setSecretValues(Map.of("ROUTER_PASSWORD", "old-pass-123"));
        VaultSecrets.set("u1", Map.of("ROUTER_PASSWORD", "new-pass-456"));
        assertTrue(ctx.secretValues().containsValue("old-pass-123"), "the task's earlier results hold it");
        assertTrue(ctx.secretValues().containsValue("new-pass-456"));
    }

    @Test
    @DisplayName("the log is scrubbed of every user's values at once, the longest first across users too")
    void everyUserAtOnce() {
        VaultSecrets.set("u1", Map.of("PIN", "hunter22"));
        VaultSecrets.set("u2", Map.of("PASSWORD", "hunter22-and-more"));
        String out = VaultSecrets.scrubAll("u2 typed hunter22-and-more, u1 typed hunter22");
        assertFalse(out.contains("and-more") || out.contains("hunter22"), out);
    }

    @Test
    @DisplayName("a value is scrubbed in every form a text holds it in: JSON, Python's, a URL's, HTML's, CR LF, a line at a time, unquoted")
    void everyForm() throws Exception {
        String v = "Zq7-p\u0159\u00edli\u0161 \"quoted\" back\\slash\nZq7-second-line\n\tZq7-third";
        var vault = Map.of("SSH_PRIVATE_KEY", v);
        var texts = new ArrayList<String>();
        texts.add(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("stderr", v)));
        texts.add("{\"k\": \"Zq7-p\\u0159\\u00edli\\u0161 \\\"quoted\\\" back\\\\slash\\nZq7-second-line\\n\\tZq7-third\"}");
        texts.add("Command '['ssh', '-o', 'Zq7-p\u0159\u00edli\u0161 \"quoted\" back\\\\slash\\nZq7-second-line\\n\\tZq7-third']' returned 255");
        texts.add("https://h/?k=" + java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        texts.add("<pre>" + v.replace("&", "&amp;").replace("\"", "&quot;") + "</pre>");
        texts.add(v.replace("\n", "\r\n"));
        texts.add("the live call's latest line: Zq7-second-line");
        for (String text : texts) {
            String out = Redactor.scrubVault(text, vault).text();
            assertFalse(out.contains("Zq7"), "left in: " + out);
        }
        assertEquals("pw \u00abvault:P\u00bb", Redactor.scrubVault("pw hunter22x", Map.of("P", "\"hunter22x\"")).text(),
                "typed in quotes, printed without them");
        assertEquals("pw \u00abvault:K\u00bb", Redactor.scrubVault("pw abc1\r\nxy2\r\nz3!", Map.of("K", "abc1\nxy2\nz3!")).text(),
                "lines too short to be removed one by one, ended by CR LF");
        assertEquals("<b>\u00abvault:K\u00bb</b>", Redactor.scrubVault("<b>a&amp;b&lt;c&gt;d</b>", Map.of("K", "a&b<c>d")).text(),
                "with HTML's entities");
    }

    @Test
    @DisplayName("a value is scrubbed as Python prints it: repr with both quotes and unprintables, inside JSON, quoted for a URL or a shell")
    void pythonsForms() {
        String v = "pa'ss\"word~1*2 x\u00a0y\u001b";
        var vault = Map.of("P", v);
        for (String text : List.of(
                // repr / str(list) / CalledProcessError: both quotes, so single, the single escaped
                "Command '['curl', '-u', 'user:pa\\'ss\"word~1*2 x\\xa0y\\x1b']' returned 22",
                // the runner's json.dumps of a result holding that repr
                "{\"error\": \"Command '['curl', 'pa\\\\'ss\\\"word~1*2 x\\\\xa0y\\\\x1b']'\"}",
                // json.dumps, ensure_ascii as by default: lower-case escapes, NBSP escaped
                "{\"pw\": \"pa'ss\\\"word~1*2 x\\u00a0y\\u001b\"}",
                // requests' query string: quote_plus keeps '~', encodes '*'
                "401 Client Error for url: https://h/api?p=pa%27ss%22word~1%2A2+x%C2%A0y%1B",
                // shlex.quote
                "ssh -o 'pa'\"'\"'ss\"word~1*2 x\u00a0y\u001b' host",
                // html.escape
                "<td>pa&#x27;ss&quot;word~1*2 x\u00a0y\u001b</td>")) {
            String out = Redactor.scrubVault(text, vault).text();
            assertFalse(out.contains("ss") && out.contains("word~1"), "left in: " + out);
        }
        String json = "{\"type\": \"service_account\", \"private_key\": \"-----BEGIN PRIVATE KEY-----\\nRkFLRS1HQ1AtS0VZLUJPRFktTElORQ==\\n-----END PRIVATE KEY-----\\n\"}";
        String printed = "key:\nRkFLRS1HQ1AtS0VZLUJPRFktTElORQ==\n";
        assertFalse(Redactor.scrubVault(printed, Map.of("GCP_KEY", json)).text().contains("RkFLRS1HQ1A"),
                "a JSON credential's key, printed decoded, a line at a time");
        assertEquals("the type is service_account here",
                Redactor.scrubVault("the type is service_account here",
                        Map.of("GCP_KEY", "{\n\"type\": \"service_account\",\n\"k\": \"RkFLRS1HQ1AtS0VZ\"\n}")).text(),
                "a word of a line is no form of its own, but key material");
    }

    /** Scrubbed of {@code value}: changed when {@code changes}, and then holding none of its secret's characters' run. */
    private static void changes(String why, String value, String text, boolean changes) {
        String out = Redactor.scrubVault(text, Map.of("K", value)).text();
        if (changes) assertTrue(out.contains("\u00abvault:K"), why + ": " + out);
        else assertEquals(text, out, why);
    }

    @Test
    @DisplayName("each form a value is printed in, one by one, and what is no form of it")
    void formByForm() throws Exception {
        changes("json.dumps of a repr, ASCII escapes as by default", "Zq7-p\u0159\u00edli\u0161\\heslo", "{\"error\": \"Command '['curl', '-u', 'user:Zq7-p\\u0159\\u00edli\\u0161\\\\\\\\heslo']' returned non-zero exit status 1.\"}", true);
        changes("json.dumps of json.dumps", "Zq7-p\u0159\u00edli\u0161\\heslo", "{\"row\": \"{\\\"stderr\\\": \\\"Zq7-p\\\\u0159\\\\u00edli\\\\u0161\\\\\\\\heslo\\\"}\"}", true);
        changes("Jackson's escapes: control characters in upper case", "Zq7\u001bx\u007fy-secret", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("k", "Zq7\u001bx\u007fy-secret")), true);
        changes("json.dumps escapes DEL", "Zq7\u001bx\u007fy-secret", "{\"k\": \"Zq7\\u001bx\\u007fy-secret\"}", true);
        changes("Java's URLEncoder: '*' kept, '~' encoded", "Zq7 x*y~z-secret", "q=" + java.net.URLEncoder.encode("Zq7 x*y~z-secret", java.nio.charset.StandardCharsets.UTF_8), true);
        changes("Python's quote: a blank as %20", "Zq7 x*y~z-secret", "https://h/Zq7%20x%2Ay~z-secret", true);
        changes("HTML with &#39;", "Zq7'apos-secret", "<td>Zq7&#39;apos-secret</td>", true);
        changes("repr in double quotes: a single quote and no double", "Zq7'it\u00a0x-secret", "['ssh', \"Zq7'it\\xa0x-secret\"]", true);
        changes("a WireGuard config's key line, alone", "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.0.0.2/24", "PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", true);
        changes("... and its key, printed without its line", "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.0.0.2/24", "peer key yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk= used", true);
        changes(".netrc line, alone", "machine git.local login bob password s3cr3tPassw0rd\nmachine nas login bob password Other9Pass", "machine git.local login bob password s3cr3tPassw0rd", true);
        changes("a .env's password line, alone", "DB_HOST=db01.internal\nVERSION=v1.2.3-rc4\nDB_PASSWORD=P@ssword!", "DB_PASSWORD=P@ssword!", true);
        changes("backup codes, one of them", "47291836\n18273645\n55102938", "use backup code 18273645 now", true);
        changes("a word of a config's lines is no form of its own", "DB_HOST=db01.internal\nVERSION=v1.2.3-rc4\nDB_PASSWORD=P@ssword!", "ping db01.internal, version v1.2.3-rc4", false);
        changes("a short value's encoded form, inside text encoded alike", "@dmin1", "payload=%7B%22pw%22%3A%22%40dmin1%22%7D", true);
        changes("a quoted value is judged without its quotes: too short", "\"12\"", "at 12:30, 12 files", false);
        changes("... and a short one as a word", "\"admin1\"", "sysadmin1 and admin10", false);
        changes("... found as one", "\"admin1\"", "pw admin1.", true);
        changes("a value too short to remove has no forms", "<", "a &lt; b", false);
        changes("a JSON line break before a short value is no part of a word", "admin12", "{\"stderr\":\"Password:\\nadmin12\\nOK\"}", true);
        changes("an escaped backslash and n are letters of the word", "admin12", "{\"path\":\"C:\\\\nadmin12\\\\x\"}", false);
        changes("a long identifier in lower case is no key material", "{\n\"auth_provider_x509_cert_url\": \"https://www.googleapis.com/oauth2/v1/certs\",\n\"private_key_id\": \"Zq7b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9\"\n}", "the auth_provider_x509_cert_url field", false);
        changes("a key after NAME=, printed alone", "WG_PRIVATE_KEY=yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nWG_ADDRESS=10.0.0.2/24", "using yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk= now", true);
        changes("a kubeconfig's token, printed alone", "apiVersion: v1\nusers:\n- name: admin\n  user:\n    token: eyJhbGciOiJSUzI1NiIsImtpZCI6IkZBS0UifQ.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQifQ.RkFLRS1TSUdOQVRVUkUtMTIzNDU2", "Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IkZBS0UifQ.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQifQ.RkFLRS1TSUdOQVRVUkUtMTIzNDU2", true);
        changes(".netrc's password, in a failed curl", "machine git.local login bob password s3cr3tPassw0rd\nmachine nas login bob password Other9Pass", "Command '['curl', '-u', 'bob:s3cr3tPassw0rd', 'https://git.local']' returned non-zero exit status 1.", true);
        changes(".pgpass's password, alone", "db01.internal:5432:app:app:Pg9xLm2Qw7\n*:*:*:backup:Bk7zQ2", "PGPASSWORD=Pg9xLm2Qw7 psql", true);
        changes("a .env password under sixteen characters, alone", "DB_HOST=db01.internal\nDB_PASSWORD=Xk9#mP2vLq", "{\"pw\": \"Xk9#mP2vLq\"}", true);
        changes("a word of prose after a secret's name is no secret", "router notes\nthe token value is in the drawer", "pick a value from the list", false);
        changes("a key after a name that is no secret's, printed alone", "ORG_ID=acme\nWEBHOOK=yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "using yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk= now", true);
        changes("a JSON Web Token after a name that is no secret's, alone", "kind: Config\nid-data: eyJhbGciOiJSUzI1NiIsImtpZCI6IkZBS0UifQ.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQifQ.RkFLRS1TSUdOQVRVUkUtMTIzNDU2", "jwt eyJhbGciOiJSUzI1NiIsImtpZCI6IkZBS0UifQ.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQifQ.RkFLRS1TSUdOQVRVUkUtMTIzNDU2 used", true);
    }

    @Test
    @DisplayName("another private key's PEM block is still found by its armor and removed whole, though the vault holds a key of its kind")
    void anotherKeyOfTheSameKind() throws Exception {
        JdbcTemplate jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        String armor = "-----BEGIN OPENSSH PRIVATE KEY-----";
        String shared = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW";
        String mine = armor + "\n" + shared + "\nRkFLRS1NWS1PV04tS0VZLUJPRFk=\n-----END OPENSSH PRIVATE KEY-----";
        String other = armor + "\n" + shared + "\nRkFLRS1PVEhFUi1LRVktQk9EWQ==\n-----END OPENSSH PRIVATE KEY-----";
        String out = new Redactor(jdbc).filter("u1", "a new key for the NAS:\n" + other + "\ndone",
                Map.of("SSH_PRIVATE_KEY", mine), new Redactor.Tally());
        assertFalse(out.contains("RkFLRS1PVEhFUi1LRVktQk9EWQ"), "its body went out: " + out);
        assertTrue(out.endsWith("done"), out);
    }

    @Test
    @DisplayName("what the ops and debug APIs answer is scrubbed on the way out: rows and log lines written before their door scrubbed")
    void tracesOnTheWayOut() {
        holds("u1");
        var body = new LinkedHashMap<String, Object>();
        body.put("logs", List.of("2026-10-01 INFO reasoning: " + SECRET));
        body.put("rows", List.of(Map.of("progress_msg", "logged in with " + SECRET, "pct", 50)));
        Object out = new com.ownclaw.interfaces.web.ScrubbedTraces().beforeBodyWrite(body, null, null, null, null, null);
        assertClean(out);
        assertTrue(String.valueOf(out).contains("pct=50"), String.valueOf(out));
    }

    @Test
    @DisplayName("a frame kept to show the next tab is scrubbed as it is shown, of a value stored after it was kept")
    void aKeptFrameIsScrubbedWhenShown() {
        var emitter = new ChatStatusEmitter();
        emitter.running("u1", "t1", () -> null);
        emitter.emitForTask("u1", "t1", StatusMessage.Type.STEP, "read " + SECRET, null);
        holds("u1");
        assertClean(emitter.doing("u1"));
    }

    @Test
    @DisplayName("the prompts name only the vault's keys that a name may be: one stored as a typed value is not sent")
    void promptsNameOnlyNames() {
        var ctx = new AgentContext("u1", "t1", "hello");
        ctx.setCredentialKeys(List.of("SMTP_PASS=HUNTER2", "SMTP_HOST", "ROUTER-PASS", "IMAP:PW", "P@SSW0RD!"));
        assertEquals(List.of("SMTP_HOST", "ROUTER-PASS"), ctx.credentialKeys(),
                "a name of an older spelling stays: a skill still reads it");
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
