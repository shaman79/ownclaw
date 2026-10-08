package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of a chat's time vs cost and thinking effort: the two controls under Send, what
 * they show when a chat is opened, what a message is sent with, and the settings page's defaults.
 * There is no JS engine in the suite, so the lines that decide are pinned as text, whitespace
 * collapsed; the page itself is driven in a browser outside it.
 */
class ChatOptionsPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = ChatOptionsPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    /** The body of a function of the page, from its declaration to the next function declared at its level. */
    static String function(String name) {
        int start = page.indexOf("function " + name + "(");
        assertTrue(start >= 0, name + " is not on the page");
        int end = page.indexOf(" function ", start + 1);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    @Test
    @DisplayName("the composer offers each option's stops and a default that follows the settings page")
    void theControls() {
        assertTrue(page.contains("<select id=\"cost-mode\"> <option value=\"\">Default</option> "
                + "<option value=\"fast\">Fastest</option> <option value=\"cheaper\">Cheaper</option> "
                + "<option value=\"free\">Free</option> </select>"), "time vs cost");
        assertTrue(page.contains("<select id=\"effort-level\"> <option value=\"\">Default</option> "
                + "<option value=\"low\">Low</option> <option value=\"medium\">Medium</option> "
                + "<option value=\"high\">High</option> </select>"), "the thinking effort");
        assertTrue(page.indexOf("class=\"composer-options\"") > page.indexOf("<button id=\"send\""),
                "under Send, in the composer");
    }

    @Test
    @DisplayName("a chat opened shows its choice, or the default it follows by name; a message is sent with what is chosen")
    void shownAndSent() {
        String load = function("loadSessionMessages");
        assertTrue(load.contains("showChatOptions(data.options, data.defaults);"), load);
        String show = function("showChatOptions");
        assertTrue(show.contains("costSel.options[0].textContent = 'Default: ' + COST_NAMES[defaults.costMode];")
                && show.contains("effortSel.options[0].textContent = 'Default: ' + EFFORT_NAMES[defaults.effort];"),
                "the default state names the default: " + show);
        assertTrue(show.contains("costSel.value = options.costMode || '';")
                && show.contains("effortSel.value = options.effort || '';"), show);
        assertTrue(function("chosenOptions").contains(
                "return { costMode: costSel.value || null, effort: effortSel.value || null };"));
        assertTrue(function("send").contains("var options = chosenOptions(); var payload = { message: text, "
                + "clientId: clientId, costMode: options.costMode, effort: options.effort };"), "sent with every message");
        // Mutation: leave the options out of the payload -> every message clears the chat's choice.
    }

    @Test
    @DisplayName("Send is promised to reach the running task only with what it was sent with chosen")
    void theSteeringPromise() {
        assertTrue(page.contains("steeredChatId = data.sessionId; steeredOptions = data.options;"));
        assertTrue(function("updateComposer").contains("&& chosen.costMode === steeredOptions.costMode "
                + "&& chosen.effort === steeredOptions.effort;"));
        assertTrue(page.contains("costSel.addEventListener('change', updateComposer); "
                + "effortSel.addEventListener('change', updateComposer);"), "and says so as soon as one changes");
    }

    @Test
    @DisplayName("the settings page sets the defaults, and says a chat can override them")
    void theSettingsAreTheDefaults() {
        assertTrue(page.contains("'<label for=\"settings-cost\">Default time vs cost</label>'"));
        assertTrue(page.contains("'<label for=\"settings-effort\">Default thinking effort</label>'"));
        String hint = "'<div class=\"field-hint\">Each chat can override it next to the message box.</div>'";
        assertEquals(2, page.split(java.util.regex.Pattern.quote(hint), -1).length - 1, "under each of the two");
    }
}
