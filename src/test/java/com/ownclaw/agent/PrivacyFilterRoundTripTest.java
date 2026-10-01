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
                        .contains("For the cloud model: 2 secrets removed, 5 identifiers replaced."),
                String.valueOf(rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress'")));
        // Mutations: restore nothing in the gateway -> wifi_move runs with "<ssid_1>"; restore
        // the text but not the arguments -> the same; keep "credentials (N)" -> the read is a
        // descriptor and the cloud has no placeholder to use.
    }
}
