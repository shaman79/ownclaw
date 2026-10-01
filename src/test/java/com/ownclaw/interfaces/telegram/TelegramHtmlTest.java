package com.ownclaw.interfaces.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTML Telegram is sent for the Markdown the models write: code, bold, headings and links
 * render, every character Telegram reads as markup is escaped, and nothing else is a marker.
 */
class TelegramHtmlTest {

    static String html(String markdown) {
        return TelegramHtml.render(List.of(markdown)).getFirst();
    }

    /** The text Telegram reads out of this HTML, and counts against its limit: no tags, entities read. */
    static String textOf(String html) {
        return html.replaceAll("<[^>]*>", "").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&");
    }

    @Test
    @DisplayName("an identifier's underscores and arithmetic's asterisks are not markers, and there is no italic")
    void identifiersAndArithmeticSurvive() {
        String text = "Done: result 1 (smtp_send_email) mailed report_2026_09.csv to Home_Net_5G; "
                + "2*3*4 = 24, x**2 + y**2 = 2**5 and skill__name__v2 too; *one* and _one_ stay as they are.";
        assertEquals(text, html(text));
        // Mutation: drop the letter-or-digit lookarounds -> x<b>2 + y</b>2, skill<b>name</b>v2.
    }

    @Test
    @DisplayName("headings, bold, code spans, code blocks and links render")
    void markupRenders() {
        assertEquals("<b>Daily digest</b>\n"
                        + "<b>3 new mails</b> and <b>1 reminder</b> from <code>imap_fetch</code>; "
                        + "<a href=\"https://example.org/r?a=1&amp;b=&quot;2&quot;\">the report</a>.\n"
                        + "<pre>for m in mails:\n    print(m)</pre>\n"
                        + "<b>Next</b>\n"
                        + "<b><code>smtp_send_email</code></b> ran; see <a href=\"https://example.org/wiki/Mail_(protocol)\">"
                        + "<b>the</b> page</a>.",
                html("## Daily digest\n"
                        + "**3 new mails** and __1 reminder__ from `imap_fetch`; "
                        + "[the report](https://example.org/r?a=1&b=\"2\").\n"
                        + "```python\nfor m in mails:\n    print(m)\n```\n"
                        + "###### Next\n"
                        + "**`smtp_send_email`** ran; see [**the** page](https://example.org/wiki/Mail_(protocol))."));
        // Not markup: no space after #, seven of them, a pair of three markers, an address that is not the web's.
        String plain = "#hashtag ####### seven ***x*** [a](javascript:alert(1)) [b](ftp://example.org)";
        assertEquals(plain, html(plain));
    }

    @Test
    @DisplayName("&, <, > and \" are escaped in text, code spans and code blocks alike, and nothing in code is markup")
    void htmlIsEscaped() {
        assertEquals("if a &lt; b &amp;&amp; c &gt; d: &quot;yes&quot; <code>&lt;br&gt; &amp;amp; **x**</code>",
                html("if a < b && c > d: \"yes\" `<br> &amp; **x**`"));
        assertEquals("<pre>&lt;html&gt; **not bold** `not code` [no](https://example.org) &amp;</pre>",
                html("```\n<html> **not bold** `not code` [no](https://example.org) &\n```"));
        assertEquals("<b>a &lt;b&gt; &amp; c</b>", html("# a <b> & c"));
    }

    @Test
    @DisplayName("a code block cut between two parts is code in both, and the text after it is read again")
    void aCodeBlockAcrossParts() {
        var parts = List.of("Script:\n```python\nprint(\"**a**\")\n", "x = 1 < 2\n```\nThat is **all**.");
        assertEquals(List.of("Script:\n<pre>print(&quot;**a**&quot;)</pre>",
                        "<pre>x = 1 &lt; 2</pre>\nThat is <b>all</b>."),
                TelegramHtml.render(parts));
        // A block the text never closes ends with it.
        assertEquals("<pre>still **code**</pre>", html("```\nstill **code**"));
        // Mutation: forget the open fence at a part's end -> the next part's code is read as Markdown.
    }

    @Test
    @DisplayName("a part's HTML holds no more text than the part, however much longer the markup makes it")
    void theTextNeverGrows() {
        String text = ("## Heading & more\n**bold <b>** and __x__ `a&b` [l](https://example.org/?a=1&b=2)\n"
                + "```\n<&>\"\n```\n").repeat(200);
        var parts = TelegramBotService.telegramParts(text, TelegramBotService.TELEGRAM_MAX_CHARS);
        var html = TelegramHtml.render(parts);
        assertTrue(parts.size() >= 3, "parts: " + parts.size());
        for (int i = 0; i < parts.size(); i++) {
            assertTrue(html.get(i).length() > TelegramBotService.TELEGRAM_MAX_CHARS || i == parts.size() - 1,
                    "the HTML of a full part is longer than a message may be: " + html.get(i).length());
            assertTrue(textOf(html.get(i)).length() <= parts.get(i).length(), "part " + i);
        }
    }
}
