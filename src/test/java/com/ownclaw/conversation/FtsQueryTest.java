package com.ownclaw.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turning typed text into an FTS5 MATCH expression.
 * <p>
 * The raw string used to go straight into MATCH, which is an expression language rather than a
 * search box: a bare {@code .}, {@code '} or {@code -} is a syntax error, and SQLite raises
 * rather than returning nothing. The sidebar searches on every keystroke, so ordinary input
 * produced a 500 partway through a word — including this system's own vocabulary, such as a skill
 * named {@code web_search_bikes} or the host {@code claw.avercode.com}.
 */
class FtsQueryTest {

    @Test
    @DisplayName("a skill name with underscores survives intact")
    void underscoresSurvive() {
        assertEquals("\"web_search_bikes\"", ConversationService.ftsQuery("web_search_bikes"),
                "underscores are part of an FTS5 token and must not be stripped");
    }

    @Test
    @DisplayName("a dotted hostname becomes literal terms instead of a syntax error")
    void dottedInputIsSafe() {
        String q = ConversationService.ftsQuery("claw.avercode.com");
        assertTrue(q.startsWith("\""), "must be quoted, not passed through as syntax: " + q);
        assertFalse(q.contains("."), "the dot is not tokenised by FTS5: " + q);
        assertTrue(q.contains("claw") && q.contains("avercode") && q.contains("com"), q);
    }

    @Test
    @DisplayName("apostrophes and hyphens no longer raise")
    void apostropheAndHyphen() {
        assertEquals("\"don't\"", ConversationService.ftsQuery("don't"));
        assertEquals("\"web-search\"", ConversationService.ftsQuery("web-search"),
                "a leading hyphen would otherwise read as the NOT operator");
    }

    @Test
    @DisplayName("FTS5 operators typed by a user are searched for, not executed")
    void operatorsAreLiteral() {
        String q = ConversationService.ftsQuery("NOT a");
        assertTrue(q.contains("\"NOT\""), "the word the user typed must be a term: " + q);
    }

    @Test
    @DisplayName("several words narrow the search rather than widening it")
    void multipleTermsAreAnded() {
        assertEquals("\"daily\" AND \"digest\"", ConversationService.ftsQuery("daily digest"));
    }

    @Test
    @DisplayName("an embedded quote cannot break out of the literal")
    void embeddedQuoteIsDoubled() {
        String q = ConversationService.ftsQuery("say \"hi\"");
        assertEquals(0, q.chars().filter(c -> c == '"').count() % 2,
                "unbalanced quotes are exactly the syntax error being fixed: " + q);
    }

    @Test
    @DisplayName("empty and punctuation-only input match nothing instead of raising")
    void emptyIsSafe() {
        for (String raw : new String[]{null, "", "   ", "...", "!!!"}) {
            assertEquals("\"\"", ConversationService.ftsQuery(raw),
                    "input <" + raw + "> should match nothing, not throw");
        }
    }
}
