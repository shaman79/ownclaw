package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A link that opens the chat with a message in the box (#task=...): read from the address before
 * anything else and taken out of it, put in the box as text once the chat is on screen, and never
 * sent by the page. What it does in a browser is driven end to end outside the suite; this pins
 * the rules in the page's code, whitespace collapsed.
 */
class LinkPageTest {

    static String page;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = LinkPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "index.html is on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    static String function(String name) {
        int start = page.indexOf("function " + name + "(");
        assertTrue(start >= 0, name + " is not on the page");
        int end = page.indexOf(" function ", start + 1);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    @Test
    @DisplayName("the link is read first thing, kept for the tab, and taken out of the address")
    void readFirstAndTakenOut() {
        int script = page.indexOf("<script> (function() {");
        assertTrue(script > 0);
        assertTrue(page.indexOf("captureLink(); ") > script && page.indexOf("captureLink(); ") < page.indexOf("var authOverlay"),
                "before the page does anything else");
        String capture = function("captureLink");
        assertTrue(capture.contains("new URLSearchParams(location.hash.slice(1))"), capture);
        assertTrue(capture.contains("history.replaceState(null, '', location.pathname + location.search);"),
                "out of the address, so a reload or the history does not put it in again: " + capture);
        assertTrue(function("keepLink").contains("sessionStorage.setItem(LINK_KEY, JSON.stringify(link));"));
        assertTrue(page.contains("var LINK_KEY = 'ownclaw.pendingTask';"));
        assertTrue(page.contains("window.addEventListener('hashchange', function() { if (captureLink()) applyLink(); });"),
                "a link opened in a tab already on the page");
    }

    @Test
    @DisplayName("it goes in the box as text, once the chat is on screen, and the page never sends it")
    void textInTheBoxNeverSent() {
        String apply = function("applyLink");
        assertTrue(apply.contains("if (!link || !authToken || !ws || ws.readyState !== 1 || !displayedSessionId) return;"),
                "only once signed in and the chat is on screen: " + apply);
        assertTrue(page.contains("loadEarlierNearTop(); // The chat is on screen: a link's message waiting for it goes in the box. applyLink(); })"));
        String fill = function("fillFromLink");
        assertTrue(fill.contains("inputEl.value = text;"), fill);
        for (String f : new String[] {apply, fill, function("showLinkBanner")}) {
            assertFalse(f.contains("send(") && !f.contains("newChat("), "the page sends nothing of it: " + f);
            assertFalse(f.contains("ws.send("), f);
        }
        assertFalse(fill.contains("innerHTML"), fill);
        String banner = function("showLinkBanner");
        assertEquals(1, banner.split("innerHTML", -1).length - 1, banner);
        assertTrue(banner.contains("linkBanner.innerHTML = '';") && banner.contains("node('span', 'link-banner-text', words)"),
                "the banner's words as text: " + banner);
        assertTrue(page.contains("var LINK_MAX = 1500;") && apply.contains("Array.from(String(link.task))"),
                "cut by character: " + apply);
        assertTrue(apply.contains("if (inputEl.value.trim() && inputEl.value !== linkText) {"),
                "a draft is not replaced without asking: " + apply);
    }
}
