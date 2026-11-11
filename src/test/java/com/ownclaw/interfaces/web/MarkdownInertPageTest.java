package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every answer and progress row is rendered from Markdown that can quote a web page or a mail:
 * it is parsed and cleaned in a document that runs nothing and loads nothing. Made in the page,
 * an element runs its handlers as it is made -- {@code <details open ontoggle>}, {@code <audio
 * src=x onerror>} and {@code <svg><image onerror>} ran before the sanitizer took them out, with
 * the session's token in reach (review, 2026-11-11). There is no JS engine in the suite, so the
 * lines that decide are pinned as text; the behaviour was checked in headless Chromium, the
 * payloads above running before the change and none after it, ordinary Markdown rendering the same.
 */
class MarkdownInertPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = MarkdownInertPageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    private static String between(String from, String to) {
        String s = page.substring(page.indexOf(from));
        return s.substring(0, s.indexOf(to));
    }

    @Test
    @DisplayName("Markdown is parsed into an inert document and cleaned there; nothing of it is made in the page first")
    void markdownIsParsedInert() {
        String render = between("function renderMarkdown(text) {", "function safeSnippet(");
        assertTrue(render.contains("new DOMParser().parseFromString("), render);
        assertTrue(render.contains("var holder = inert.body.firstChild; holder.innerHTML = html;"), render);
        assertTrue(render.contains("return mdSanitize(holder).innerHTML;"), render);
        assertFalse(render.contains("document.createElement"), "a holder made in the page runs its handlers: " + render);
        String sanitize = between("function mdSanitize(root) {", "function renderMarkdown(text) {");
        assertTrue(sanitize.contains("root.ownerDocument.createTextNode("), "replacements made in the inert document: " + sanitize);
        assertFalse(sanitize.contains(" document.createTextNode("), sanitize);
        // Mutation: holder = document.createElement('div') again -> the first two assertions fail.
    }

    @Test
    @DisplayName("a free class is dropped, code keeps only marked's language-*, and comments are removed")
    void classesAndComments() {
        String sanitize = between("function mdSanitize(root) {", "function renderMarkdown(text) {");
        assertTrue(page.contains("CODE: ['class'], PRE: ['class'], SPAN: [], DIV: [],"), "no class on a span or a div");
        assertTrue(sanitize.contains("/^language-[\\w+#.-]+$/.test(c)"), "only language-* on code: " + sanitize);
        assertTrue(sanitize.contains("createTreeWalker(root, NodeFilter.SHOW_COMMENT)"), "comments removed: " + sanitize);
        assertTrue(sanitize.contains("if (!root.contains(el)) continue;"), sanitize);
    }

    @Test
    @DisplayName("the skill Test Run escapes a parameter's type: a model writes it into SKILL.yaml")
    void theTestRunEscapesTheType() {
        assertTrue(page.contains("font-weight:400\">(' + escapeHtml(pType) + ')</span></label>"),
                "a type of <img src=x onerror=…> ran with the session's token (review, 2026-11-11)");
        assertFalse(page.contains("(' + pType + ')"));
    }

    @Test
    @DisplayName("every Markdown body the chat shows goes through the one renderer")
    void everyBodyGoesThroughIt() {
        assertEquals(1, page.split("marked\\.parse\\(", -1).length - 1, "marked.parse is called in renderMarkdown only");
    }
}
