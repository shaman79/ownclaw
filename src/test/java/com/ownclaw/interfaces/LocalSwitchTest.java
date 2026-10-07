package com.ownclaw.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.LocalMode;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.config.SetupWizardService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's local-only switch: /local, the settings page, and a restart that keeps it -- and a
 * page that loads with no internet, since a switch for no internet is no use behind a page that
 * needs it.
 */
class LocalSwitchTest {

    static CommandHandler commands(LocalMode mode, String owner) {
        return new CommandHandler(null, null, null, null, null, null, null, null,
                new AuthService(null, null, new OwnClawConfig()) {
                    @Override public boolean isOwner(String userId) { return owner.equals(userId); }
                }, null, null, null, null, null, mode);
    }

    @Test
    @DisplayName("/local on and off are the owner's, saved, and kept by a restart; /local says which it is")
    void theSwitch(@TempDir Path tmp) throws Exception {
        var db = MigratedDatabase.at(tmp.resolve("t.db"));
        var config = new OwnClawConfig();
        var settings = new SetupWizardService(db, config, new ObjectMapper(), null, null, null);
        var mode = new LocalMode(config, settings);
        var commands = commands(mode, "owner");

        assertTrue(commands.handle("owner", "/local").orElseThrow().startsWith("Local only is **off**"));
        assertTrue(commands.handle("guest", "/local on").orElseThrow().startsWith("Only the owner"));
        assertFalse(config.getMentor().isLocalOnly(), "a guest switched it");

        assertTrue(commands.handle("owner", "/local on").orElseThrow().startsWith("Local only is **on**"));
        assertTrue(config.getMentor().isLocalOnly());
        assertEquals(Optional.of("true"), settings.getSetting("local_only"));

        var restarted = new OwnClawConfig();
        new SetupWizardService(db, restarted, new ObjectMapper(), null, null, null).applyOverrides();
        assertTrue(restarted.getMentor().isLocalOnly(), "a restart forgot the switch");

        assertTrue(commands.handle("owner", "/local off").orElseThrow().startsWith("Local only is **off**"));
        assertFalse(config.getMentor().isLocalOnly());
        assertEquals(Optional.of("false"), settings.getSetting("local_only"));
        assertTrue(commands.handle("owner", "/local maybe").orElseThrow().startsWith("Usage:"));

        // The slider's middle stop: cost over speed on a chat, saved and kept by a restart.
        mode.setPreferCost(false);
        assertEquals(Optional.of("false"), settings.getSetting("prefer_cost"));
        var again = new OwnClawConfig();
        new SetupWizardService(db, again, new ObjectMapper(), null, null, null).applyOverrides();
        assertFalse(again.getMentor().isPreferCost(), "a restart forgot the slider");
        assertTrue(commands.handle("owner", "/help").orElseThrow().contains("`/local [on|off]`"));
    }

    static String resource(String path) throws Exception {
        try (InputStream in = LocalSwitchTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("the page loads nothing from the internet: its markdown library is the app's own")
    void thePageNeedsNoInternet() throws Exception {
        String page = resource("/static/index.html");
        assertFalse(Pattern.compile("<(script|link)[^>]+(src|href)=\"https?://").matcher(page).find(),
                "the page loads something from the internet");
        Matcher script = Pattern.compile("<script src=\"(/vendor/[^\"]+)\"\\s+integrity=\"sha384-([^\"]+)\"").matcher(page);
        assertTrue(script.find(), "the markdown library is not the app's own");
        byte[] library;
        try (InputStream in = LocalSwitchTest.class.getResourceAsStream("/static" + script.group(1))) {
            assertNotNull(in, script.group(1));
            library = in.readAllBytes();
        }
        assertEquals(script.group(2), Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-384").digest(library)), "the integrity hash is not the file's");


        assertTrue(page.contains("'<input type=\"range\" id=\"settings-cost\" min=\"0\" max=\"2\" step=\"1\""),
                "the time-vs-cost slider is not on the settings page");
        String flat = page.replaceAll("\\s+", " ");
        assertTrue(flat.contains("return data.local_only ? 2 : (data.prefer_cost ? 1 : 0);"), "where it stands");
        assertTrue(flat.contains("var localOnly = stop === 2;"), "the last stop is local only");
        assertTrue(flat.contains("if (stop < 2 && (stop === 1) !== !!originalData.prefer_cost) payload.prefer_cost = String(stop === 1);"),
                "the first two say whether the cloud runs the skills");
    }
}
