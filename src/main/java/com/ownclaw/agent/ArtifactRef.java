package com.ownclaw.agent;

import com.ownclaw.privacy.PrivateIndex;

import java.util.regex.Pattern;

/**
 * A reference to an earlier result: {@code {{1}}} for the whole output, {@code {{1.body_text}}}
 * for one field of a JSON result. The one place that decides what a reference is.
 * <p>
 * It used to be {@code $1}, and a dollar sign followed by digits is also how every price is
 * written. Four review rounds went into rules for telling the two apart — a price has a digit
 * after the dot, a bare handle out of range is money, a field may not start with a digit — and
 * every rule broke something real: {@code $1.2fa_code} and {@code $1.2026-09-24} became prices
 * and went out as literal text, {@code $1.99} became a reference and blinded the rest of a run.
 * A marker nobody uses for anything else needs none of those rules. Template braces are also the
 * syntax a model reaches for on its own; a reviewer's probes found the model writing
 * {@code {{$1.body_text}}} unprompted, which is accepted. The old {@code $1} form is not a
 * reference at all any more, and nothing teaches it.
 *
 * @param handle 1-based position in whichever list the reference is resolved against — the
 *               delegation's own results for the local model, the task's for the cloud
 * @param field  the JSON field, or null for the whole output: its name, or {@code #k} for the
 *               k-th field in key order ({@link #position})
 */
public record ArtifactRef(int handle, String field) {

    /** Beyond this many digits a handle is not a handle, and Integer.parseInt would overflow. */
    private static final int MAX_HANDLE_DIGITS = 9;

    /** Blank, including the invisible kinds {@link #trim} removes. */
    private static final String SP = "[\\s\\u00A0\\u200B\\u200C\\u200D\\u2060\\uFEFF]*";

    /**
     * Something written as a reference, well-formed or not: braces around digits of any script,
     * optionally a field after a dot or a colon, optionally a field hung outside the braces.
     */
    private static final Pattern NEAR = Pattern.compile(
            "\\{\\{" + SP + "\\$?" + SP + "\\p{Nd}+" + SP + "([.:][^{}]*)?\\}\\}"
                    + "(\\.[\\p{L}_][\\p{L}\\p{N}_]*)?");

    /**
     * The old form, as a whole value: {@code $1.body_text}. Stored lessons and replayed rows from
     * before the change can still hold it. It is a reference only if it would resolve -- see
     * {@code References.resolve} -- because "$1.jpg" is also a regex replacement.
     */
    static final Pattern LEGACY = Pattern.compile("^\\$(\\d{1,9})\\.([\\p{L}_][\\p{L}\\p{N}_]*)$");

    /** A letter or a digit: what makes the rest of a value text. */
    private static final Pattern WORDY = Pattern.compile("[\\p{L}\\p{N}]");

    /**
     * A well-formed reference inside prose, for taking one out of a place where it cannot work:
     * a delegation's goal naming results the delegation cannot see.
     */
    static final Pattern TOKEN = Pattern.compile(
            "\\{\\{" + SP + "\\$?" + SP + "\\d{1,9}" + SP + "(\\.[^{}]*)?\\}\\}");

    /**
     * The reference that IS the whole value, or null.
     * <p>
     * Whole value only. Substituting inside a sentence is how a summary silently becomes a
     * quotation; the resolver refuses a reference embedded in text instead (see
     * {@link #looksLikeReference}).
     */
    public static ArtifactRef parse(String value) {
        if (value == null) return null;
        // One layer of quotes or backticks is forgiven: a model writing "{{1}}" or `{{1}}` meant
        // the reference, and no real argument is exactly a quoted reference.
        String s = unquote(trim(value));
        if (s.length() < 5 || !s.startsWith("{{") || !s.endsWith("}}")) return null;
        String inner = trim(s.substring(2, s.length() - 2));
        if (inner.startsWith("$")) inner = trim(inner.substring(1));

        String body = inner, field = null;
        int dot = inner.indexOf('.');
        if (dot >= 0) {
            body = trim(inner.substring(0, dot));
            field = trim(inner.substring(dot + 1));
            if (field.isEmpty() || field.contains("{{") || field.contains("}}")) return null;
        }
        if (body.isEmpty() || body.length() > MAX_HANDLE_DIGITS) return null;
        for (int i = 0; i < body.length(); i++) {
            // ASCII only. Character.isDigit accepts Arabic-Indic and fullwidth digits, which
            // Integer.parseInt also accepts, and those once resolved under a PUBLIC label.
            if (body.charAt(i) < '0' || body.charAt(i) > '9') return null;
        }
        int handle = Integer.parseInt(body);
        return handle < 1 ? null : new ArtifactRef(handle, field);
    }

    /** Whether a model plausibly meant this whole value as a reference. */
    public static boolean looksLikeReference(String value) {
        if (value == null) return false;
        String s = trim(value);
        if (!NEAR.matcher(s).find()) return false;
        // A value made of nothing but references -- plus braces, punctuation and space -- is an
        // attempt at one: {{{1}}}, {{1}}., {{1}}{{2}}, {{1}}.body_text, **{{1}}**. Anything with
        // a word of its own around the braces is text: an f-string, a LaTeX fraction, a C array,
        // a WhatsApp template, "Menu: {{1.body_text}}". Refusing the second kind was round 6's
        // finding; letting the first through as literal text was round 7's.
        return !WORDY.matcher(NEAR.matcher(s).replaceAll("")).find();
    }

    /** How the n-th result is named to whoever is reading. */
    public static String handle(int n) {
        return "{{" + n + "}}";
    }

    /**
     * k for a field written {@code #k} -- {@code {{3.#7}}} is the seventh field of result 3, in
     * key order -- or null for a field named by its name. Always a position: a key that is itself
     * spelled {@code #7} is referenced by its own position, which {@link #toField} offers.
     */
    public Integer position() {
        if (field == null || field.length() < 2 || field.length() > MAX_HANDLE_DIGITS + 1
                || field.charAt(0) != '#') {
            return null;
        }
        for (int i = 1; i < field.length(); i++) {
            if (field.charAt(i) < '0' || field.charAt(i) > '9') return null;
        }
        int k = Integer.parseInt(field.substring(1));
        return k < 1 ? null : k;
    }

    /**
     * The reference to the field {@code name}, the {@code position}-th in key order of result
     * {@code handle}: by its name when the name is shorter than a canary window and this grammar
     * reads it back as that same name; otherwise by its position, {@code {{handle.#position}}}.
     * <p>
     * A name of a whole window or more is withheld because a key can be data -- an address, a
     * sentence a skill keyed its output by -- and a window of a PRIVATE result is exactly what
     * the canary refuses to send, so a descriptor carrying one would leak it and then be refused.
     * Its length is measured as the canary measures it, after {@link PrivateIndex#normalise},
     * which can lengthen a name: a dotted capital I lowercases to two characters, a ligature
     * unfolds into two letters. A shorter name can still complete a window with what the
     * descriptor prints around it; the descriptor finds those itself ({@code Artifact#withheld}).
     * A name the grammar would read differently (padded with spaces, holding braces, or spelled
     * like a position) could never resolve. The position always does, and says nothing.
     */
    public static ArtifactRef toField(int handle, String name, int position) {
        var byName = new ArtifactRef(handle, name);
        return PrivateIndex.normalise(name).length() < PrivateIndex.WINDOW
                && byName.equals(parse(byName.toString())) && byName.position() == null
                ? byName : atPosition(handle, position);
    }

    /** The reference to the {@code position}-th field of result {@code handle}, in key order. */
    public static ArtifactRef atPosition(int handle, int position) {
        return new ArtifactRef(handle, "#" + position);
    }

    /** The reference exactly as the model has to write it. */
    @Override
    public String toString() {
        return field == null ? handle(handle) : "{{" + handle + "." + field + "}}";
    }

    /** Remove one matching pair of surrounding quotes or backticks. */
    static String unquote(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if (a == b && (a == '"' || a == '\'' || a == '`')) return trim(s.substring(1, s.length() - 1));
        }
        return s;
    }

    /**
     * Strip whitespace including the invisible kinds. String.strip leaves a zero-width space,
     * a no-break space and a byte-order mark in place, and a reviewer's probes showed each of
     * them turning a correct reference into literal text.
     */
    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isBlank(s.charAt(a))) a++;
        while (b > a && isBlank(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿';
    }
}
