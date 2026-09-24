package com.ownclaw.agent;

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
 * {@code {{$1.body_text}}} unprompted.
 *
 * @param handle 1-based position in whichever list the reference is resolved against — the
 *               delegation's own results for the local model, the task's for the cloud
 * @param field  the JSON field, or null for the whole output
 */
public record ArtifactRef(int handle, String field) {

    /** Beyond this many digits a handle is not a handle, and Integer.parseInt would overflow. */
    private static final int MAX_HANDLE_DIGITS = 9;

    /**
     * Anything a model plausibly MEANT as a reference, parsed or not: an opening pair of braces
     * followed by a digit (optionally a dollar sign first), anywhere in the value; or the old
     * {@code $1.field} form, which a stored lesson or an old habit can still produce. The old
     * form only with a field that starts with a letter, because {@code $5.50} is a price.
     * <p>
     * Deliberately looser than {@link #parse}: everything this matches must either be
     * substituted or refused, so a near miss — {@code "{{1}}"} inside a sentence, in quotes, in
     * a nested list, or {@code {{ 1 .body}}} — is stopped instead of being sent as literal text.
     */
    // \p{Nd}, not \d: Java's \d is ASCII only, so "{{١}}" -- an Arabic-Indic one -- was not
    // recognised as an attempt at all and went out as literal text. parse() still accepts only
    // ASCII, so an attempt like that is refused rather than resolved.
    private static final Pattern LOOKS_LIKE = Pattern.compile(
            "\\{\\{\\s*\\$?\\s*\\p{Nd}|^\\s*\\$\\p{Nd}{1,9}\\.[\\p{L}_]");

    /**
     * A reference inside a longer text, for taking one out of a place where it cannot work —
     * a delegation's goal names results the delegation cannot see.
     */
    static final Pattern TOKEN = Pattern.compile(
            "\\{\\{\\s*\\$?\\s*\\p{Nd}[^{}]*\\}\\}|\\$\\p{Nd}{1,9}\\.[\\p{L}_][\\p{L}\\p{N}_.…-]*");

    /**
     * The reference that IS the whole value, or null.
     * <p>
     * Whole value only. Substituting inside a sentence is how a summary silently becomes a
     * quotation; the resolver refuses a reference embedded in text instead (see
     * {@link #looksLikeReference}).
     */
    public static ArtifactRef parse(String value) {
        if (value == null) return null;
        String s = trim(value);
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

    /** Whether a model plausibly meant this as a reference; see {@link #LOOKS_LIKE}. */
    public static boolean looksLikeReference(String value) {
        return value != null && LOOKS_LIKE.matcher(value).find();
    }

    /** How the n-th result is named to whoever is reading. */
    public static String handle(int n) {
        return "{{" + n + "}}";
    }

    /** The reference exactly as the model has to write it. */
    @Override
    public String toString() {
        return field == null ? handle(handle) : "{{" + handle + "." + field + "}}";
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
