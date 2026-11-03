package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.observability.TaskTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Redact and release, through the real loop, gateway and database: a router's configuration read
 * with a password goes to the cloud whole but for its secrets and identifiers, the cloud acts on
 * placeholders, and everything on this machine -- the tool, the owner's answer, the stored chat
 * row -- has the real values. Every value is made up.
 */
class PrivacyFilterRoundTripTest {

    static final String PASSWORD = "fake-router-login-77";

    static final String UCI = String.join("\n",
            "config wifi-iface 'default_radio0'",
            "\toption ssid 'Fake Kolibri'",
            "\toption encryption 'psk2'",
            "\toption key 'fake-wifi-key-42'",
            "",
            "config host",
            "\toption name 'fake-laptop'",
            "\toption mac '00:00:5e:00:53:01'",
            "\toption ip '10.0.0.20'",
            "",
            "config host",
            "\toption name 'fake-printer'",
            "\toption mac '00:00:5e:00:53:02'",
            "\toption ip '10.0.0.21'",
            "# read as root / " + PASSWORD);

    /** Every value the cloud must never be sent. */
    static final List<String> REAL = List.of("Fake Kolibri", "fake-wifi-key-42", "fake-laptop",
            "00:00:5e:00:53:01", "fake-printer", "00:00:5e:00:53:02", PASSWORD);

    static Tool tool(String name, List<String> credentials, boolean changes, List<Map<String, Object>> calls,
                     String output) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test skill " + name; }
            public Map<String, ToolParam> inputSchema() { return Map.of(); }
            public List<String> requiredCredentials() { return credentials; }
            public boolean hasSideEffects() { return changes; }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                calls.add(Map.copyOf(p));
                return ToolResult.success(output);
            }
        };
    }

    /** The placeholder the cloud was shown in the last request for what {@code pattern}'s group 1 matched. */
    static String shown(LoopRig rig, String pattern) {
        var last = rig.cloud.calls.get(rig.cloud.calls.size() - 1);
        Matcher m = Pattern.compile(pattern).matcher(String.join("\n", LoopRig.userParts(last)));
        assertTrue(m.find(), pattern + " in " + LoopRig.userParts(last));
        return m.group(1);
    }

    static List<String> allParts(LoopRig.Call call) {
        var out = new ArrayList<String>();
        for (LlmMessage m : call.messages()) out.add(m.content());
        if (call.config().tools() != null) {
            for (var t : call.config().tools()) {
                out.add(t.description());
                out.add(String.valueOf(t.inputSchema()));
            }
        }
        return out;
    }

    @Test
    @DisplayName("a credentialed router read reaches the cloud whole but for secrets and identifiers, and the cloud's placeholders come back as the real values")
    void roundTrip(@TempDir Path tmp) throws Exception {
        var reads = new CopyOnWriteArrayList<Map<String, Object>>();
        var sets = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(
                tool("router_read", List.of("OPENWRT_USER", "OPENWRT_PASS"), false, reads, UCI),
                tool("wifi_move", List.of("OPENWRT_USER", "OPENWRT_PASS"), true, sets, "moved")));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
        rig.vault.storeCredential("u1", "OPENWRT_PASS", PASSWORD);
        String session = rig.chat.createSession("u1", "Network");
        String[] seen = new String[2];

        rig.cloud.think.add(call("router_read", Map.of()));
        rig.cloud.think.add(c -> {
            seen[0] = shown(rig, "option ssid '(<ssid_\\d+>)'");
            seen[1] = shown(rig, "option mac '(<mac_\\d+>)'");
            return call("wifi_move", Map.of("ssid", seen[0], "mac", seen[1])).answer(c);
        });
        rig.cloud.think.add(c -> respond("Moved " + seen[1] + " to " + seen[0] + ".").answer(c));

        AgentResult r = rig.turn(session, "move my laptop to the main network");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var task = rig.cloud.calls("think");
        // The router's configuration, as the cloud read it: whole, but for what the filter takes.
        String read = String.join("\n", LoopRig.userParts(task.get(1)));
        assertTrue(read.contains("\toption encryption 'psk2'") && read.contains("\toption ip '10.0.0.20'"), read);
        assertTrue(read.contains("\toption key '«secret removed»'"), read);
        assertTrue(read.contains("# read as root / «vault:OPENWRT_PASS»"), read);
        assertTrue(read.matches("(?s).*option name '<host_\\d+>'.*option name '<host_\\d+>'.*"), read);
        assertFalse(read.contains("PRIVATE"), "a credential alone does not make the result private: " + read);
        // The cloud's call ran with the real values...
        assertEquals(List.of(Map.of("ssid", "Fake Kolibri", "mac", "00:00:5e:00:53:01")), sets);
        // ...and the owner reads them, on the screen and in the stored chat row.
        assertEquals("Moved 00:00:5e:00:53:01 to Fake Kolibri.", r.response());
        assertTrue(rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'assistant'", String.class)
                .contains("Moved 00:00:5e:00:53:01 to Fake Kolibri."));
        // No request carried a real value, in any part: the replayed call is placeholders again.
        for (var c : task) {
            for (String part : allParts(c)) {
                for (String real : REAL) assertFalse(part.contains(real), "[" + real + "] sent in: " + part);
            }
        }
        String replayed = String.join("\n", task.get(2).messages().stream().map(LlmMessage::content).toList());
        assertTrue(replayed.contains(seen[0]) && replayed.contains(seen[1]), replayed);

        // The next task: the same values, the same placeholders, in its history.
        rig.cloud.think.add(respond("ok"));
        rig.turn(session, "and the printer?");
        String next = String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(3)));
        assertTrue(next.contains("Moved " + seen[1] + " to " + seen[0] + "."), next);
        for (String real : REAL) assertFalse(next.contains(real), next);

        // The ledger counts what the filter did, and the task page shows it.
        var trace = new TaskTraceService(rig.events).trace("u1", r.taskId()).orElseThrow();
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Object>) trace.get("totals");
        assertTrue(((Number) totals.get("scrubs")).intValue() >= 2, totals.toString());
        assertTrue(((Number) totals.get("identifiers")).intValue() >= 5, totals.toString());
        // And the owner's chat says it, after the step whose result it changed.
        assertTrue(rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress'", String.class)
                        .contains("🔒 For the cloud model: 2 secrets removed, 5 identifiers replaced."),
                String.valueOf(rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress'")));
        // Mutations: restore nothing in the gateway -> wifi_move runs with "<ssid_1>"; restore
        // the text but not the arguments -> the same; keep "credentials (N)" -> the read is a
        // descriptor and the cloud has no placeholder to use.
    }

    @Test
    @DisplayName("every vault value stored under a secret's name is removed from what the cloud reads: a WiFi key, a WireGuard key, a PIN")
    void vaultValuesUnderEverySecretNameAreRemoved(@TempDir Path tmp) throws Exception {
        String psk = "fake-psk-value-31", wg = "RmFrZVdnUHJpdmF0ZUtleUZvclRlc3RzMDAwMDAwMDA=", pin = "4821";
        // Printed where no name marks them: a QR code's text, prose, a log line.
        String printed = "QR payload: WIFI:T:WPA;S:Fake Kolibri;P:" + psk + ";;\npeer configured with " + wg
                + "\nunlock code accepted: " + pin;
        var rig = new LoopRig(tmp, List.of(tool("router_qr", List.of("ROUTER_HOST", "WIFI_PSK", "WG_PRIVATE_KEY",
                "ROUTER_PIN"), false, new CopyOnWriteArrayList<>(), printed)));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        rig.vault.storeCredential("u1", "ROUTER_HOST", "10.0.0.1");
        rig.vault.storeCredential("u1", "WIFI_PSK", psk);
        rig.vault.storeCredential("u1", "WG_PRIVATE_KEY", wg);
        rig.vault.storeCredential("u1", "ROUTER_PIN", pin);
        String session = rig.chat.createSession("u1", "Network");
        rig.cloud.think.add(call("router_qr", Map.of()));
        rig.cloud.think.add(respond("done"));

        rig.turn(session, "show me the guest network's QR code");

        var task = rig.cloud.calls("think");
        String read = String.join("\n", LoopRig.userParts(task.get(1)));
        assertTrue(read.contains("P:«vault:WIFI_PSK»;;") && read.contains("peer configured with «vault:WG_PRIVATE_KEY»")
                && read.contains("unlock code accepted: «vault:ROUTER_PIN»"), read);
        for (var c : task) {
            for (String part : allParts(c)) {
                for (String v : List.of(psk, wg, pin)) assertFalse(part.contains(v), "[" + v + "] sent in: " + part);
            }
        }
        // Mutation: decrypt only the keys the old pattern named (pass|token|secret...) -> all three sent.
    }

    @Test
    @DisplayName("a placeholder the cloud uses as a key of an argument comes back as the real value")
    void placeholderKeysAreRestored(@TempDir Path tmp) throws Exception {
        var assigned = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(
                tool("router_read", List.of("OPENWRT_PASS"), false, new CopyOnWriteArrayList<>(), UCI),
                tool("assign_vlans", List.of("OPENWRT_PASS"), true, assigned, "assigned")));
        String session = rig.chat.createSession("u1", "Network");
        rig.cloud.think.add(call("router_read", Map.of()));
        rig.cloud.think.add(c -> call("assign_vlans", Map.of(
                "vlan_by_mac", Map.of(shown(rig, "option mac '(<mac_\\d+>)'"), 20),
                "hosts", List.of(Map.of(shown(rig, "option name '(<host_\\d+>)'"), "guest")))).answer(c));
        rig.cloud.think.add(respond("ok"));

        rig.turn(session, "put the laptop on VLAN 20");

        assertEquals(List.of(Map.of("vlan_by_mac", Map.of("00:00:5e:00:53:01", 20),
                "hosts", List.of(Map.of("fake-laptop", "guest")))), assigned);
        // Mutation: restore the values of a map and not its keys -> the tool is handed <mac_1>.
    }

    @Test
    @DisplayName("an SSID that json.dumps escaped comes back as the SSID itself, to the tool and to the owner")
    void escapedValuesComeBackAsTheyRead(@TempDir Path tmp) throws Exception {
        var moves = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(
                tool("wifi_status", List.of(), false, new CopyOnWriteArrayList<>(),
                        "{\"ssid\": \"Kav\\u00e1rna Fake\", \"clients\": 3}"),
                tool("wifi_move", List.of(), true, moves, "moved")));
        String session = rig.chat.createSession("u1", "Network");
        String[] ssid = new String[1];
        rig.cloud.think.add(call("wifi_status", Map.of()));
        rig.cloud.think.add(c -> {
            ssid[0] = shown(rig, "\"ssid\": \"(<ssid_\\d+>)\"");
            return call("wifi_move", Map.of("ssid", ssid[0])).answer(c);
        });
        rig.cloud.think.add(c -> respond("Moved the laptop to " + ssid[0] + ".").answer(c));

        AgentResult r = rig.turn(session, "move the laptop to the café network");

        assertEquals(List.of(Map.of("ssid", "Kavárna Fake")), moves);
        assertEquals("Moved the laptop to Kavárna Fake.", r.response());
        // Mutation: keep a value as it is written -> the tool is handed Kav\u00e1rna Fake.
    }

    @Test
    @DisplayName("a call that writes back a secret the filter removed is refused, and the tool never runs")
    void aRemovedSecretIsNeverWrittenBack(@TempDir Path tmp) throws Exception {
        var writes = new CopyOnWriteArrayList<Map<String, Object>>();
        var rig = new LoopRig(tmp, List.of(
                tool("router_read", List.of("OPENWRT_PASS"), false, new CopyOnWriteArrayList<>(), UCI),
                tool("router_write_wireless", List.of("OPENWRT_PASS"), true, writes, "written")));
        String session = rig.chat.createSession("u1", "Network");
        rig.cloud.think.add(call("router_read", Map.of()));
        rig.cloud.think.add(c -> {
            // The section as the cloud was shown it, with only the encryption changed.
            String read = String.join("\n", LoopRig.userParts(rig.cloud.calls.get(rig.cloud.calls.size() - 1)));
            String section = read.substring(read.indexOf("config wifi-iface"), read.indexOf("config host"))
                    .replace("psk2", "sae-mixed");
            return call("router_write_wireless", Map.of("config", section)).answer(c);
        });
        rig.cloud.think.add(respond("ok"));

        rig.turn(session, "switch the main network to WPA3");

        assertTrue(writes.isEmpty(), "the router's WiFi key would have become the marker: " + writes);
        String told = String.join("\n", LoopRig.userParts(rig.cloud.calls("think").get(2)));
        assertTrue(told.contains("Not run: an argument holds a removed secret."), told);
        // Mutation: no check before the call -> the tool runs with option key '«secret removed»'.
    }
}
