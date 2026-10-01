package com.ownclaw.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The privacy filter: what a cloud model may read of a text, and the way back. The only place
 * these rules live; the cloud gateway is the only caller that sends or receives through it.
 * <p>
 * Out, every part of a cloud request goes through {@link #filter}:
 * <ul>
 *   <li>Secrets are REMOVED and never come back: every vault value, as {@code «vault:KEY»}
 *       ({@link #scrubVault}), and secrets found by code, as {@link #SECRET_REMOVED} -- the value
 *       of a key or option named like a password, a key, a token or a secret (UCI, INI and env,
 *       JSON, YAML, query strings, command-line flags), a PEM private key, a password hash, a JWT,
 *       a token in a well-known format.</li>
 *   <li>Identifiers are REPLACED by placeholders such as {@code <email_3>} or {@code <ssid_1>}:
 *       e-mail addresses, international phone numbers, IBANs, card numbers, MAC addresses,
 *       public IP addresses, SSIDs and the hostnames of clients. The same value always gets the
 *       same placeholder for a user -- a table kept here and in {@code privacy_placeholders},
 *       never sent anywhere -- so a request is the same bytes every time it is the same text,
 *       and the provider's prompt cache keeps working. An SSID or a hostname is found by the
 *       name it is written under, and once found it is replaced wherever it stands as a word --
 *       in the owner's answer that names it, in the next task's history -- since nothing else
 *       marks it there.</li>
 * </ul>
 * Everything else goes as it is. In, {@link #restore} puts the values back wherever the reply
 * names a placeholder the user's table knows; one it does not know stays as written.
 * <p>
 * The detectors are plain code, each one pass over the text, with no model involved: a guard
 * that a model decides is not a guard. They find what has a shape or a name. A secret with
 * neither -- a password in prose, an unlabelled key -- is not found unless it is in the vault, and
 * a name in free text is not an identifier here.
 */
@Component
public final class Redactor {

    private static final Logger log = LoggerFactory.getLogger(Redactor.class);

    /** What a secret found by code becomes: removed, and nothing to put back. */
    public static final String SECRET_REMOVED = "«secret removed»";

    /** A vault value shorter than this is not removed: too short to be a secret, long enough to be a word. */
    public static final int MIN_SECRET_LENGTH = 8;

    /**
     * An SSID or hostname shorter than this is replaced only where it is found under its name,
     * not wherever it stands: "nas" or "ap" is a word more often than it is that device.
     */
    static final int MIN_KNOWN_LENGTH = 4;

    /** The kinds of identifier, each replaced by {@code <kind_n>}. */
    public enum Kind {
        EMAIL, PHONE, IBAN, CARD, MAC, IP, SSID, HOST;

        String tag() { return name().toLowerCase(Locale.ROOT); }
    }

    /** How many secrets were removed and identifiers replaced, over one text or a whole request. */
    public static final class Tally {
        private int secrets;
        private int identifiers;

        public int secretsRemoved() { return secrets; }
        public int identifiersReplaced() { return identifiers; }
        public boolean any() { return secrets + identifiers > 0; }
    }

    /** A vault scrub's result: the text, and how many values were removed. */
    public record Scrubbed(String text, int count) {}

    /** One span the detectors found: a secret ({@code kind} null) or an identifier. */
    record Finding(int start, int end, Kind kind) {}

    /** Null: the table lives in memory only. */
    private final JdbcTemplate jdbc;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    /** @param jdbc where the placeholder table is kept; null keeps it in memory only */
    public Redactor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── out ──

    /**
     * {@code text} as a cloud model may read it: the vault values removed, then every secret the
     * detectors find removed and every identifier replaced by the user's placeholder for it.
     * Counted into {@code tally}.
     */
    public String filter(String userId, String text, Map<String, String> vault, Tally tally) {
        if (text == null || text.isEmpty()) return text;
        Scrubbed scrubbed = scrubVault(text, vault);
        tally.secrets += scrubbed.count();
        String s = scrubbed.text();
        Table table = table(userId);
        List<Finding> found = find(s, table.named());
        if (found.isEmpty()) return s;
        var sb = new StringBuilder(s.length());
        int from = 0;
        for (Finding f : found) {
            sb.append(s, from, f.start());
            if (f.kind() == null) {
                sb.append(SECRET_REMOVED);
                tally.secrets++;
            } else {
                sb.append(table.placeholder(f.kind(), s.substring(f.start(), f.end())));
                tally.identifiers++;
            }
            from = f.end();
        }
        return sb.append(s, from, s.length()).toString();
    }

    /**
     * {@link #filter} over every string in a JSON-shaped value -- a tool's input schema, a map of
     * arguments -- keys and all other values left as they are.
     */
    public Object filterTree(String userId, Object value, Map<String, String> vault, Tally tally) {
        if (value instanceof String s) return filter(userId, s, vault, tally);
        if (value instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<Object, Object>();
            for (var e : m.entrySet()) out.put(e.getKey(), filterTree(userId, e.getValue(), vault, tally));
            return out;
        }
        if (value instanceof List<?> l) {
            var out = new ArrayList<Object>(l.size());
            for (Object o : l) out.add(filterTree(userId, o, vault, tally));
            return out;
        }
        return value;
    }

    /**
     * What {@link #filter} would remove and replace in {@code text}, counted without adding to the
     * placeholder table: what a step's progress message says of its result.
     */
    public Tally count(String userId, String text, Map<String, String> vault) {
        var tally = new Tally();
        if (text == null || text.isEmpty()) return tally;
        Scrubbed scrubbed = scrubVault(text, vault);
        tally.secrets += scrubbed.count();
        for (Finding f : find(scrubbed.text(), table(userId).named())) {
            if (f.kind() == null) tally.secrets++;
            else tally.identifiers++;
        }
        return tally;
    }

    /**
     * Replace every occurrence of a vault value with {@code «vault:KEY»}. Deterministic, so a
     * prompt's cached prefix stays byte-stable across steps. Values shorter than
     * {@link #MIN_SECRET_LENGTH} are left. Also what everything stored for display is scrubbed
     * with: the owner's chat, the task record, the usage rows.
     */
    public static Scrubbed scrubVault(String text, Map<String, String> vault) {
        if (text == null || text.isEmpty() || vault == null || vault.isEmpty()) {
            return new Scrubbed(text, 0);
        }
        String out = text;
        int count = 0;
        for (var e : vault.entrySet()) {
            String value = e.getValue();
            if (value == null || value.length() < MIN_SECRET_LENGTH) continue;
            String marker = "«vault:" + e.getKey() + "»";
            // A key whose name embeds the value -- or a value that is literally "vault:PASS" --
            // would leave the secret inside its own replacement. Fall back to a marker that
            // cannot contain it.
            if (marker.contains(value)) marker = "«vault:redacted»";
            // Scan forward from after each replacement. Restarting from zero never terminated
            // when the value was a substring of its own marker -- a vault value of "vault:pass"
            // rewrote itself for ever and hung the call, holding the task's only worker thread.
            var sb = new StringBuilder();
            int from = 0, at;
            while ((at = out.indexOf(value, from)) >= 0) {
                sb.append(out, from, at).append(marker);
                from = at + value.length();
                count++;
            }
            if (from > 0) out = sb.append(out.substring(from)).toString();
        }
        return new Scrubbed(out, count);
    }

    // ── in ──

    /** {@code text} with every placeholder the user's table knows put back to its value. */
    public String restore(String userId, String text) {
        if (text == null || text.indexOf('<') < 0) return text;
        Table table = null;
        StringBuilder sb = null;
        int from = 0;
        int n = text.length();
        for (int i = text.indexOf('<'); i >= 0 && i < n; i = text.indexOf('<', i + 1)) {
            int j = i + 1;
            while (j < n && text.charAt(j) >= 'a' && text.charAt(j) <= 'z') j++;
            if (j == i + 1 || j >= n || text.charAt(j) != '_') continue;
            int k = j + 1;
            while (k < n && isDigit(text.charAt(k))) k++;
            if (k == j + 1 || k >= n || text.charAt(k) != '>') continue;
            if (table == null) table = table(userId);
            String value = table.value(text.substring(i, k + 1));
            if (value == null) continue;
            if (sb == null) sb = new StringBuilder(n);
            sb.append(text, from, i).append(value);
            from = k + 1;
            i = k;
        }
        return sb == null ? text : sb.append(text, from, n).toString();
    }

    /**
     * {@link #restore} over every string in a JSON-shaped value: a tool call's arguments. The same
     * object when it names no placeholder the table knows.
     */
    public Object restoreTree(String userId, Object value) {
        if (value instanceof String s) return restore(userId, s);
        boolean changed = false;
        if (value instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<Object, Object>();
            for (var e : m.entrySet()) {
                Object v = restoreTree(userId, e.getValue());
                changed |= v != e.getValue();
                out.put(e.getKey(), v);
            }
            return changed ? out : value;
        }
        if (value instanceof List<?> l) {
            var out = new ArrayList<Object>(l.size());
            for (Object o : l) {
                Object v = restoreTree(userId, o);
                changed |= v != o;
                out.add(v);
            }
            return changed ? out : value;
        }
        return value;
    }

    // ── the table ──

    private Table table(String userId) {
        String user = userId == null ? "system" : userId;
        return tables.computeIfAbsent(user, Table::new);
    }

    /** One user's placeholders, both ways, as the database holds them. */
    private final class Table {
        private final String user;
        private final Map<String, Integer> numbers = new HashMap<>();   // kind tag + NUL + value -> n
        private final Map<String, String> values = new HashMap<>();     // "<kind_n>" -> value
        private final int[] last = new int[Kind.values().length];

        Table(String user) {
            this.user = user;
            if (jdbc == null) return;
            for (var row : jdbc.queryForList(
                    "SELECT kind, n, value FROM privacy_placeholders WHERE user_id = ?", user)) {
                Kind kind;
                try {
                    kind = Kind.valueOf(String.valueOf(row.get("kind")).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    continue;
                }
                int n = ((Number) row.get("n")).intValue();
                remember(kind, n, String.valueOf(row.get("value")));
            }
        }

        private void remember(Kind kind, int n, String value) {
            numbers.put(kind.tag() + '\0' + value, n);
            values.put("<" + kind.tag() + "_" + n + ">", value);
            last[kind.ordinal()] = Math.max(last[kind.ordinal()], n);
        }

        synchronized String placeholder(Kind kind, String value) {
            Integer n = numbers.get(kind.tag() + '\0' + value);
            if (n == null) {
                n = last[kind.ordinal()] + 1;
                remember(kind, n, value);
                if (jdbc != null) {
                    try {
                        jdbc.update("INSERT INTO privacy_placeholders (user_id, kind, n, value) "
                                + "VALUES (?, ?, ?, ?)", user, kind.tag(), n, value);
                    } catch (RuntimeException e) {
                        // Replaced all the same: only its number may differ after a restart.
                        log.warn("A placeholder could not be stored: {}", e.getClass().getSimpleName());
                    }
                }
            }
            return "<" + kind.tag() + "_" + n + ">";
        }

        synchronized String value(String placeholder) {
            return values.get(placeholder);
        }

        /** The SSIDs and hostnames it holds that are long enough to be looked for anywhere. */
        synchronized Map<Kind, java.util.Set<String>> named() {
            var out = new java.util.EnumMap<Kind, java.util.Set<String>>(Kind.class);
            for (Kind kind : List.of(Kind.SSID, Kind.HOST)) out.put(kind, new java.util.HashSet<>());
            for (var e : numbers.keySet()) {
                int nul = e.indexOf('\0');
                Kind kind = Kind.valueOf(e.substring(0, nul).toUpperCase(Locale.ROOT));
                String value = e.substring(nul + 1);
                if (out.containsKey(kind) && value.length() >= MIN_KNOWN_LENGTH) out.get(kind).add(value);
            }
            return out;
        }
    }

    // ── the detectors ──

    /**
     * Every secret and identifier in {@code s}, in order, none overlapping: a secret wins an
     * overlap, then what a detector found, then a known SSID or hostname standing as a word --
     * one in {@code known} (the user's table) or one this text names.
     */
    static List<Finding> find(String s, Map<Kind, java.util.Set<String>> known) {
        var secrets = new ArrayList<Finding>();
        var ids = new ArrayList<Finding>();
        pemBlocks(s, secrets);
        passwordHashes(s, secrets);
        tokens(s, secrets);
        namedValues(s, secrets, ids);
        emails(s, ids);
        phones(s, ids);
        ibans(s, ids);
        cards(s, ids);
        macs(s, ids);
        ipv4(s, ids);
        ipv6(s, ids);
        var names = new java.util.EnumMap<Kind, java.util.Set<String>>(Kind.class);
        for (var e : known.entrySet()) names.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
        for (Finding f : ids) {
            if ((f.kind() == Kind.SSID || f.kind() == Kind.HOST) && f.end() - f.start() >= MIN_KNOWN_LENGTH) {
                names.computeIfAbsent(f.kind(), k -> new java.util.HashSet<>()).add(s.substring(f.start(), f.end()));
            }
        }
        var again = standingAsWords(s, names);
        if (secrets.isEmpty() && ids.isEmpty() && again.isEmpty()) return List.of();
        var covered = new BitSet(s.length());
        var kept = new ArrayList<Finding>();
        Comparator<Finding> order = Comparator.comparingInt(Finding::start)
                .thenComparing(Comparator.comparingInt(Finding::end).reversed());
        for (List<Finding> kind : List.of(secrets, ids, again)) {
            kind.sort(order);
            for (Finding f : kind) {
                if (f.end() <= f.start()) continue;
                int taken = covered.nextSetBit(f.start());
                if (taken >= 0 && taken < f.end()) continue;
                covered.set(f.start(), f.end());
                kept.add(f);
            }
        }
        kept.sort(Comparator.comparingInt(Finding::start));
        return kept;
    }

    /**
     * Where each of {@code names} stands in {@code s} as a word: with no letter, digit or '_'
     * just before or after it. One pass, looking up the values that start with the two
     * characters at each word's start, the longest first.
     */
    private static List<Finding> standingAsWords(String s, Map<Kind, java.util.Set<String>> names) {
        var byStart = new HashMap<Integer, List<Map.Entry<String, Kind>>>();
        for (var e : names.entrySet()) {
            for (String v : e.getValue()) {
                byStart.computeIfAbsent((v.charAt(0) << 16) | v.charAt(1), k -> new ArrayList<>())
                        .add(Map.entry(v, e.getKey()));
            }
        }
        var out = new ArrayList<Finding>();
        if (byStart.isEmpty()) return out;
        for (var list : byStart.values()) list.sort((a, b) -> b.getKey().length() - a.getKey().length());
        int n = s.length();
        for (int i = 0; i + 1 < n; i++) {
            if (i > 0 && wordChar(s.charAt(i - 1))) continue;
            var candidates = byStart.get((s.charAt(i) << 16) | s.charAt(i + 1));
            if (candidates == null) continue;
            for (var c : candidates) {
                int end = i + c.getKey().length();
                if (s.startsWith(c.getKey(), i) && (end >= n || !wordChar(s.charAt(end)))) {
                    out.add(new Finding(i, end, c.getValue()));
                    i = end - 1;
                    break;
                }
            }
        }
        return out;
    }

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // PEM private keys: from -----BEGIN ...PRIVATE KEY----- to its -----END ...-----, or to the
    // end of the text when it was cut off -- half a key is still a key.
    private static void pemBlocks(String s, List<Finding> out) {
        int from = 0, at;
        while ((at = s.indexOf("-----BEGIN ", from)) >= 0) {
            int label = at + "-----BEGIN ".length();
            int close = label;
            while (close < s.length() && s.charAt(close) != '\n' && !s.startsWith("-----", close)) close++;
            if (close >= s.length() || !s.startsWith("-----", close)
                    || !s.substring(label, close).contains("PRIVATE KEY")) {
                from = label;
                continue;
            }
            int end = s.indexOf("-----END ", close + 5);
            int stop;
            if (end < 0) {
                stop = s.length();
            } else {
                int tail = s.indexOf("-----", end + "-----END ".length());
                stop = tail < 0 ? s.length() : tail + 5;
            }
            out.add(new Finding(at, stop, null));
            from = stop;
        }
    }

    private static final Set<String> HASH_IDS =
            Set.of("1", "5", "6", "2a", "2b", "2x", "2y", "y", "argon2i", "argon2d", "argon2id");

    // crypt(3) hashes: $id$[params$]salt$hash -- $1$, $5$, $6$, bcrypt's $2a$/$2b$/$2y$, yescrypt's
    // $y$, $argon2...$. The part after the last '$' has at least 16 characters, so $1$2$3 in a shell
    // line is not one.
    private static void passwordHashes(String s, List<Finding> out) {
        int n = s.length();
        for (int i = s.indexOf('$'); i >= 0; i = s.indexOf('$', i + 1)) {
            if (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '$')) continue;
            int idEnd = i + 1;
            while (idEnd < n && idEnd - i <= 9 && Character.isLetterOrDigit(s.charAt(idEnd))) idEnd++;
            if (idEnd >= n || s.charAt(idEnd) != '$' || !HASH_IDS.contains(s.substring(i + 1, idEnd))) continue;
            int end = idEnd + 1;
            while (end < n && hashChar(s.charAt(end))) end++;
            int lastDollar = s.lastIndexOf('$', end - 1);
            if (lastDollar <= idEnd || end - lastDollar - 1 < 16) continue;
            out.add(new Finding(i, end, null));
            i = end - 1;
        }
    }

    private static boolean hashChar(char c) {
        return isAsciiAlnum(c) || c == '.' || c == '/' || c == '$' || c == '=' || c == ',' || c == '+' || c == '-';
    }

    // JWTs, and tokens in a format a provider made recognisable on purpose.
    private static void tokens(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && tokenChar(s.charAt(i - 1))) continue;
            int end = knownToken(s, i);
            if (end > i) {
                out.add(new Finding(i, end, null));
                i = end - 1;
            }
        }
    }

    /** The end of a token in a known format starting at {@code i}, or -1. */
    private static int knownToken(String s, int i) {
        char c = s.charAt(i);
        if (c == 'e' && s.startsWith("eyJ", i)) {
            int e1 = run(s, i, Redactor::tokenChar);
            if (e1 - i < 10 || e1 >= s.length() || s.charAt(e1) != '.' || !s.startsWith("eyJ", e1 + 1)) return -1;
            int e2 = run(s, e1 + 1, Redactor::tokenChar);
            if (e2 - e1 - 1 < 10 || e2 >= s.length() || s.charAt(e2) != '.') return -1;
            return run(s, e2 + 1, Redactor::tokenChar);
        }
        if (c == 's' && s.startsWith("sk-", i)) {
            int end = run(s, i, Redactor::tokenChar);
            String body = s.substring(i + 3, end);
            if (s.startsWith("sk-ant-", i)) return end - i - 7 >= 20 ? end : -1;
            return body.length() >= 20 && body.chars().anyMatch(Character::isDigit)
                    && body.chars().anyMatch(Character::isUpperCase)
                    && body.chars().anyMatch(Character::isLowerCase) ? end : -1;
        }
        if (c == 'g') {
            for (String p : List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) {
                if (s.startsWith(p, i)) {
                    int end = run(s, i + 4, Redactor::isAsciiAlnum);
                    return end - i - 4 >= 30 && boundaryAfter(s, end) ? end : -1;
                }
            }
            if (s.startsWith("github_pat_", i)) {
                int end = run(s, i + 11, ch -> isAsciiAlnum(ch) || ch == '_');
                return end - i - 11 >= 22 ? end : -1;
            }
        }
        if (c == 'x' && s.startsWith("xox", i) && i + 4 < s.length()
                && "bpas".indexOf(s.charAt(i + 3)) >= 0 && s.charAt(i + 4) == '-') {
            int end = run(s, i + 5, ch -> isAsciiAlnum(ch) || ch == '-');
            return end - i - 5 >= 10 ? end : -1;
        }
        if (c == 'A' && s.startsWith("AKIA", i)) {
            int end = run(s, i + 4, ch -> ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'Z');
            return end - i - 4 == 16 && boundaryAfter(s, end) ? end : -1;
        }
        if (c == 'A' && s.startsWith("AIza", i)) {
            int end = run(s, i + 4, Redactor::tokenChar);
            return end - i - 4 == 35 ? end : -1;
        }
        return -1;
    }

    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "pass", "pwd",
            "passphrase", "psk", "secret", "token", "auth", "credential", "credentials",
            "authorization", "apikey", "privatekey");
    /** The words before "key" that make it a secret: api_key, private_key, secret_key, access_key. */
    private static final Set<String> KEY_QUALIFIERS = Set.of("api", "private", "secret", "access");
    /** An auth scheme, written before the token it carries: "Bearer xyz". */
    private static final Set<String> AUTH_SCHEMES = Set.of("bearer", "basic", "token", "digest", "bot");
    /** Unquoted words that are a type or a constant, not a value: password: str, token = None. */
    private static final Set<String> NOT_VALUES = Set.of("none", "null", "nil", "true", "false",
            "undefined", "yes", "no", "str", "string", "int", "integer", "bool", "boolean", "bytes",
            "float", "number", "any", "object");

    /** What a named value is. */
    private enum Field { SECRET, SSID, HOST }

    /**
     * Values found by the name they are written under, line by line: UCI ({@code option ssid
     * 'x'}, {@code config host} sections), {@code uci show} and INI, env and hostapd lines
     * ({@code name=value}), YAML and header lines ({@code name: value}), JSON and Python dicts
     * ({@code "name": "value"}), query strings ({@code ?token=x&}) and command-line flags
     * ({@code --password=x}); and the name column of dnsmasq and odhcpd lease lines. An unquoted
     * value is taken to the end of its line only where its name starts the line; in the middle
     * of one, only a quoted value is -- code is full of {@code name: type} and
     * {@code name = expression}.
     */
    private static void namedValues(String s, List<Finding> secrets, List<Finding> ids) {
        int n = s.length();
        String section = null;
        for (int ls = 0; ls <= n; ) {
            int nl = s.indexOf('\n', ls);
            int lineEnd = nl < 0 ? n : nl;
            int le = lineEnd > ls && s.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            int first = ls;
            while (first < le && isBlank(s.charAt(first))) first++;
            if (s.startsWith("config ", first)) {
                int w = skipBlanks(s, first + 7, le);
                section = unquote(s.substring(w, runEnd(s, w, le)));
            } else if (s.startsWith("package ", first)) {
                section = null;
            } else if (s.startsWith("option ", first) || s.startsWith("list ", first)) {
                uciOption(s, first, le, section, secrets, ids);
            } else if (!leaseLine(s, first, le, ids)) {
                separated(s, ls, first, le, section, secrets, ids);
            }
            if (nl < 0) break;
            ls = nl + 1;
        }
    }

    /** {@code option NAME VALUE}: the value quoted, or the rest of the line. */
    private static void uciOption(String s, int first, int le, String section,
                                  List<Finding> secrets, List<Finding> ids) {
        int nameStart = skipBlanks(s, s.indexOf(' ', first), le);
        int nameEnd = runEnd(s, nameStart, le);
        Field field = fieldOf(unquote(s.substring(nameStart, nameEnd)), section);
        if (field == null) return;
        int v = skipBlanks(s, nameEnd, le);
        if (v >= le) return;
        int[] span = isQuote(s.charAt(v)) ? quoted(s, v, le) : trimmed(s, v, le);
        if (span != null) add(field, s, span[0], span[1], span[2] == 1, secrets, ids);
    }

    /** Every {@code name=value} and {@code name: value} in the line. */
    private static void separated(String s, int ls, int first, int le, String section,
                                  List<Finding> secrets, List<Finding> ids) {
        for (int p = first; p < le; p++) {
            char c = s.charAt(p);
            if (c != '=' && c != ':') continue;
            char prev = p > ls ? s.charAt(p - 1) : 0;
            char next = p + 1 < le ? s.charAt(p + 1) : 0;
            if (c == '=' && (next == '=' || next == '>' || prev == '=' || prev == '!' || prev == '<'
                    || prev == '>' || prev == ':')) continue;
            if (c == ':' && (next == '=' || next == ':' || prev == ':' || next == '/')) continue;
            // The name before it, quoted or bare, with blanks allowed between.
            int q = p;
            while (q > ls && isBlank(s.charAt(q - 1))) q--;
            int nameStart, nameEnd, before;
            boolean quotedName = false;
            if (q > ls && isQuote(s.charAt(q - 1))) {
                char quote = s.charAt(q - 1);
                int open = q - 2;
                while (open >= ls && nameChar(s.charAt(open))) open--;
                if (open < ls || s.charAt(open) != quote || open == q - 2) continue;
                nameStart = open + 1;
                nameEnd = q - 1;
                before = open;
                quotedName = true;
            } else {
                nameEnd = q;
                nameStart = q;
                while (nameStart > ls && nameChar(s.charAt(nameStart - 1))) nameStart--;
                if (nameStart == nameEnd) continue;
                before = nameStart;
            }
            String name = s.substring(nameStart, nameEnd);
            boolean flag = !quotedName && name.startsWith("-");
            Field field = fieldOf(name, section);
            if (field == null) continue;
            char ahead = before > ls ? s.charAt(before - 1) : 0;
            boolean query = !quotedName && c == '=' && (ahead == '?' || ahead == '&');
            String prefix = s.substring(ls, before).strip();
            boolean lineStart = prefix.isEmpty() || prefix.equals("export") || prefix.equals("-");
            int v = skipBlanks(s, p + 1, le);
            int[] span;
            if (v < le && isQuote(s.charAt(v))) {
                span = quoted(s, v, le);
            } else if (query) {
                int end = v;
                while (end < le && "&# \t\"'<>)]}".indexOf(s.charAt(end)) < 0) end++;
                span = new int[] {v, end, 0};
            } else if (flag && c == '=') {
                span = new int[] {v, runEnd(s, v, le), 0};
            } else if (lineStart) {
                span = trimmed(s, v, le);
                if (span != null && quotedName && s.charAt(span[1] - 1) == ',') span[1]--;
            } else {
                continue;
            }
            if (span == null) continue;
            add(field, s, span[0], span[1], span[2] == 1, secrets, ids);
            p = Math.max(p, span[1]);
        }
    }

    /**
     * The name column of a lease line: dnsmasq's {@code <expiry> <mac> <ip> <name> <client-id>},
     * and odhcpd's {@code # <iface> <duid> <iaid> <name> <valid> <id> <length> <addresses>}.
     * Whether the line was one.
     */
    private static boolean leaseLine(String s, int first, int le, List<Finding> ids) {
        var starts = new ArrayList<Integer>();
        var ends = new ArrayList<Integer>();
        for (int i = first; i < le && starts.size() < 6; ) {
            int e = runEnd(s, i, le);
            starts.add(i);
            ends.add(e);
            i = skipBlanks(s, e, le);
        }
        java.util.function.IntFunction<String> t = k -> s.substring(starts.get(k), ends.get(k));
        if (starts.size() >= 4 && allDigits(t.apply(0)) && macAt(s, starts.get(1)) == ends.get(1)
                && (t.apply(2).indexOf('.') >= 0 || t.apply(2).indexOf(':') >= 0)) {
            if (!t.apply(3).equals("*")) ids.add(new Finding(starts.get(3), ends.get(3), Kind.HOST));
            return true;
        }
        if (starts.size() >= 6 && t.apply(0).equals("#") && t.apply(2).length() >= 8 && allHex(t.apply(2))
                && (allHex(t.apply(3)) || t.apply(3).equals("ipv4"))
                && (allDigits(t.apply(5)) || t.apply(5).equals("-1"))) {
            if (!t.apply(4).equals("-")) ids.add(new Finding(starts.get(4), ends.get(4), Kind.HOST));
            return true;
        }
        return false;
    }

    /** What a value is by its name, or null for a name that says nothing. */
    private static Field fieldOf(String name, String section) {
        String path = name;
        while (path.startsWith("-")) path = path.substring(1);
        String last = path.substring(path.lastIndexOf('.') + 1);
        int bracket = last.indexOf('[');
        if (bracket >= 0) last = last.substring(0, bracket);
        List<String> words = words(last);
        if (words.isEmpty()) return null;
        String w = words.get(words.size() - 1);
        String before = words.size() > 1 ? words.get(words.size() - 2) : null;
        if (w.equals("ssid") || w.equals("essid")) return Field.SSID;
        if (w.equals("hostname") || w.equals("name") && "host".equals(before)) return Field.HOST;
        if (words.size() == 1 && w.equals("name") && ("host".equals(section) || path.contains("@host["))) {
            return Field.HOST;
        }
        if (SECRET_WORDS.contains(w)) return Field.SECRET;
        if (w.equals("key") && (before == null || KEY_QUALIFIERS.contains(before))) return Field.SECRET;
        return null;
    }

    /** A name's words, lowercased: split at '_', '-' and a lower-to-upper case change. */
    private static List<String> words(String name) {
        var out = new ArrayList<String>();
        var w = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean split = c == '_' || c == '-';
            if (!split && Character.isUpperCase(c) && i > 0 && Character.isLowerCase(name.charAt(i - 1))
                    && w.length() > 0) {
                out.add(w.toString());
                w.setLength(0);
            }
            if (split) {
                if (w.length() > 0) out.add(w.toString());
                w.setLength(0);
            } else {
                w.append(Character.toLowerCase(c));
            }
        }
        if (w.length() > 0) out.add(w.toString());
        return out;
    }

    /** The value at {@code [start, end)} under a name, if it is a value and not code or nothing. */
    private static void add(Field field, String s, int start, int end, boolean quoted,
                            List<Finding> secrets, List<Finding> ids) {
        String v = s.substring(start, end);
        String t = v.strip();
        if (t.isEmpty() || t.startsWith("«") || isPlaceholder(t)) return;
        String lower = t.toLowerCase(Locale.ROOT);
        if (field != Field.SECRET) {
            if (t.equals("*") || t.equals("-") || !quoted && NOT_VALUES.contains(lower)) return;
            ids.add(new Finding(start, end, field == Field.SSID ? Kind.SSID : Kind.HOST));
            return;
        }
        if (t.chars().allMatch(ch -> ch == '*')) return;              // masked already
        if (t.indexOf('{') >= 0 || t.indexOf('}') >= 0) return;       // a template, an f-string
        if (t.startsWith("$") || t.startsWith("%")) return;           // $PASS, ${PASS}, %s
        if (AUTH_SCHEMES.contains(lower)) return;                     // "Bearer " + token
        if (!quoted) {
            if (t.indexOf('(') >= 0 || t.indexOf('[') >= 0) return;   // os.environ["X"], getpass()
            String head = lower.split("[\\s=|,]", 2)[0];
            if (NOT_VALUES.contains(head)) return;                    // None, str, true
            if (dottedIdentifier(t)) return;                          // args.password
            String[] parts = t.split("\\s+");
            if (parts.length > 1 && !(parts.length == 2 && AUTH_SCHEMES.contains(parts[0].toLowerCase(Locale.ROOT)))) {
                return;                                               // prose: "token: the API token"
            }
        }
        secrets.add(new Finding(start, end, null));
    }

    private static boolean dottedIdentifier(String t) {
        String[] parts = t.split("\\.", -1);
        if (parts.length < 2) return false;
        for (String p : parts) {
            if (p.isEmpty() || !(Character.isLetter(p.charAt(0)) || p.charAt(0) == '_')) return false;
            for (int i = 1; i < p.length(); i++) {
                char c = p.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
            }
        }
        return true;
    }

    private static boolean isPlaceholder(String t) {
        if (t.length() < 5 || t.charAt(0) != '<' || t.charAt(t.length() - 1) != '>') return false;
        int u = t.indexOf('_');
        if (u < 2) return false;
        for (int i = 1; i < u; i++) if (t.charAt(i) < 'a' || t.charAt(i) > 'z') return false;
        for (int i = u + 1; i < t.length() - 1; i++) if (!isDigit(t.charAt(i))) return false;
        return u + 1 < t.length() - 1;
    }

    /** A quoted value from the quote at {@code q}: {start, end, 1}, or null when it does not close in the line. */
    private static int[] quoted(String s, int q, int le) {
        char quote = s.charAt(q);
        for (int j = q + 1; j < le; j++) {
            char c = s.charAt(j);
            if (c == '\\') { j++; continue; }
            if (c == quote) return new int[] {q + 1, j, 1};
        }
        return null;
    }

    /** The rest of the line from {@code v}, without the blanks at its end: {start, end, 0}. */
    private static int[] trimmed(String s, int v, int le) {
        int end = le;
        while (end > v && isBlank(s.charAt(end - 1))) end--;
        return end > v ? new int[] {v, end, 0} : null;
    }

    // e-mail addresses: a local part, '@', a domain with a dot and a top-level domain of letters.
    private static void emails(String s, List<Finding> out) {
        int n = s.length();
        for (int at = s.indexOf('@'); at >= 0; at = s.indexOf('@', at + 1)) {
            int l = at;
            while (l > 0 && localChar(s.charAt(l - 1))) l--;
            while (l < at && s.charAt(l) == '.') l++;
            if (l == at) continue;
            int r = at + 1;
            while (r < n && (isAsciiAlnum(s.charAt(r)) || s.charAt(r) == '.' || s.charAt(r) == '-')) r++;
            while (r > at + 1 && (s.charAt(r - 1) == '.' || s.charAt(r - 1) == '-')) r--;
            String domain = s.substring(at + 1, r);
            int dot = domain.lastIndexOf('.');
            if (dot <= 0 || domain.startsWith("-") || domain.contains("..")) continue;
            String tld = domain.substring(dot + 1);
            if (tld.length() < 2 || !tld.chars().allMatch(Character::isLetter)) continue;
            out.add(new Finding(l, r, Kind.EMAIL));
        }
    }

    private static boolean localChar(char c) {
        return isAsciiAlnum(c) || c == '.' || c == '_' || c == '%' || c == '+' || c == '-';
    }

    // Phone numbers in international form: '+', a country code, 8 to 15 digits in all, grouped
    // by spaces, dashes, dots or brackets.
    private static void phones(String s, List<Finding> out) {
        int n = s.length();
        for (int i = s.indexOf('+'); i >= 0; i = s.indexOf('+', i + 1)) {
            if (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '+')) continue;
            if (i + 1 >= n || s.charAt(i + 1) < '1' || s.charAt(i + 1) > '9') continue;
            int digits = 0, j = i + 1, last = -1;
            while (j < n && digits <= 15) {
                char c = s.charAt(j);
                if (isDigit(c)) {
                    digits++;
                    last = j++;
                    continue;
                }
                int k = j;
                while (k < n && k - j < 2 && " -.()".indexOf(s.charAt(k)) >= 0) k++;
                if (k == j || k >= n || !isDigit(s.charAt(k))) break;
                j = k;
            }
            if (digits < 8 || digits > 15) continue;
            if (last + 1 < n && Character.isLetterOrDigit(s.charAt(last + 1))) continue;
            out.add(new Finding(i, last + 1, Kind.PHONE));
            i = last;
        }
    }

    // IBANs: a country, two check digits and up to 30 more letters and digits, compact or in
    // groups of four, valid by ISO 7064 mod 97.
    private static void ibans(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i + 15 <= n; i++) {
            if (!upper(s.charAt(i)) || !upper(s.charAt(i + 1)) || !isDigit(s.charAt(i + 2))
                    || !isDigit(s.charAt(i + 3))) continue;
            if (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) continue;
            char[] chars = new char[34];
            int[] endAt = new int[34];
            int count = 0;
            for (int j = i; j < n && count < 34; ) {
                char c = s.charAt(j);
                if (upper(c) || isDigit(c)) {
                    chars[count] = c;
                    endAt[count++] = ++j;
                } else if (c == ' ' && count % 4 == 0 && j + 1 < n
                        && (upper(s.charAt(j + 1)) || isDigit(s.charAt(j + 1)))) {
                    j++;
                } else {
                    break;
                }
            }
            for (int len = count; len >= 15; len--) {
                int e = endAt[len - 1];
                if (e < n && Character.isLetterOrDigit(s.charAt(e))) continue;
                if (mod97(chars, len) == 1) {
                    out.add(new Finding(i, e, Kind.IBAN));
                    i = e - 1;
                    break;
                }
            }
        }
    }

    private static int mod97(char[] chars, int len) {
        int r = 0;
        for (int k = 0; k < len; k++) {
            char c = chars[(k + 4) % len];
            if (isDigit(c)) {
                r = (r * 10 + (c - '0')) % 97;
            } else {
                r = (r * 100 + (c - 'A' + 10)) % 97;
            }
        }
        return r;
    }

    // Payment card numbers: 13 to 19 digits, compact or in groups of 4 to 6 split by a space or a
    // dash, starting as an issuer's do, valid by Luhn.
    private static void cards(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (!isDigit(s.charAt(i))) continue;
            int start = i;
            char prev = start > 0 ? s.charAt(start - 1) : ' ';
            boolean okStart = !(Character.isLetterOrDigit(prev) || prev == '_'
                    || (prev == '.' || prev == '-' || prev == ',') && start >= 2 && isDigit(s.charAt(start - 2)));
            var digits = new StringBuilder();
            int j = i, last = i, group = 0;
            boolean groupsOk = true;
            while (j < n) {
                char c = s.charAt(j);
                if (isDigit(c)) {
                    digits.append(c);
                    group++;
                    last = j++;
                } else if ((c == ' ' || c == '-') && j + 1 < n && isDigit(s.charAt(j + 1))) {
                    if (group < 4 || group > 6) groupsOk = false;
                    group = 0;
                    j++;
                } else {
                    break;
                }
            }
            i = last;
            char after = last + 1 < n ? s.charAt(last + 1) : ' ';
            boolean okEnd = !(Character.isLetterOrDigit(after) || after == '_'
                    || (after == '.' || after == ',') && last + 2 < n && isDigit(s.charAt(last + 2)));
            int len = digits.length();
            if (okStart && okEnd && groupsOk && len >= 13 && len <= 19 && issuer(digits) && luhn(digits)) {
                out.add(new Finding(start, last + 1, Kind.CARD));
            }
        }
    }

    private static boolean issuer(CharSequence d) {
        char c = d.charAt(0);
        if (c == '3' || c == '4' || c == '5' || c == '6') return true;
        int four = Integer.parseInt(d.subSequence(0, 4).toString());
        return four >= 2221 && four <= 2720;
    }

    private static boolean luhn(CharSequence d) {
        int sum = 0;
        boolean twice = false;
        for (int k = d.length() - 1; k >= 0; k--) {
            int x = d.charAt(k) - '0';
            if (twice) {
                x *= 2;
                if (x > 9) x -= 9;
            }
            sum += x;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    // MAC addresses: six pairs of hex digits split by ':' or '-'. The all-zero and broadcast
    // addresses are nobody's.
    private static void macs(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i + 17 <= n; i++) {
            int end = macAt(s, i);
            if (end < 0) continue;
            String v = s.substring(i, end).toLowerCase(Locale.ROOT).replace('-', ':');
            if (!v.equals("00:00:00:00:00:00") && !v.equals("ff:ff:ff:ff:ff:ff")) {
                out.add(new Finding(i, end, Kind.MAC));
            }
            i = end - 1;
        }
    }

    /** The end of a MAC address starting at {@code i}, or -1. */
    private static int macAt(String s, int i) {
        int n = s.length();
        if (i + 17 > n) return -1;
        if (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == ':'
                || s.charAt(i - 1) == '-')) return -1;
        char sep = s.charAt(i + 2);
        if (sep != ':' && sep != '-') return -1;
        for (int k = 0; k < 6; k++) {
            int g = i + 3 * k;
            if (!isHex(s.charAt(g)) || !isHex(s.charAt(g + 1))) return -1;
            if (k < 5 && s.charAt(g + 2) != sep) return -1;
        }
        int end = i + 17;
        if (end < n && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == sep)) return -1;
        return end;
    }

    // Public IPv4 addresses. Private, loopback, link-local, CGNAT, documentation, benchmarking,
    // multicast and reserved addresses stay as they are, and so does a dotted number after a
    // letter or among more than four parts -- a version.
    private static void ipv4(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (!isDigit(s.charAt(i))) continue;
            if (i > 0 && (Character.isLetterOrDigit(s.charAt(i - 1)) || s.charAt(i - 1) == '.'
                    || s.charAt(i - 1) == '_')) continue;
            int[] octets = new int[4];
            int j = i;
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                int d = j;
                int v = 0;
                while (j < n && j - d < 3 && isDigit(s.charAt(j))) v = v * 10 + (s.charAt(j++) - '0');
                if (j == d || v > 255 || (j < n && isDigit(s.charAt(j)))) ok = false;
                octets[k] = v;
                if (ok && k < 3) {
                    if (j < n && s.charAt(j) == '.') j++;
                    else ok = false;
                }
            }
            if (!ok) continue;
            if (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_'
                    || s.charAt(j) == '.' && j + 1 < n && isDigit(s.charAt(j + 1)))) {
                i = j;
                continue;
            }
            if (publicV4(octets[0], octets[1], octets[2])) out.add(new Finding(i, j, Kind.IP));
            i = j - 1;
        }
    }

    private static boolean publicV4(int a, int b, int c) {
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;
        if (a == 198 && (b == 18 || b == 19)) return false;
        if (a == 198 && b == 51 && c == 100) return false;
        return !(a == 203 && b == 0 && c == 113);
    }

    // Public IPv6 addresses: global unicast, 2000::/3, but the documentation prefix 2001:db8::/32.
    private static void ipv6(String s, List<Finding> out) {
        int n = s.length();
        for (int i = 0; i < n; ) {
            char c = s.charAt(i);
            if (!isHex(c) && c != ':') {
                i++;
                continue;
            }
            int start = i, j = i, colons = 0;
            while (j < n && (isHex(s.charAt(j)) || s.charAt(j) == ':' || s.charAt(j) == '.')) {
                if (s.charAt(j) == ':') colons++;
                j++;
            }
            boolean okStart = start == 0 || !(Character.isLetterOrDigit(s.charAt(start - 1)) || s.charAt(start - 1) == '_');
            boolean okEnd = j >= n || !(Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_');
            int e = j;
            while (e > start && (s.charAt(e - 1) == '.'
                    || s.charAt(e - 1) == ':' && !(e - 2 >= start && s.charAt(e - 2) == ':'))) e--;
            if (okStart && okEnd && colons >= 2) {
                int[] head = ipv6Head(s.substring(start, e));
                if (head != null && head[0] >= 0x2000 && head[0] <= 0x3fff
                        && !(head[0] == 0x2001 && head[1] == 0x0db8)) {
                    out.add(new Finding(start, e, Kind.IP));
                }
            }
            i = j;
        }
    }

    /** The first two groups of a valid IPv6 address, or null when it is not one. */
    private static int[] ipv6Head(String a) {
        int gap = a.indexOf("::");
        if (gap >= 0 && a.indexOf("::", gap + 1) >= 0) return null;
        String[] sides = gap >= 0 ? new String[] {a.substring(0, gap), a.substring(gap + 2)}
                : new String[] {a};
        var head = new ArrayList<Integer>();
        int count = 0;
        for (int side = 0; side < sides.length; side++) {
            if (sides[side].isEmpty()) continue;
            String[] parts = sides[side].split(":", -1);
            for (int k = 0; k < parts.length; k++) {
                String p = parts[k];
                if (p.indexOf('.') >= 0) {
                    // An IPv4 address can only be the last two groups.
                    if (side != sides.length - 1 || k != parts.length - 1 || !dottedQuad(p)) return null;
                    count += 2;
                } else if (p.isEmpty() || p.length() > 4 || !allHex(p)) {
                    return null;
                } else {
                    if (side == 0) head.add(Integer.parseInt(p, 16));
                    count++;
                }
            }
        }
        if (gap >= 0 ? count > 7 : count != 8) return null;
        return new int[] {head.isEmpty() ? 0 : head.get(0), head.size() < 2 ? 0 : head.get(1)};
    }

    private static boolean dottedQuad(String p) {
        String[] o = p.split("\\.", -1);
        if (o.length != 4) return false;
        for (String x : o) {
            if (x.isEmpty() || x.length() > 3 || !allDigits(x) || Integer.parseInt(x) > 255) return false;
        }
        return true;
    }

    // ── characters ──

    private static int run(String s, int from, java.util.function.IntPredicate in) {
        int i = from;
        while (i < s.length() && in.test(s.charAt(i))) i++;
        return i;
    }

    /** The end of the run of non-blank characters from {@code i}, within the line. */
    private static int runEnd(String s, int i, int le) {
        while (i < le && !isBlank(s.charAt(i))) i++;
        return i;
    }

    private static int skipBlanks(String s, int i, int le) {
        if (i < 0) return le;
        while (i < le && isBlank(s.charAt(i))) i++;
        return i;
    }

    private static String unquote(String t) {
        return t.length() >= 2 && isQuote(t.charAt(0)) && t.charAt(t.length() - 1) == t.charAt(0)
                ? t.substring(1, t.length() - 1) : t;
    }

    private static boolean boundaryAfter(String s, int end) {
        return end >= s.length() || !tokenChar(s.charAt(end));
    }

    private static boolean nameChar(char c) {
        return isAsciiAlnum(c) || c == '_' || c == '-' || c == '.' || c == '@' || c == '[' || c == ']';
    }

    private static boolean tokenChar(int c) {
        return isAsciiAlnum(c) || c == '_' || c == '-';
    }

    private static boolean isAsciiAlnum(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    private static boolean upper(char c) { return c >= 'A' && c <= 'Z'; }

    private static boolean isHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean isQuote(char c) { return c == '"' || c == '\''; }

    private static boolean isBlank(char c) { return c == ' ' || c == '\t'; }

    private static boolean allDigits(String t) {
        return !t.isEmpty() && t.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean allHex(String t) {
        return !t.isEmpty() && t.chars().allMatch(c -> isHex((char) c));
    }
}
