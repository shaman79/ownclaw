package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    public CapabilityResolver(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    // ─────────────── Capability Pattern Definitions ───────────────

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
                            "scan.*network", "network.*scan", "find.*devices",
                            "discover.*hosts?", "open.*ports?", "port.*scan",
                            "\\bnmap\\b", "network.*discover",
                            "skenov.*síť", "skenuj.*síť", "najdi.*zařízení",  // Czech
                            "otevřené.*porty", "skenování.*sítě"
                    ),
                    "network_scanner",
                    "Scan local network using nmap: discover live hosts (ping scan + ARP), "
                            + "then scan each host for open ports with service/version detection (-sV). "
                            + "Identify device type via OS fingerprinting (-O) or service banners. "
                            + "Output structured results: IP, hostname, MAC, vendor, device type, open ports with service names.",
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

            // ── Packet capture ──
            new CapabilityPattern(
                    "packet_capture",
                    List.of("packet.*capture", "tcpdump", "sniff.*traffic", "network.*traffic",
                            "capture.*packets?"),
                    "packet_capture",
                    "Capture network packets on a specified interface using tcpdump. "
                            + "Filter by protocol, port, or host. Output summary or save pcap.",
                    List.of("tcpdump"),
                    List.of(),
                    "{ \"interface\": { \"type\": \"string\", \"description\": \"Network interface (e.g. eth0)\", \"required\": false }, "
                            + "\"filter\": { \"type\": \"string\", \"description\": \"BPF filter expression\", \"required\": false }, "
                            + "\"count\": { \"type\": \"string\", \"description\": \"Number of packets to capture\", \"required\": false } }",
                    60,
                    List.of("packet", "capture", "tcpdump", "sniff", "traffic")
            ),

            // ── Media processing (ffmpeg) ──
            new CapabilityPattern(
                    "media_processing",
                    List.of("convert.*video", "convert.*audio", "transcode", "\\bffmpeg\\b",
                            "extract.*audio", "video.*to.*mp[34]", "compress.*video",
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
                            "markdown.*to.*pdf", "html.*to.*pdf", "document.*convert"),
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
                            "system.*monitor", "\\bhtop\\b", "\\btop\\b.*process",
                            "running.*process", "system.*health"),
                    "system_info",
                    "Gather system information: CPU, memory, disk usage, running processes, "
                            + "network interfaces, uptime. Uses standard Linux tools.",
                    List.of("procps", "net-tools", "iproute2"),
                    List.of("psutil"),
                    "{ \"category\": { \"type\": \"string\", \"description\": \"Category: cpu, memory, disk, network, processes, all\", \"required\": false } }",
                    30,
                    List.of("system", "info", "cpu", "memory", "disk", "process", "monitor")
            ),

            // ── SSL/TLS certificate check ──
            new CapabilityPattern(
                    "ssl_check",
                    List.of("ssl.*cert", "tls.*cert", "certificate.*check", "certificate.*expir",
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
                    List.of("run.*command", "execute.*command", "shell.*command",
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
            )
    );

    // ─────────────── Core Resolution Logic ───────────────

    /**
     * Analyze a user message and detect capability gaps.
     *
     * @param userMessage the user's original request
     * @return a capability hint if a gap was detected, or {@code null} if
     *         existing skills cover the request (or no known pattern matches)
     */
    public CapabilityHint resolve(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return null;

        String normalized = userMessage.toLowerCase();

        // 1. Match against known capability patterns
        CapabilityPattern matched = null;
        for (CapabilityPattern pattern : CAPABILITY_PATTERNS) {
            if (pattern.matches(normalized)) {
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
            log.debug("Capability '{}' covered by existing skill '{}' — no hint needed",
                    matched.category, existingSkill);
            return null;
        }

        // 3. Gap detected — produce a specific hint
        log.info("Capability gap detected: '{}' → suggesting skill_create with system_packages={}",
                matched.category, matched.systemPackages);

        return new CapabilityHint(
                matched.category,
                matched.suggestedName,
                matched.description,
                matched.systemPackages,
                matched.pipPackages,
                matched.parameters,
                matched.timeout
        );
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
            int timeout
    ) {
        /**
         * Render this hint as a directive for the LLM system prompt.
         * This is designed to be a clear, unambiguous instruction that even
         * a small model will follow correctly.
         */
        public String toPromptDirective() {
            var sb = new StringBuilder();
            sb.append("## ACTION REQUIRED — Create Skill\n");
            sb.append("Your task requires ").append(category.replace('_', ' '));
            sb.append(" capability, but NO existing tool provides this.\n");
            sb.append("You MUST use skill_create as your FIRST action with these parameters:\n\n");
            sb.append("```\n");
            sb.append("tool: skill_create\n");
            sb.append("params:\n");
            sb.append("  name: \"").append(suggestedName).append("\"\n");
            sb.append("  description: \"").append(description).append("\"\n");
            sb.append("  parameters: '").append(parametersJson).append("'\n");
            if (!systemPackages.isEmpty()) {
                sb.append("  system_packages: \"").append(String.join(" ", systemPackages)).append("\"\n");
            }
            if (!pipPackages.isEmpty()) {
                sb.append("  requirements: \"").append(String.join("\n", pipPackages)).append("\"\n");
            }
            sb.append("  timeout: ").append(timeout).append("\n");
            sb.append("```\n\n");
            sb.append("DO NOT refuse. DO NOT say tools are missing. ");
            sb.append("The system_packages are auto-installed inside a Docker container. ");
            sb.append("Just execute skill_create with the parameters above.\n");
            return sb.toString();
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
            List<String> matchKeywords
    ) {
        /** Compiled patterns — lazily cached per instance. */
        boolean matches(String normalizedInput) {
            for (String trigger : triggerPatterns) {
                try {
                    if (Pattern.compile(trigger, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(normalizedInput).find()) {
                        return true;
                    }
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
}
