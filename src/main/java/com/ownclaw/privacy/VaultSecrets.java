package com.ownclaw.privacy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decrypted values of each user's secret credentials -- those whose name
 * {@link Redactor#isSecretName} calls a secret -- as the vault holds them now: what every place
 * that shows, keeps or logs a text removes from it, with {@link Redactor#scrubVault}.
 * <p>
 * Held here and not only in a running task's context, because text leaves by more doors than a
 * task's answer: the ops and debug traces, the activity frames on the page, the long-running
 * task rows, a test run from the Skills page, and the log, which logback writes without any
 * bean to ask. The vault ({@link com.ownclaw.users.CredentialVault}) is the only writer: when it
 * starts, and whenever a credential is stored or deleted -- so a value stored while a task runs
 * is removed from that task's text from then on, too.
 * <p>
 * A value is never dropped while the service runs: one replaced or deleted stays, under its
 * name with {@code ~prev} added. A skill started before a password was changed can still print
 * the old one, and a task's earlier results, read again for every model call, still hold it.
 */
public final class VaultSecrets {

    private static final Map<String, Map<String, String>> byUser = new ConcurrentHashMap<>();

    /** Every user's values in one map, for {@link #scrubAll}: rebuilt on each change. */
    private static volatile Map<String, String> everyone = Map.of();

    private VaultSecrets() {}

    /** The user's secret values by name, as the vault last set them; empty when none. */
    public static Map<String, String> of(String userId) {
        return userId == null ? Map.of() : byUser.getOrDefault(userId, Map.of());
    }

    /**
     * What the vault holds for {@code userId} now, beside every value it held before that is no
     * longer among them ({@link #withRetired}).
     */
    public static synchronized void set(String userId, Map<String, String> values) {
        if (userId == null) return;
        Map<String, String> held = withRetired(values == null ? Map.of() : values, of(userId));
        if (held.isEmpty()) byUser.remove(userId);
        else byUser.put(userId, Map.copyOf(held));
        everyone = merged();
    }

    /**
     * {@code now}, and each value of {@code before} that is not among them, under its name -- with
     * {@code ~prev} added while the name is taken. A changed password's old value is no less a
     * secret, and a deleted one may still be in use elsewhere.
     */
    public static Map<String, String> withRetired(Map<String, String> now, Map<String, String> before) {
        var out = new LinkedHashMap<String, String>(now);
        for (var e : before.entrySet()) {
            if (out.containsValue(e.getValue())) continue;
            String name = e.getKey();
            while (out.containsKey(name)) name += "~prev";
            out.put(name, e.getValue());
        }
        return out;
    }

    /** Every user's values under names no two share, so one scrub takes the longest first. */
    private static Map<String, String> merged() {
        var all = new LinkedHashMap<String, String>();
        for (Map<String, String> values : byUser.values()) {
            for (var e : values.entrySet()) {
                if (all.containsValue(e.getValue())) continue;
                String name = e.getKey();
                for (int n = 2; all.containsKey(name); n++) name = e.getKey() + "~" + n;
                all.put(name, e.getValue());
            }
        }
        return Map.copyOf(all);
    }

    /** Forget every user's values: the vault does this as it starts, before it loads them. */
    public static synchronized void clear() {
        byUser.clear();
        everyone = Map.of();
    }

    /** {@code text} with the user's secret values replaced by their markers. */
    public static String scrub(String userId, String text) {
        return Redactor.scrubVault(text, of(userId)).text();
    }

    /**
     * {@code text} with every user's secret values replaced: for the log, where a line belongs to
     * no one in particular. One scrub over all of them, so a value inside another user's longer
     * one is taken after it, as within one user's ({@link Redactor#scrubVault}).
     */
    public static String scrubAll(String text) {
        return Redactor.scrubVault(text, everyone).text();
    }

    /**
     * {@code value} with the user's secret values replaced in every text in it: a text, and the
     * values in maps and the items in lists, at any depth -- what a status frame's data and a
     * trace's steps are made of. A map's keys are left as they are: they are the names the page
     * and the API read the values by. Anything else is returned as it is.
     */
    public static Object scrubDeep(String userId, Object value) {
        Map<String, String> values = of(userId);
        return values.isEmpty() ? value : deep(value, values);
    }

    /**
     * {@link #scrubDeep} with every user's values: for what the ops and debug APIs answer, which
     * reads rows and log lines of any user, and of any day -- those written before a value was
     * scrubbed at the door that wrote them among them.
     */
    public static Object scrubAllDeep(Object value) {
        Map<String, String> values = everyone;
        return values.isEmpty() ? value : deep(value, values);
    }

    private static Object deep(Object value, Map<String, String> values) {
        if (value instanceof String s) return Redactor.scrubVault(s, values).text();
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet()) out.put(e.getKey(), deep(e.getValue(), values));
            return out;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(item -> deep(item, values)).toList();
        }
        if (value instanceof Object[] items) {
            return java.util.Arrays.stream(items).map(item -> deep(item, values)).toList();
        }
        return value;
    }

    /** {@link #scrubDeep} of a map, as a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> scrubDeep(String userId, Map<String, Object> data) {
        return data == null ? null : (Map<String, Object>) scrubDeep(userId, (Object) data);
    }
}
