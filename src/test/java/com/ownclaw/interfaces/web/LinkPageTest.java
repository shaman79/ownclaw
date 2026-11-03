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

    /** A function of the page, from its name to its closing brace. */
    static String function(String name) {
        int start = page.indexOf("function " + name + "(");
        assertTrue(start >= 0, name + " is not on the page");
        int depth = 0;
        for (int i = page.indexOf('{', start); i < page.length(); i++) {
            if (page.charAt(i) == '{') depth++;
            else if (page.charAt(i) == '}' && --depth == 0) return page.substring(start, i + 1);
        }
        throw new AssertionError(name + " does not end");
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
    @DisplayName("it goes in the box as text, once the chat is on screen, and nothing of the link's code sends, clicks or submits")
    void textInTheBoxNeverSent() {
        String apply = function("applyLink");
        assertTrue(apply.contains("if (!link || !authToken || !ws || ws.readyState !== 1 || !displayedSessionId) return;"),
                "only once signed in and the chat is on screen: " + apply);
        assertTrue(page.contains("loadEarlierNearTop(); // The chat is on screen: a link's message waiting for it goes in the box. applyLink(); })"));
        String fill = function("fillFromLink");
        assertTrue(fill.contains("inputEl.value = text;"), fill);
        for (String name : new String[] {"captureLink", "keepLink", "waitingLink", "applyLink", "makeLinkChat",
                "linkTextOf", "fillFromLink", "showLinkBanner", "hideLinkBanner"}) {
            String f = function(name);
            for (String sends : new String[] {"send(", ".click(", "dispatchEvent(", "KeyboardEvent", "sendBtn", "submit"}) {
                assertFalse(f.contains(sends), name + " holds " + sends + ": " + f);
            }
        }
        assertFalse(fill.contains("innerHTML"), fill);
        String banner = function("showLinkBanner");
        assertEquals(1, banner.split("innerHTML", -1).length - 1, banner);
        assertTrue(banner.contains("linkBanner.innerHTML = '';") && banner.contains("node('span', 'link-banner-text', words)"),
                "the banner's words as text: " + banner);
        assertTrue(apply.contains("if (inputEl.value.trim() && inputEl.value !== linkText) {"),
                "a draft is not replaced without asking: " + apply);
        // Mutation (from review): send(false) at the end of applyLink, or sendBtn.click() in fillFromLink.
    }

    @Test
    @DisplayName("a command is refused, the text is shaped so none of it hides, and green is for the signed text alone, as it is in the box")
    void nothingHidesAndGreenMeansWhatItSays() {
        assertTrue(page.contains("var LINK_COMMAND = /^[\\s\\p{Z}\\p{Cc}\\p{Cf}]*\\//u;"),
                "a slash after any blanks, invisible ones too, as the server reads a command");
        assertTrue(page.contains("var LINK_HIDDEN = /(?![\\n\\u200C\\u200D\\uFE0E\\uFE0F])[\\p{Cf}\\p{Cc}\\p{Default_Ignorable_Code_Point}]/u;"),
                "every format, control and default-ignorable character but a line break and the emoji joiners and selectors");
        String apply = function("applyLink");
        int refused = apply.indexOf("if (refused) { keepLink(null); showLinkBanner(refused, false); return; }");
        assertTrue(apply.contains("var refused = !shaped.text ? ") && apply.contains(": LINK_COMMAND.test(shaped.text) ? ")
                && apply.contains(": LINK_HIDDEN.test(shaped.text) ? ") && refused > 0, apply);
        assertTrue(refused < apply.indexOf("makeLinkChat(link);") && refused < apply.indexOf("fillFromLink(link, shaped)"),
                "refused before a chat is made for it or anything is put in the box: " + apply);
        String shape = function("linkTextOf");
        assertTrue(shape.contains("replace(/\\r\\n?/g, '\\n')") && shape.contains("if (l || (kept.length && kept[kept.length - 1])) kept.push(l);"),
                "line ends as the box writes them, no run of blank lines: " + shape);
        assertTrue(page.contains("var BLANK_RUN = /[\\s\\p{Z}\\u115F\\u1160\\u2800\\u3164\\uFFA0]+/gu;")
                && shape.contains("var l = line.replace(BLANK_RUN, ' '); if (l.charAt(0) === ' ') l = l.slice(1); "
                        + "if (l.charAt(l.length - 1) === ' ') l = l.slice(0, -1);"),
                "each run of blanks on a line one space, none at its ends -- in linear time: " + shape);
        assertTrue(shape.contains("new Intl.Segmenter(undefined, { granularity: 'grapheme' })") && page.contains("var LINK_MAX = 1500;")
                && shape.contains("var n = Array.from(part).length; if (count + n > LINK_MAX) { cut = true; break; }")
                && shape.contains(".segment(text)[Symbol.iterator]()"),
                "LINK_MAX code points, cut where a character ends: " + shape);
        assertTrue(page.contains("max-height: max(160px, 45vh);"), "the box may grow past the 160px of typing for a link's message");
        assertTrue(page.contains("inputEl.style.height = Math.min(inputEl.scrollHeight, 160) + 'px';"), "typing still stops at 160px");
        String fill = function("fillFromLink");
        assertTrue(fill.contains("inputEl.scrollTop = 0;") && fill.contains("It is longer than the box shows"),
                "its start in view, and said when it does not all fit: " + fill);
        assertTrue(fill.contains("if (r.valid && !r.expired && shaped.same && inputEl.value === linkText) showLinkBanner('Guardian alert: verified.'"),
                "green only for the whole signed text, still in the box: " + fill);
        assertTrue(page.contains("if (linkText !== null && inputEl.value !== linkText && linkBanner.classList.contains('verified')) {"),
                "changed in the box, it is no longer verified");
        String logout = function("logout");
        assertTrue(logout.contains("if (linkText !== null && inputEl.value === linkText) inputEl.value = ''; hideLinkBanner();"),
                "not left for whoever signs in next: " + logout);
        assertTrue(function("hideLinkBanner").contains("keepLink(null);"), "and a link still waiting goes with the banner");
    }

    @Test
    @DisplayName("a link's chat is its own, made by its id; no chat is renamed for a link; a failure puts it in the chat on screen, a page being left does not")
    void aLinksChatIsItsOwn() {
        assertFalse(page.contains("renameSession(link"), "no chat is renamed for a link");
        assertFalse(function("captureLink").contains(".chat = "), "a link takes no other link's chat");
        String make = function("makeLinkChat");
        assertTrue(make.contains("if (makingLinkChat) return;") && make.contains("fetch('/api/chats', {")
                && make.contains("now.chat = made.sessionId;") && make.contains("switchToSession(made.sessionId);"), make);
        assertTrue(make.contains("if (!now || now.id !== link.id) { applyLink(); return; }"),
                "a link that replaced this one meanwhile is applied: " + make);
        assertTrue(make.contains("setTimeout(function() { stop.abort(); }, LINK_CHAT_WAIT);") && make.contains("signal: stop.signal,"),
                "never waited for for ever: " + make);
        assertTrue(make.contains("if (pageLeaving) return;") && make.contains("now.new = false; now.noChat = true;"),
                "a failure puts it in the chat on screen, said; a reload is no failure: " + make);
        assertTrue(page.contains("window.addEventListener('pagehide', function() { pageLeaving = true; });"));
    }

    @Test
    @DisplayName("a question's choices are settled once a page of rows is drawn, and a command answers none")
    void choicesAreSettledPageByPage() {
        String load = function("loadSessionMessages");
        assertTrue(load.contains("if (data.hasMore) holdEarlier(sessionId, msgs[0].id); settleChoices();"), load);
        assertTrue(page.contains("msgs.forEach(function(m) { drawRow(m, oldest); }); settleChoices();"),
                "an earlier page, whose questions' replies may be on the page below");
        assertTrue(function("settleChoices").contains("if (said.trim().charAt(0) === '/') continue;"),
                "a command answers nothing");
    }
}
