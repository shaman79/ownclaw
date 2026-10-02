package com.ownclaw.interfaces;

import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.CredentialVault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A command's reply shows its placeholders in code. Outside it, the web chat's Markdown reads
 * "&lt;KEY&gt;" as an HTML tag, and the page's sanitiser keeps only the text inside a tag it does
 * not allow -- so "Usage: /cred set &lt;KEY&gt; &lt;VALUE&gt;" was shown as "Usage: /cred set  ".
 */
class UsageRepliesTest {

    final CommandHandler commands = new CommandHandler(null, null, null, null,
            new CredentialVault(null) {
                @Override public List<String> listCredentialKeys(String userId) { return List.of(); }
            }, null, null, new ScheduledTaskService(null, null, null, null, null, null),
            new AuthService(null, null, new OwnClawConfig()) {
                @Override public boolean isOwner(String userId) { return true; }
            }, null, null, null, null, null, null);

    /** A tag, as the page's Markdown would take it, left after the code spans are taken out. */
    static final Pattern TAG = Pattern.compile("<[A-Za-z][^<>]*>");

    @Test
    @DisplayName("every reply that names a placeholder names it in code, where the page shows it as typed")
    void placeholdersAreInCode() {
        for (String command : List.of("/user", "/user add bob", "/user add bob pw", "/user disable", "/user telegram bob",
                "/switch next", "/cred x", "/cred list", "/cred delete", "/cred set KEY", "/grant tool",
                "/bg", "/schedule help", "/schedule in 45 check the oven", "/schedule cancel x", "/help")) {
            String reply = commands.handle("owner", command).orElseThrow(() -> new AssertionError(command));
            String outsideCode = reply.replaceAll("`[^`]*`", "");
            assertFalse(TAG.matcher(outsideCode).find(), command + " -> " + reply);
        }
        // Mutation: the /cred usage without its backticks -> "<KEY>" outside code.
    }
}
