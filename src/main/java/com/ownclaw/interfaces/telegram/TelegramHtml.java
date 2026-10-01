package com.ownclaw.interfaces.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown the models and the commands write, in the HTML Telegram reads ({@code parse_mode}
 * HTML). A block fenced with backticks is {@code <pre>}, its text escaped and not read as
 * Markdown; a code span is {@code <code>}; {@code **bold**}, {@code __bold__} and a line that
 * starts with one to six {@code #} and a space are {@code <b>}; {@code [text](address)} with an
 * http or https address is a link. Every {@code &}, {@code <}, {@code >} and {@code "} is
 * escaped, and nothing else is markup: a single {@code *} or {@code _} stays as it is, so
 * {@code smtp_send_email} and {@code 2*3*4} arrive as written, and a pair of markers with a
 * letter or digit outside it is not bold, so {@code x**2 + y**2} and {@code skill__name__v2} do
 * too.
 */
final class TelegramHtml {

    private TelegramHtml() {
    }

    /** A fence: three or more backticks, then an info string -- the language -- with none in it. */
    private static final Pattern FENCE = Pattern.compile("[ \\t]*(`{3,})([^`]*)");

    /** A heading: one to six {@code #}, a space, and its text. */
    private static final Pattern HEADING = Pattern.compile("#{1,6}[ \\t]+(.+)");

    /** A code span: a run of backticks, its text, and a run of as many. */
    private static final Pattern CODE = Pattern.compile("(?<!`)(`+)(?!`)(.+?)(?<!`)\\1(?!`)");

    /**
     * A link to an http or https address, whose parentheses -- a Wikipedia address has them --
     * are taken one level deep. A held piece ({@link #HELD}) is never part of an address.
     */
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\[\\]]+)]\\((https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)\\)");

    /**
     * Bold: two markers, not three or more, with no letter, digit or underscore just outside
     * them and no space just inside them.
     */
    private static final Pattern BOLD = Pattern.compile(
            "(?<![\\p{L}\\p{N}_*])(\\*\\*|__)(?![\\s*_])(.+?)(?<![\\s*_])\\1(?![\\p{L}\\p{N}_*])");

    /**
     * A rendered piece of a line, held while the rest of the line is read so that nothing in it
     * is read again: escaped text has no {@code <} of its own.
     */
    private static final Pattern HELD = Pattern.compile("<(\\d+)>");

    /**
     * The parts of one text, each in HTML that stands on its own: a code block that a part's end
     * cuts is closed there and opened again at the next part's start, so its text is code in
     * both. The text the HTML holds -- what Telegram counts against its limit -- is never longer
     * than the part: markers are taken out and nothing is put in.
     */
    static List<String> render(List<String> parts) {
        var html = new ArrayList<String>(parts.size());
        int fence = 0;   // the backticks that opened the code block the text is in; 0 outside one
        for (String part : parts) {
            var out = new StringBuilder(fence > 0 ? "<pre>" : "");
            int at = 0;
            while (at < part.length()) {
                int lineBreak = part.indexOf('\n', at);
                int end = lineBreak < 0 ? part.length() : lineBreak;
                String line = part.substring(at, end);
                String eol = lineBreak < 0 ? "" : "\n";
                at = end + eol.length();
                Matcher f = FENCE.matcher(line);
                if (fence == 0 && f.matches()) {
                    fence = f.group(1).length();
                    out.append("<pre>");
                } else if (fence > 0 && f.matches() && f.group(1).length() >= fence && f.group(2).isBlank()) {
                    fence = 0;
                    closeBlock(out);
                    out.append(eol);
                } else if (fence > 0) {
                    out.append(escape(line)).append(eol);
                } else {
                    Matcher h = HEADING.matcher(line);
                    out.append(h.matches() ? "<b>" + inline(h.group(1)) + "</b>" : inline(line)).append(eol);
                }
            }
            if (fence > 0) closeBlock(out);
            html.add(out.toString());
        }
        return html;
    }

    /** Close a code block: the line break before its closing fence ends the block, not a line in it. */
    private static void closeBlock(StringBuilder out) {
        if (out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
        out.append("</pre>");
    }

    /** A line outside a code block: its code spans, links and bold rendered, the rest escaped. */
    private static String inline(String line) {
        var held = new ArrayList<String>();
        String s = escape(line);
        s = replace(CODE, s, m -> hold(held, "<code>" + m.group(2) + "</code>"));
        s = replace(LINK, s, m -> hold(held,
                "<a href=\"" + m.group(2) + "\">" + restore(bold(m.group(1)), held) + "</a>"));
        return restore(bold(s), held);
    }

    private static String bold(String s) {
        return replace(BOLD, s, m -> "<b>" + m.group(2) + "</b>");
    }

    private static String hold(List<String> held, String html) {
        held.add(html);
        return "<" + (held.size() - 1) + ">";
    }

    private static String restore(String s, List<String> held) {
        return replace(HELD, s, m -> held.get(Integer.parseInt(m.group(1))));
    }

    /** Every match replaced by what {@code to} makes of it, taken as it is. */
    private static String replace(Pattern p, String s, Function<MatchResult, String> to) {
        return p.matcher(s).replaceAll(m -> Matcher.quoteReplacement(to.apply(m)));
    }

    /** Text as Telegram's HTML holds it: the four characters it reads as markup, escaped. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
