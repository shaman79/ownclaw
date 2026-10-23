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
 */
public final class VaultSecrets {

    private static final Map<String, Map<String, String>> byUser = new ConcurrentHashMap<>();

    private VaultSecrets() {}

    /** The user's secret values by name, as the vault last set them; empty when none. */
    public static Map<String, String> of(String userId) {
        return userId == null ? Map.of() : byUser.getOrDefault(userId, Map.of());
    }

    /** What the vault holds for {@code userId} now: replaces what was there. */
    public static void set(String userId, Map<String, String> values) {
        if (userId == null) return;
        if (values == null || values.isEmpty()) byUser.remove(userId);
        else byUser.put(userId, Map.copyOf(values));
    }

    /** Forget every user's values: the vault does this as it starts, before it loads them. */
    public static void clear() {
        byUser.clear();
    }

    /** {@code text} with the user's secret values replaced by their markers. */
    public static String scrub(String userId, String text) {
        return Redactor.scrubVault(text, of(userId)).text();
    }

    /**
     * {@code text} with every user's secret values replaced: for the log, where a line belongs to
     * no one in particular.
     */
    public static String scrubAll(String text) {
        if (text == null || text.isEmpty()) return text;
        for (Map<String, String> values : byUser.values()) {
            text = Redactor.scrubVault(text, values).text();
        }
        return text;
    }

    /**
     * {@code value} with the user's secret values replaced in every text in it: a text, and the
     * texts in maps and lists, at any depth -- what a status frame's data and a trace's steps are
     * made of. Anything else is returned as it is.
     */
    public static Object scrubDeep(String userId, Object value) {
        Map<String, String> values = of(userId);
        return values.isEmpty() ? value : deep(value, values);
    }

    private static Object deep(Object value, Map<String, String> values) {
        if (value instanceof String s) return Redactor.scrubVault(s, values).text();
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<Object, Object>();
            for (var e : map.entrySet()) out.put(deep(e.getKey(), values), deep(e.getValue(), values));
            return out;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(item -> deep(item, values)).toList();
        }
        return value;
    }

    /** {@link #scrubDeep} of a map, as a map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> scrubDeep(String userId, Map<String, Object> data) {
        return data == null ? null : (Map<String, Object>) scrubDeep(userId, (Object) data);
    }
}
