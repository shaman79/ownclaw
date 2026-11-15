package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.sandbox.ContainerSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic capability gap detection — runs BEFORE the LLM thinks.
 *
 * <h2>Why this exists</h2>
 * The local thinking LLM (small, 14B params) is unreliable at deciding whether
 * to create a new skill. Its safety training makes it refuse tasks involving
 * local commands ("nmap not installed", "sudo blocked") instead of using
 * {@code skill_create} with {@code system_packages}. No amount of prompt
 * engineering reliably fixes this — it's a fundamental limitation.
 *
 * <h2>Architecture</h2>
 * This component moves the "do I need a new skill?" decision from the
 * unreliable LLM into deterministic code:
 * <ol>
 *   <li>Classify the user's request against known capability categories</li>
 *   <li>Check if any existing skill covers the required capability</li>
 *   <li>If a gap is detected, produce a specific {@link CapabilityHint} that
 *       tells the LLM exactly what to do — not "you can create skills" (generic)
 *       but "create a skill named X with system_packages Y" (specific)</li>
 * </ol>
 *
 * <p>The LLM's job is reduced to confirming a well-specified action, which
 * even small models handle reliably.
 *
 * <h2>Container awareness</h2>
 * When a container runtime (Docker/Podman) is available, hints include
 * {@code system_packages} so the skill runs in a container with OS packages
 * pre-installed.  When no container runtime is available (e.g. NoNewPrivileges
 * blocks rootless Podman), hints automatically switch to pure-Python
 * alternatives that don't need system packages.  This ensures the agent
 * always produces a working skill on the first try.
 *
 * <h2>Extensibility</h2>
 * Add new capabilities by adding entries to {@link #CAPABILITY_PATTERNS}.
 * Each entry maps regex triggers → required system packages → suggested skill.
 * For tasks that don't match any known pattern, the resolver stays silent and
 * the LLM operates normally.
 */
@Component
public class CapabilityResolver {

    private static final Logger log = LoggerFactory.getLogger(CapabilityResolver.class);

    private final ToolRegistry toolRegistry;
    private final ContainerSandbox containerSandbox;

    public CapabilityResolver(ToolRegistry toolRegistry, ContainerSandbox containerSandbox) {
        this.toolRegistry = toolRegistry;
        this.containerSandbox = containerSandbox;
    }

    // ─────────────── Capability Pattern Definitions ───────────────

    /**
     * The word for mail in a trigger: mail, e-mail, email, gmail, webmail, and what they are stems
     * of ("emails", "e-maily"). Not the same letters inside an address or a host's name --
     * "someone@gmail.com", "https://webmail.example.org", "mail.example.org": a task that sends
     * something to an address asks for no mailbox to be read.
     */
    private static final String MAIL = "(?<![@./:])(?:e-?|g|web)?mail(?!\\.[\\p{L}\\p{N}])";

    /**
     * Known capability categories. Each maps user-intent patterns to the
     * system packages and skill shape needed to fulfill them.
     *
     * <p>Order matters: first match wins. Put more specific patterns before
     * generic ones.
     */
    private static final List<CapabilityPattern> CAPABILITY_PATTERNS = List.of(

            // ── Network scanning & discovery ──
            new CapabilityPattern(
                    "network_scanning",
                    List.of(
                            "(?:re|port)?scan.*(?:sub)?network", "(?:sub)?network.*(?:re|port)?scan", "port-?scan", "find.*devices",
                            "(?:re)?discover.*hosts?", "open.*ports?", "port.*scan",
                            "\\bnmap\\b", "network.*discover",
                            "(?:na|pro|o|za|pře)?skenov.*síť", "(?:na|pro|o|za|pře)?skenuj.*síť", "najdi.*zařízení",  // Czech
                            "otevřené.*porty", "skenování.*sítě"
                    ),
                    "network_scanner",
                    "Scan the local network with nmap as an unprivileged scanner: the container has no raw "
                            + "sockets, so always pass --unprivileged. Discover live hosts with a TCP-connect "
                            + "ping sweep (-sn --unprivileged), or skip discovery with -Pn, and read MAC addresses "
                            + "from the neighbour table (/proc/net/arp). Then scan each host's ports with -sT -sV. "
                            + "No -O, no -sS, no ARP ping: identify the device type from the service banners. "
                            + "Output structured results: IP, hostname, MAC, device type, open ports with service names.",
                    List.of("nmap", "net-tools"),
                    List.of("python-nmap"),
                    "{ \"target\": { \"type\": \"string\", \"description\": \"Target network in CIDR notation (e.g. 192.168.1.0/24). "
                            + "If empty, auto-detect local subnet.\", \"required\": false } }",
                    120,
                    List.of("scan", "network", "device", "port", "host", "nmap")
            ),

            // ── Traceroute / network path ──
            new CapabilityPattern(
                    "traceroute",
                    List.of("traceroute", "trace.*route", "network.*path", "hop.*count"),
                    "traceroute_tool",
                    "Trace network path to a target host, showing each hop with latency.",
                    List.of("traceroute", "iputils-ping"),
                    List.of(),
                    "{ \"host\": { \"type\": \"string\", \"description\": \"Target hostname or IP\", \"required\": true } }",
                    60,
                    List.of("traceroute", "trace", "route", "hop", "path")
            ),

            // ── DNS lookup ──
            new CapabilityPattern(
                    "dns_lookup",
                    List.of("dns.*lookup", "dig\\b", "nslookup", "resolve.*domain",
                            "dns.*record", "\\bmx record", "\\btxt record", "\\bcname"),
                    "dns_lookup",
                    "Perform DNS lookups: A, AAAA, MX, TXT, CNAME, NS, SOA records for a domain.",
                    List.of("dnsutils"),
                    List.of(),
                    "{ \"domain\": { \"type\": \"string\", \"description\": \"Domain name to query\", \"required\": true }, "
                            + "\"record_type\": { \"type\": \"string\", \"description\": \"Record type (A, AAAA, MX, TXT, CNAME, NS, SOA, ANY)\", \"required\": false } }",
                    30,
                    List.of("dns", "lookup", "dig", "nslookup", "domain", "record")
            ),

            // ── Media processing (ffmpeg) ──
            new CapabilityPattern(
                    "media_processing",
                    List.of("convert.*video", "convert.*audio", "transcode", "\\bffmpeg\\b",
                            "extract.*audio", "video.*(?:in)?to.*mp[34]", "compress.*video",
                            "merge.*video", "trim.*video", "cut.*video"),
                    "media_processor",
                    "Process media files using ffmpeg: convert formats, extract audio, "
                            + "trim/cut, merge, compress, change resolution/bitrate.",
                    List.of("ffmpeg"),
                    List.of(),
                    "{ \"input\": { \"type\": \"string\", \"description\": \"Input file path\", \"required\": true }, "
                            + "\"operation\": { \"type\": \"string\", \"description\": \"Operation: convert, extract_audio, trim, compress, info\", \"required\": true }, "
                            + "\"output\": { \"type\": \"string\", \"description\": \"Output file path\", \"required\": false } }",
                    300,
                    List.of("video", "audio", "media", "ffmpeg", "convert", "transcode")
            ),

            // ── Image processing (ImageMagick) ──
            new CapabilityPattern(
                    "image_processing",
                    List.of("convert.*image", "resize.*image", "\\bimagemagick\\b",
                            "image.*manipulation", "crop.*image", "rotate.*image",
                            "thumbnail", "image.*format"),
                    "image_processor",
                    "Process images using ImageMagick: resize, crop, rotate, convert formats, "
                            + "apply filters, create thumbnails, get metadata.",
                    List.of("imagemagick"),
                    List.of("Pillow"),
                    "{ \"input\": { \"type\": \"string\", \"description\": \"Input image path\", \"required\": true }, "
                            + "\"operation\": { \"type\": \"string\", \"description\": \"Operation: resize, crop, rotate, convert, info, thumbnail\", \"required\": true } }",
                    120,
                    List.of("image", "resize", "crop", "rotate", "convert", "thumbnail")
            ),

            // ── Document conversion (Pandoc) ──
            new CapabilityPattern(
                    "document_conversion",
                    List.of("convert.*docx?", "convert.*pdf", "\\bpandoc\\b",
                            "markdown.*(?:in)?to.*pdf", "html.*(?:in)?to.*pdf", "document.*convert"),
                    "document_converter",
                    "Convert documents between formats using Pandoc: Markdown, HTML, DOCX, PDF, "
                            + "LaTeX, EPUB, etc.",
                    List.of("pandoc"),
                    List.of(),
                    "{ \"input\": { \"type\": \"string\", \"description\": \"Input file path\", \"required\": true }, "
                            + "\"output_format\": { \"type\": \"string\", \"description\": \"Target format (pdf, docx, html, epub, etc.)\", \"required\": true } }",
                    120,
                    List.of("document", "convert", "pandoc", "pdf", "docx")
            ),

            // ── System info / monitoring ──
            new CapabilityPattern(
                    "system_monitoring",
                    List.of("system.*info", "cpu.*usage", "memory.*usage", "disk.*usage",
                            "system.*monitor", "\\bhtop\\b", "system.*health"),
                    "system_info",
                    "Gather system information: CPU, memory, disk usage, network interfaces, "
                            + "uptime. Uses standard Linux tools.",
                    List.of("procps", "net-tools", "iproute2"),
                    List.of("psutil"),
                    "{ \"category\": { \"type\": \"string\", \"description\": \"Category: cpu, memory, disk, network, all\", \"required\": false } }",
                    30,
                    List.of("system", "info", "cpu", "memory", "disk", "monitor")
            ),

            // ── SSL/TLS certificate check ──
            new CapabilityPattern(
                    "ssl_check",
                    List.of("(?:open)?ssl.*cert", "tls.*cert", "certificate.*check", "certificate.*expir",
                            "https.*cert", "check.*ssl"),
                    "ssl_checker",
                    "Check SSL/TLS certificates: expiration date, issuer, subject, "
                            + "chain validity, protocol support.",
                    List.of("openssl"),
                    List.of(),
                    "{ \"host\": { \"type\": \"string\", \"description\": \"Hostname to check\", \"required\": true }, "
                            + "\"port\": { \"type\": \"string\", \"description\": \"Port (default: 443)\", \"required\": false } }",
                    30,
                    List.of("ssl", "tls", "certificate", "cert", "expir", "https")
            ),

            // ── Generic shell command execution ──
            new CapabilityPattern(
                    "shell_execution",
                    List.of("(?:re)?run.*command", "execute.*command", "shell.*command",
                            "spusť.*příkaz", "spust.*příkaz", "proveď.*příkaz",  // Czech
                            "terminal.*command", "bash.*command", "linux.*command"),
                    "shell_exec",
                    "Execute arbitrary shell commands on the local system. "
                            + "Captures stdout, stderr, and exit code.",
                    List.of(),
                    List.of(),
                    "{ \"command\": { \"type\": \"string\", \"description\": \"Shell command to execute\", \"required\": true }, "
                            + "\"timeout\": { \"type\": \"string\", \"description\": \"Timeout in seconds (default: 30)\", \"required\": false } }",
                    60,
                    List.of("shell", "command", "execute", "run", "bash", "terminal")
            ),

            // ── Email / IMAP ──
            new CapabilityPattern(
                    "email_imap",
                    List.of(
                            "check.*" + MAIL, MAIL + ".*check", "read.*" + MAIL, MAIL + ".*read",
                            "fetch.*" + MAIL, MAIL + ".*fetch", "inbox", "\\bimap\\b",
                            "unread.*" + MAIL, MAIL + ".*unread", "\\bnew\\b.*" + MAIL, MAIL + ".*\\bnew\\b",
                            MAIL + ".*summ", "summ.*" + MAIL,
                            "zkontroluj.*" + MAIL, "přečti.*" + MAIL, "\\bpošt",  // Czech
                            "nepřečten.*" + MAIL, MAIL + ".*nepřečten",
                            "stáhni.*" + MAIL, MAIL + ".*stáhn"
                    ),
                    "check_email",
                    "Connect to an IMAP server and fetch emails. "
                            + "Uses IMAP4_SSL to connect (host/port/user/pass from env vars IMAP_HOST, IMAP_PORT, IMAP_USER, IMAP_PASS). "
                            + "Searches for emails matching criteria (UNSEEN, FROM, subject, date range). "
                            + "Returns structured JSON: [{\"uid\": \"...\", \"from\": \"...\", \"to\": \"...\", \"subject\": \"...\", "
                            + "\"date\": \"...\", \"body_text\": \"...\", \"has_attachments\": true/false}]. "
                            + "Handles encoding (RFC2047 headers, multipart bodies, charset detection). "
                            + "Extracts the whole plain text body (prefers text/plain, falls back to text/html with tag stripping). "
                            + "Returns every matching email unless 'limit' is given.",
                    List.of(),
                    List.of(),
                    "{ \"mailbox\": { \"type\": \"string\", \"description\": \"IMAP mailbox folder (default: INBOX)\", \"required\": false }, "
                            + "\"search\": { \"type\": \"string\", \"description\": \"IMAP search criteria (default: UNSEEN). Examples: UNSEEN, ALL, FROM \\\"user@example.com\\\", SUBJECT \\\"keyword\\\"\", \"required\": false }, "
                            + "\"limit\": { \"type\": \"string\", \"description\": \"How many of the newest matching emails to fetch (default: every match)\", \"required\": false } }",
                    60,
                    List.of("email", "mail", "imap", "inbox", "unread", "fetch", "check"),
                    List.of("IMAP_HOST", "IMAP_PORT", "IMAP_USER", "IMAP_PASS")
            )
    );

    // ─────────────── Core Resolution Logic ───────────────

    /**
     * Analyze a user message and detect capability gaps.
     *
     * <p>When a container runtime is available, the hint suggests skills with
     * {@code system_packages} (e.g. nmap in a Docker container).  When no
     * container runtime is available, the hint switches to a pure-Python
     * alternative so the skill works without system packages.
     *
     * @param userMessage the user's original request
     * @return a capability hint if a gap was detected, or {@code null} if
     *         existing skills cover the request (or no known pattern matches)
     */
    public CapabilityHint resolve(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;

        String normalized = userMessage.toLowerCase();
        // Found once, in the message as it was written: where a sentence ends is read off its
        // capitals too, and the triggers are matched whatever the case.
        int[] sentences = sentences(userMessage);

        // 1. Match against known capability patterns
        CapabilityPattern matched = null;
        for (CapabilityPattern pattern : CAPABILITY_PATTERNS) {
            if (pattern.matches(userMessage, sentences, normalized)) {
                matched = pattern;
                break;
            }
        }

        if (matched == null) {
            // No known pattern — LLM operates normally
            return null;
        }

        // 2. Check if an existing skill already covers this capability
        String existingSkill = findMatchingSkill(matched);
        if (existingSkill != null) {
            // At INFO: how often a request sets a pattern off, and which skill is taken to cover
            // it, is the only record of what this component would do with a smaller library.
            // The category and the skill's name only, never the message.
            log.info("Capability '{}' asked for; the existing skill '{}' is taken to cover it — nothing is made",
                    matched.category, existingSkill);
            return null;
        }

        // 3. Gap detected — adapt hint based on container availability
        boolean containerAvailable = containerSandbox != null && containerSandbox.isAvailable();

        if (containerAvailable || matched.systemPackages.isEmpty()) {
            // Container available OR pattern doesn't need system packages — use as-is
            log.info("Capability gap detected: '{}' → skill '{}' with system_packages={} (container={})",
                    matched.category, matched.suggestedName, matched.systemPackages,
                    containerAvailable ? containerSandbox.runtime() : "none");

            return new CapabilityHint(
                    matched.category,
                    matched.suggestedName,
                    matched.description,
                    matched.systemPackages,
                    matched.pipPackages,
                    matched.parameters,
                    matched.timeout,
                    matched.credentials
            );
        }

        // 4. No container available AND pattern needs system packages — use pure-Python fallback
        log.info("Capability gap detected: '{}' → pure-Python fallback (no container runtime for {})",
                matched.category, matched.systemPackages);

        return buildPurePythonHint(matched);
    }

    /**
     * Build a pure-Python capability hint that doesn't require system packages.
     * Used when no container runtime is available (e.g. NoNewPrivileges blocks
     * rootless Podman, Docker not installed).
     *
     * <p>Maps each category to a Python-only alternative:
     * <ul>
     *   <li>network_scanning → TCP connect scan + ARP/neighbor table</li>
     *   <li>dns_lookup → dnspython library</li>
     *   <li>ssl_check → Python ssl module</li>
     *   <li>system_monitoring → psutil library</li>
     *   <li>image_processing → Pillow library</li>
     *   <li>Others → generic description with Python-only constraint</li>
     * </ul>
     */
    private CapabilityHint buildPurePythonHint(CapabilityPattern matched) {
        // Category-specific pure-Python alternatives (no system_packages, no container needed)
        return switch (matched.category) {
            case "network_scanning" -> new CapabilityHint(
                    matched.category,
                    "network_scanner",
                    "Scan a local network WITHOUT nmap (nmap is NOT available). "
                            + "Use pure Python only. "
                            + "1) Host discovery: read ARP/neighbor table via `ip neigh show` subprocess, "
                            + "plus concurrent ping sweep (`ping -c1 -W1`) to populate the table. "
                            + "2) Port scan: TCP connect scan using Python sockets with concurrent.futures. "
                            + "Scan common ports (1-1024 + well-known high ports like 3306,5432,8080,8443). "
                            + "3) Service identification: banner grab on open ports (HTTP HEAD, SSH banner, SMTP greeting). "
                            + "4) Output structured JSON: {\"scanned_targets\":[...], \"hosts\":[{\"ip\":\"\", \"status\":\"up\", "
                            + "\"method\":[\"neigh\",\"ping\"], \"open_ports\":[{\"port\":80, \"service\":\"http\", \"banner\":\"...\"}]}], "
                            + "\"errors\":[...]}. "
                            + "Never require sudo. Keep runtime bounded with timeout and concurrency limits.",
                    List.of(), // NO system packages
                    List.of(), // no pip packages needed
                    matched.parameters,
                    matched.timeout
            );
            case "traceroute" -> new CapabilityHint(
                    matched.category,
                    "traceroute_tool",
                    "Trace network path WITHOUT the traceroute system command (not available). "
                            + "Use raw Python sockets with incrementing TTL (socket.IP_TTL) and ICMP, "
                            + "or fall back to subprocess `ping -t <TTL>` on Linux. "
                            + "Show each hop with latency measurement.",
                    List.of(), List.of(),
                    matched.parameters, matched.timeout
            );
            case "dns_lookup" -> new CapabilityHint(
                    matched.category,
                    "dns_lookup",
                    "Perform DNS lookups using the dnspython library (pure Python, no dig/nslookup needed). "
                            + "Support A, AAAA, MX, TXT, CNAME, NS, SOA record types. "
                            + "Return structured JSON with all records found.",
                    List.of(), // no system packages
                    List.of("dnspython"),
                    matched.parameters, matched.timeout
            );
            case "ssl_check" -> new CapabilityHint(
                    matched.category,
                    "ssl_checker",
                    "Check SSL/TLS certificates using Python's built-in ssl module (no openssl command needed). "
                            + "Use ssl.create_default_context() + wrap_socket to connect and get peer cert. "
                            + "Extract: expiration, issuer, subject, serial, SAN. Return structured JSON.",
                    List.of(), List.of(),
                    matched.parameters, matched.timeout
            );
            case "system_monitoring" -> new CapabilityHint(
                    matched.category,
                    "system_info",
                    "Gather system information using the psutil Python library (no system packages needed). "
                            + "Report CPU usage, memory, disk, network interfaces, uptime. Do not report "
                            + "running processes: an isolated skill sees only its own.",
                    List.of(),
                    List.of("psutil"),
                    matched.parameters, matched.timeout
            );
            case "image_processing" -> new CapabilityHint(
                    matched.category,
                    "image_processor",
                    "Process images using the Pillow Python library (no ImageMagick needed). "
                            + "Resize, crop, rotate, convert formats, create thumbnails, get metadata.",
                    List.of(),
                    List.of("Pillow"),
                    matched.parameters, matched.timeout
            );
            default -> {
                // Generic fallback: strip system packages, add Python-only constraint to description
                String desc = "IMPORTANT: No container runtime available — do NOT use system commands that require "
                        + "package installation. Use only Python standard library and pip packages. "
                        + "Original task: " + matched.description;
                yield new CapabilityHint(
                        matched.category,
                        matched.suggestedName,
                        desc,
                        List.of(), // no system packages
                        matched.pipPackages,
                        matched.parameters,
                        matched.timeout
                );
            }
        };
    }

    /**
     * Check if any registered tool covers the given capability.
     * Matches by name similarity and keyword overlap with tool descriptions.
     */
    private String findMatchingSkill(CapabilityPattern pattern) {
        Collection<Tool> tools = toolRegistry.all();

        for (Tool tool : tools) {
            String toolName = tool.name().toLowerCase();
            String toolDesc = tool.description().toLowerCase();

            // Direct name match
            if (toolName.equals(pattern.suggestedName)) {
                return tool.name();
            }

            // Keyword overlap — if the existing skill covers most of the
            // capability's keywords, it's a match
            int hits = 0;
            for (String keyword : pattern.matchKeywords) {
                if (toolName.contains(keyword) || toolDesc.contains(keyword)) {
                    hits++;
                }
            }
            // If ≥50% keyword match, consider it covered
            if (!pattern.matchKeywords.isEmpty()
                    && hits >= Math.max(2, pattern.matchKeywords.size() / 2)) {
                return tool.name();
            }
        }

        return null;
    }

    // ─────────────── Data Classes ───────────────

    /**
     * A capability gap hint: specific instructions for the LLM to create a skill.
     */
    public record CapabilityHint(
            String category,
            String suggestedName,
            String description,
            List<String> systemPackages,
            List<String> pipPackages,
            String parametersJson,
            int timeout,
            List<String> credentials
    ) {
        /** Convenience constructor without credentials (backward compat). */
        public CapabilityHint(String category, String suggestedName, String description,
                              List<String> systemPackages, List<String> pipPackages,
                              String parametersJson, int timeout) {
            this(category, suggestedName, description, systemPackages, pipPackages,
                    parametersJson, timeout, List.of());
        }
    }

    /**
     * A known capability pattern: maps user intent to required tools.
     */
    private record CapabilityPattern(
            String category,
            List<String> triggerPatterns,
            String suggestedName,
            String description,
            List<String> systemPackages,
            List<String> pipPackages,
            String parameters,
            int timeout,
            List<String> matchKeywords,
            List<String> credentials
    ) {
        /** Convenience constructor without credentials (for existing patterns). */
        CapabilityPattern(String category, List<String> triggerPatterns, String suggestedName,
                          String description, List<String> systemPackages, List<String> pipPackages,
                          String parameters, int timeout, List<String> matchKeywords) {
            this(category, triggerPatterns, suggestedName, description, systemPackages, pipPackages,
                    parameters, timeout, matchKeywords, List.of());
        }
        boolean matches(String input, int[] sentences, String normalizedInput) {
            for (String trigger : triggerPatterns) {
                try {
                    if (inOneSentence(trigger, input, sentences)) return true;
                } catch (Exception e) {
                    // Invalid regex — treat as literal substring
                    if (normalizedInput.contains(trigger.toLowerCase())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Where a word begins: not after a letter, a digit or the underscore of a skill's name. */
    private static final String WORD_START = "(?<![\\p{L}\\p{N}_])";

    /**
     * Short forms that are written with a dot and end no sentence: "for example", "that is",
     * "about", in English and Czech. "Fetch approx. 10 emails" and "Zkontroluj např. e-maily"
     * are each one request. Only forms that are no word of their own: "doc.", "no.", "st." end
     * sentences as often as not.
     */
    private static final java.util.Set<String> ABBREVIATIONS = java.util.Set.of(
            "approx", "incl", "excl", "eg", "ie", "cf", "vs",
            "např", "tj", "tzn", "tzv", "resp", "popř", "příp", "cca", "mj");

    /** Titles before a name -- when written with a capital, as a title is: "Mr. Smith", not "for 20 ms." */
    private static final java.util.Set<String> TITLES = java.util.Set.of("mr", "mrs", "ms", "dr", "prof");

    /** Short forms of "number": they end no sentence before the number itself ("the no. 3 email"). */
    private static final java.util.Set<String> BEFORE_A_NUMBER = java.util.Set.of("no", "nr");

    /** Each trigger's words, compiled once: the triggers are constants. */
    private static final Map<String, List<Pattern>> WORDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A trigger's words, as they are looked for: each where a word begins. Written as
     * "open.*ports?", a trigger was searched for as a plain regex, so its words were found inside
     * other words and anywhere in the message: "open the file and send me a report" asked for a
     * port scan ("port" in "report"), and a scheduled task that names its skills -- "run
     * daily_news_digest, then smtp_send_email" -- asked for a mailbox reader ("new" in "news",
     * "mail" in "email"), which was then made at step 1 unless a skill happened to cover it. A
     * trigger's words are still stems: "summ" is "summary" and "summarise".
     */
    static List<Pattern> words(String trigger) {
        return WORDS.computeIfAbsent(trigger, t -> {
            var out = new ArrayList<Pattern>();
            for (String word : t.split("\\.\\*", -1)) {
                out.add(Pattern.compile((word.startsWith("\\b") ? "" : WORD_START) + word,
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            return List.copyOf(out);
        });
    }

    /**
     * The sentences of a text, as pairs of where each begins and ends. A sentence ends at a run
     * of . ! ? before a blank or the text's end -- a dot inside a word ("192.168.5.0",
     * "example.org") ends none, nor does the one dot of an abbreviation -- and at every line break.
     * <p>
     * One pass over the text, a character at a time. As a regex -- a run of marks, then a look
     * ahead for the blank -- a run with no blank after it was read again from each of its
     * characters: forty thousand dots in a row took half a minute, and this is asked before a
     * task starts, on its worker.
     */
    static int[] sentences(String text) {
        int n = text.length();
        int[] bounds = new int[16];
        int count = 0, start = 0, i = 0;
        while (i < n) {
            char c = text.charAt(i);
            int end;       // where the sentence that is open would end
            int next;      // where the one after it would begin
            if (lineBreak(c)) {
                end = i;
                while (i < n && lineBreak(text.charAt(i))) i++;
                next = i;
            } else if (mark(c)) {
                end = i;
                while (i < n && mark(text.charAt(i))) i++;
                next = i;
                boolean beforeABlank = i == n || Character.isWhitespace(text.charAt(i));
                if (!beforeABlank || (i - end == 1 && c == '.' && abbreviated(text, start, end, i))) continue;
            } else {
                i++;
                continue;
            }
            if (end > start) {
                if (count + 2 > bounds.length) bounds = java.util.Arrays.copyOf(bounds, bounds.length * 2);
                bounds[count++] = start;
                bounds[count++] = end;
            }
            start = next;
        }
        if (start < n) {
            if (count + 2 > bounds.length) bounds = java.util.Arrays.copyOf(bounds, bounds.length + 2);
            bounds[count++] = start;
            bounds[count++] = n;
        }
        return java.util.Arrays.copyOf(bounds, count);
    }

    private static boolean mark(char c) {
        return c == '.' || c == '!' || c == '?';
    }

    private static boolean lineBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /**
     * Whether the one dot at {@code dot} is an abbreviation's and no sentence's end: after a word
     * of {@link #ABBREVIATIONS}; after one of {@link #TITLES} written with a capital; after one
     * of {@link #BEFORE_A_NUMBER}, or a single letter ("č. 3"), when a number follows; after a
     * single letter that itself follows a dot ("e.g.", "i.e."). Any other single letter ends its
     * sentence -- "Check plan B. Email me the result" is two. Looks back over that one word, and
     * no further than an abbreviation is long.
     */
    private static boolean abbreviated(String text, int sentenceStart, int dot, int after) {
        int from = dot;
        while (from > sentenceStart && dot - from <= 6 && Character.isLetter(text.charAt(from - 1))) from--;
        int letters = dot - from;
        if (letters == 0 || letters > 6) return false;
        if (letters == 1) {
            return (from > sentenceStart && text.charAt(from - 1) == '.') || aNumberFollows(text, after);
        }
        String word = text.substring(from, dot).toLowerCase(java.util.Locale.ROOT);
        if (ABBREVIATIONS.contains(word)) return true;
        if (TITLES.contains(word)) return Character.isUpperCase(text.charAt(from));
        return BEFORE_A_NUMBER.contains(word) && aNumberFollows(text, after);
    }

    private static boolean aNumberFollows(String text, int after) {
        int next = after;
        while (next < text.length() && Character.isWhitespace(text.charAt(next)) && !lineBreak(text.charAt(next))) next++;
        return next < text.length() && Character.isDigit(text.charAt(next));
    }

    /**
     * Whether a trigger's words are all in one sentence of the text, in their order. Each word is
     * looked for once per sentence, after the one before it: if the first place a word is found
     * leaves no room for the next, no later place does. So the work grows with the text's length
     * and no faster -- one regex for the whole trigger, with a gap between its words, tried every
     * start against every end, and a pasted log of a few hundred kilobytes on one line held a
     * task's start for a minute.
     */
    static boolean inOneSentence(String trigger, String text, int[] sentences) {
        List<Pattern> words = words(trigger);
        var matchers = new java.util.regex.Matcher[words.size()];
        for (int i = 0; i < matchers.length; i++) {
            // Transparent: a word at the very start of what is searched still sees what is before it.
            matchers[i] = words.get(i).matcher(text).useTransparentBounds(true);
        }
        // Only the sentences the first word is in are read for the rest: where it is next is
        // found in the text as a whole, so a message of very many short sentences costs each
        // trigger one pass and not a search per sentence.
        var first = words.get(0).matcher(text);
        int from = 0;
        while (from <= text.length() && first.find(from)) {
            int s = sentenceEndingAfter(sentences, first.start());
            if (s < 0) return false;
            int at = sentences[s];
            boolean all = true;
            for (var matcher : matchers) {
                if (!matcher.region(at, sentences[s + 1]).find()) { all = false; break; }
                at = matcher.end();
            }
            if (all) return true;
            from = Math.max(sentences[s + 1], first.start() + 1);
        }
        return false;
    }

    /** The first sentence that ends after {@code position}, as its index in the pairs; -1 when none does. */
    private static int sentenceEndingAfter(int[] sentences, int position) {
        int low = 0, high = sentences.length / 2 - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (sentences[2 * mid + 1] > position) {
                found = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found < 0 ? -1 : 2 * found;
    }
}
