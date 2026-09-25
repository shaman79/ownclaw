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
}
