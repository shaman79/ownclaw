package com.ownclaw.interfaces;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialGrantService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A command's reply shows its placeholders in code. Outside it, the web chat's Markdown reads
 * "&lt;KEY&gt;" as an HTML tag, and the page's sanitiser keeps only the text inside a tag it does
 * not allow -- so "Usage: /cred set &lt;KEY&gt; &lt;VALUE&gt;" was shown as "Usage: /cred set  ".
 */
class UsageRepliesTest {

    /** A skill that declares a credential its account has not granted it and the vault does not hold. */
    static final Tool MAILER = new Tool() {
        public String name() { return "mailer"; }
        public String description() { return "sends mail"; }
        public Map<String, ToolParam> inputSchema() { return Map.of(); }
        public List<String> requiredCredentials() { return List.of("SMTP_PASS"); }
        public List<String> ungranted(String userId) { return List.of("SMTP_PASS"); }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
            return ToolResult.failure("not run in this test");
        }
    };

    /** Grants with no database behind them: granting writes nothing, and nothing has been granted. */
    static final CredentialGrantService GRANTS = new CredentialGrantService(null) {
        @Override public void grant(String userId, String skill, Collection<String> keys) { }
        @Override public List<String> ungranted(String userId, String skill, Collection<String> keys) { return List.of(); }
        @Override public List<String> revoke(String userId, String skill, Collection<String> keys) { return List.of(); }
        @Override public Map<String, List<String>> all(String userId) { return Map.of(); }
    };

    final CommandHandler commands = new CommandHandler(new ToolRegistry(List.of(MAILER)), null, null, null,
            new CredentialVault(null) {
                @Override public List<String> listCredentialKeys(String userId) { return List.of(); }
            }, GRANTS, null, new ScheduledTaskService(null, null, null, null, null, null),
            new AuthService(null, null, new OwnClawConfig()) {
                @Override public boolean isOwner(String userId) { return true; }
            }, null, null, null, null, null, null, null);

    /** A tag, as the page's Markdown would take it, left after the code spans are taken out. */
    static final Pattern TAG = Pattern.compile("<[A-Za-z][^<>]*>");

    private String reply(String command) {
        return commands.handle("owner", command).orElseThrow(() -> new AssertionError(command));
    }

    @Test
    @DisplayName("every reply that names a placeholder names it in code, where the page shows it as typed")
    void placeholdersAreInCode() {
        for (String command : List.of("/user", "/user add bob", "/user add bob pw", "/user disable", "/user telegram bob",
                "/switch next", "/cred x", "/cred list", "/cred delete", "/cred set KEY", "/grant tool",
                "/grant", "/grant mailer", "/grant mailer IMAP_PASS", "/revoke", "/revoke mailer", "/grants",
                "/bg", "/schedule help", "/schedule in 45 check the oven", "/schedule cancel x", "/help")) {
            String reply = reply(command);
            String outsideCode = reply.replaceAll("`[^`]*`", "");
            assertFalse(TAG.matcher(outsideCode).find(), command + " -> " + reply);
        }
        // Mutations: the /cred usage without its backticks -> "<KEY>" outside code; the /grant
        // usage without them -> "<skill>" outside code.
    }

    @Test
    @DisplayName("the replies of /grant and /revoke that name a placeholder are among the ones read: their usage, and where to store a credential the vault does not hold yet")
    void theGrantRepliesThatNameAPlaceholder() {
        // The control of the test above, which passes on any reply with no placeholder in it.
        assertTrue(reply("/grant").contains("`/grant <skill> [KEY ...]`"), reply("/grant"));
        assertTrue(reply("/revoke").contains("`/revoke <skill> [KEY ...]`"), reply("/revoke"));
        assertTrue(reply("/grant mailer").contains("`/cred set <KEY> <VALUE>`"), reply("/grant mailer"));
        String help = reply("/help");
        assertTrue(help.contains("`/grant <skill> [KEY ...]`") && help.contains("`/revoke <skill> [KEY ...]`")
                && help.contains("`/grants`"), help);
        assertEquals("Skill not found: tool", reply("/grant tool"), "a name no skill has is answered with no usage");
    }
}
