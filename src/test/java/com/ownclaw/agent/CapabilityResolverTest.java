package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.ContainerSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the resolver tells the model to build for a container run must be able to run there: the
 * container may connect, not capture (--cap-drop=NET_RAW), so no hint needs a raw or packet
 * socket.
 */
class CapabilityResolverTest {

    /** A resolver that sees a container runtime and no skills yet. */
    private static CapabilityResolver withContainer() {
        var container = new ContainerSandbox(new OwnClawConfig()) {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String runtime() {
                return "docker";
            }
        };
        return new CapabilityResolver(new ToolRegistry(List.of()), container);
    }

    @Test
    @DisplayName("a network scan in the container is an unprivileged one: TCP connect, no OS fingerprinting, no ARP ping")
    void aContainerScanIsUnprivileged() {
        var hint = withContainer().resolve("scan my network and find devices with open ports");
        assertNotNull(hint);
        assertEquals("network_scanning", hint.category());
        assertEquals(List.of("nmap", "net-tools"), hint.systemPackages());
        String d = hint.description();
        for (String said : List.of("always pass --unprivileged", "-sT -sV", "-Pn", "/proc/net/arp", "No -O, no -sS, no ARP ping")) {
            assertTrue(d.contains(said), "the hint does not say " + said + ": " + d);
        }
        for (String needsRaw : List.of("OS fingerprinting", "ping scan + ARP")) {
            assertFalse(d.contains(needsRaw), "the hint prescribes " + needsRaw + ": " + d);
        }
    }

    @Test
    @DisplayName("capturing packets is not something a skill can do: no hint sends the model to build one")
    void noPacketCapture() {
        for (String ask : List.of("capture packets on eth0", "run tcpdump for a minute", "sniff traffic on the LAN",
                "show me the network traffic")) {
            assertNull(withContainer().resolve(ask), ask);
        }
    }

    /** A resolver with no skills and no container: whatever sets a pattern off is a gap. */
    private static CapabilityResolver bare() {
        return new CapabilityResolver(new ToolRegistry(List.of()), null);
    }

    @Test
    @DisplayName("a trigger's words are found where a word begins, not inside another word or a skill's name")
    void triggersAreWordsNotSubstrings() {
        for (String message : List.of(
                // "port" in "report"
                "Open the attached file and send me a report.",
                // "new" in "news", "mail" in "email": a scheduled task that names its skills
                "Run daily_news_digest, then smtp_send_email with the result.",
                // "fetch" in "fetcher"
                "Run daily_menu_fetcher and email them to me.",
                // "trace" in "retrace"
                "Retrace the route we took last summer.")) {
            assertNull(bare().resolve(message), message);
        }
        // Mutation: asWords returning the trigger as written -> each of these asks for a skill.
    }

    @Test
    @DisplayName("a trigger's words are all in one sentence: a word in the next sentence is another matter")
    void triggersStayInOneSentence() {
        assertNull(bare().resolve("Check the weather for tomorrow. Then send it to my email."));
        assertNull(bare().resolve("Scan this page for typos!\nOur network team will review it."));
        assertNotNull(bare().resolve("Please scan the 192.168.5.0/24 network for me."), "a dot inside a word ends no sentence");
        // Mutation: SAME_SENTENCE as ".*" -> the first two ask for a skill.
    }

    @Test
    @DisplayName("what the patterns were written for still sets them off, stems and Czech included")
    void realRequestsStillMatch() {
        for (String message : List.of(
                "Scan my network and find devices with open ports",
                "check my email please",
                "Do I have any unread mail?",
                "Give me a summary of my emails",
                "convert this video to mp4",
                "Run a traceroute to example.org",
                "What are the DNS records of example.org?",
                "check the ssl certificate of example.org",
                "show me the cpu usage",
                "run this command: uptime",
                "resize the image to 800 pixels",
                "Zkontroluj mail, pros\u00edm",
                "skenuj dom\u00e1c\u00ed s\u00ed\u0165")) {
            assertNotNull(bare().resolve(message), message);
        }
        // Mutation: WORD_START also put after each word (whole words only) -> "emails", "records" fail.
    }
}
