package com.ownclaw.agent;

/**
 * What counts as a reference to an earlier result — decided in exactly one place.
 * <p>
 * Three pieces of code used to answer this question and all three answered differently.
 * {@code LocalExecutor.resolveRef} took everything after the first dot and accepted it unless it
 * was blank; {@code Artifact.REF} required no whitespace; {@code LocalExecutor.UNRESOLVED}
 * required no whitespace too but was checked after substitution. The gaps between them were not
 * cosmetic:
 * <ul>
 *   <li>{@code $1.body text} — a key with a space, which a generated skill produces as readily
 *       as one with a hyphen — was SUBSTITUTED by the resolver, so the private bytes moved into
 *       the tool's arguments, and then labelled PUBLIC by the pattern that decides the label. The
 *       content travelled and the label said it was free to travel.</li>
 *   <li>{@code $+1} and {@code $١} likewise: {@code Integer.parseInt} accepts a leading plus and
 *       Unicode decimal digits, {@code \d} in Java does not, so the whole output moved under a
 *       PUBLIC label with no field name involved at all.</li>
 *   <li>{@code $1.body_text (string, 48 chars)} — which is what the cloud writes when it copies a
 *       field name out of a descriptor that annotates them — matched no pattern, so it was
 *       neither substituted nor refused and went out as the literal body of an email.</li>
 * </ul>
 * One grammar, used by the resolver, by the refusal and by the label, cannot disagree with
 * itself. Everything else about references is built on top of this.
 *
 * @param handle the artifact number, {@code $1}-based
 * @param field  the field to take out of a JSON result, or null for the whole output
 */
public record ArtifactRef(int handle, String field) {

    /** Beyond this many digits a handle is not a handle; it is also where parseInt overflows. */
    private static final int MAX_HANDLE_DIGITS = 9;

    /**
     * The reference in {@code value}, or null when it is not one.
     * <p>
     * A reference is the WHOLE value — never a fragment, because substituting inside prose is
     * how a summary silently becomes a quotation. The rules, and why each is here:
     * <ul>
     *   <li>ASCII digits only, at most {@value #MAX_HANDLE_DIGITS} of them. An unbounded digit run
     *       overflowed {@code Integer.parseInt} and threw out of the guard — after the tool had
     *       run, so the email went and the task died reporting an internal error.</li>
     *   <li>A field may contain anything except a leading digit. That one rule is what separates
     *       {@code $1.body text} (a reference) from {@code $5.50} and {@code $1.234,56} and
     *       {@code $5.00/kg} (prices a model types as a whole argument value). No JSON key worth
     *       referencing starts with a digit; every decimal amount does.</li>
     * </ul>
     */
    public static ArtifactRef parse(String value) {
        if (value == null) return null;
        String token = value.strip();
        if (token.length() < 2 || token.charAt(0) != '$') return null;

        String body = token.substring(1);
        String field = null;
        int dot = body.indexOf('.');
        if (dot >= 0) {
            field = body.substring(dot + 1);
            body = body.substring(0, dot);
            // "$.foo" has no handle, "$1." has no field.
            if (field.isBlank() || body.isEmpty()) return null;
            // A price, not a field: "$5.50", "$1.234,56", "$1.99!", "$5.50 Polévka".
            if (Character.isDigit(field.charAt(0))) return null;
        }

        if (body.isEmpty() || body.length() > MAX_HANDLE_DIGITS) return null;
        for (int i = 0; i < body.length(); i++) {
            // Not Character.isDigit: that accepts Arabic-Indic and fullwidth digits, which
            // Integer.parseInt also accepts, so "$١" resolved while the label's ASCII-only
            // pattern called the result PUBLIC.
            if (body.charAt(i) < '0' || body.charAt(i) > '9') return null;
        }
        int handle = Integer.parseInt(body);
        if (handle < 1) return null;
        return new ArtifactRef(handle, field);
    }

    /** Whether this names a field rather than a whole result. */
    public boolean hasField() {
        return field != null;
    }

    /** The reference as the model must type it back. */
    @Override
    public String toString() {
        return field == null ? "$" + handle : "$" + handle + "." + field;
    }
}
