package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.llm.LlmProgress.Part;
import com.ownclaw.llm.SilentOllama;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A task's model calls as the owner sees them while they run, through the real loop: the live
 * state sent to his chat while a call is under way and never after it, what a page that connects
 * meanwhile is told, what the ops API reads -- and a reply that has become a loop, ended, with the
 * work handed on and the owner told. The local model is the real Ollama provider over a scripted
 * stream; the cloud is scripted.
 */
class LiveCallTaskTest {

    static final Tool NOOP = tool("noop", List.of(), p -> "nothing to report");

    static String session(LoopRig rig) {
        return rig.chat.createSession("u1", "Network");
    }

    /** The chat's progress rows, oldest first. */
    static List<String> rows(LoopRig rig) {
        return rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress' ORDER BY id",
                String.class);
    }

    static List<StatusMessage> live(List<StatusMessage> statuses) {
        return statuses.stream().filter(s -> s.type() == StatusMessage.Type.LIVE).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> state(StatusMessage live) {
        return (Map<String, Object>) live.data().get("live");
    }

    /**
     * The production incident's shape, scaled down: a thinking model that reasons for a while,
     * then writes the same paragraph over and over -- 60 times here; on 2026-10-08 it would have
     * gone on until its 262,144-token window was full.
     */
    static String loopingReply() {
        var thinking = new ArrayList<String>();
        for (int i = 1; i <= 40; i++) thinking.add("Step " + i + " of the audit: router " + i + " answers on 192.0.2." + i + ".\n");
        for (int i = 0; i < 60; i++) {
            thinking.add("Wait, I need to check the configuration again. The interface eth0 has the address ");
            thinking.add("192.0.2.1 and the gateway is 192.0.2.254, so the route should be fine. But the user asked ");
            thinking.add("about the second router, so let me look at its configuration once more before I decide ");
            thinking.add("which command to run next. Actually, let me reconsider the whole thing.\n");
        }
        return SilentOllama.reasons(thinking, "{\"done\": true, \"summary\": \"never reached\"}");
    }

    static final String SEEN = "the local model repeated the same 300 characters 4 times, after ";

    @Test
    @DisplayName("a local model that loops in a delegation is ended: the delegation fails, the cloud takes over, and the chat says so")
    void aLoopingDelegationFailsOver(@TempDir Path tmp) throws Exception {
        var ollama = new SilentOllama(0, loopingReply());
        var rig = new LoopRig(tmp, List.of(NOOP), 600, ollama.provider());
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "audit both routers")));
        rig.cloud.think.add(respond("Both routers answer; I checked them myself."));
        var statuses = rig.statuses();

        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Both routers answer; I checked them myself.", r.response());
        assertEquals(1, ollama.chats(), "the looping call was ended, not asked again");
        assertTrue(ollama.closedEveryReply(), "and its stream closed: Ollama stops generating");
        String told = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(told.contains("Delegation incomplete: Local LLM call failed: [ollama] " + SEEN), told);
        assertTrue(told.contains("characters of reasoning"), told);

        var note = rows(rig).stream().filter(row -> row.startsWith("🔁")).toList();
        assertEquals(1, note.size(), rows(rig).toString());
        assertTrue(note.get(0).startsWith("🔁 Step 1, delegation turn 1: " + SEEN), note.get(0));
        assertTrue(note.get(0).endsWith(" characters of reasoning. It was repeating itself, so its reply was "
                + "ended, and the cloud model takes over."), note.get(0));

        var frames = live(statuses);
        var turn = frames.stream().filter(f -> "step 1, delegation turn 1".equals(state(f).get("purpose"))).toList();
        assertFalse(turn.isEmpty(), "the delegation's call was shown as it ran: " + frames);
        assertEquals(true, state(turn.get(turn.size() - 1)).get("ended"), "and as it ended");
        // Mutation: let the delegation go on to its next turn after a loop -> the cloud is never
        // told, and the script has no reply for the second local call.
    }

    @Test
    @DisplayName("a task on the local model alone: the step whose reply looped runs nothing, the owner is told, and the step is asked again")
    void aLoopingStepOnTheLocalModel(@TempDir Path tmp) throws Exception {
        var ollama = new SilentOllama(0, loopingReply(),
                SilentOllama.says("{\"tool\": \"respond\", \"params\": {\"message\": \"Both routers answer.\"}}"));
        var rig = new LoopRig(tmp, List.of(NOOP), 600, ollama.provider());
        rig.cloud.available = false;         // the local model does the thinking

        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(r.response().contains("Both routers answer."), r.response());
        assertEquals(2, ollama.chats(), "asked again after the loop");
        String second = ollama.bodies().get(1);
        assertTrue(second.contains("Your previous reply never came: the call to the model failed ([ollama] " + SEEN),
                "the model is told why its step ran nothing: " + second);
        var note = rows(rig).stream().filter(row -> row.startsWith("🔁")).toList();
        assertEquals(1, note.size(), rows(rig).toString());
        assertTrue(note.get(0).startsWith("🔁 Step 1: " + SEEN), note.get(0));
        assertTrue(note.get(0).endsWith("It was repeating itself, so its reply was ended, and the step ran nothing."),
                note.get(0));
    }

    @Test
    @DisplayName("a long local reply that quotes a config whose block repeats twice is no loop: the delegation goes on and finishes")
    void aLongReplyWithARepeatedBlockGoesOn(@TempDir Path tmp) throws Exception {
        String block = "config interface 'guest'\n\toption proto 'static'\n\toption ipaddr '192.0.2.1'\n"
                + "\toption netmask '255.255.255.0'\n\toption type 'bridge'\n\toption ifname 'eth0.3'\n"
                + "\toption mtu '1500'\n\toption ip6assign '60'\n\toption delegate '0'\n\n"
                + "config zone\n\toption name 'guest'\n\tlist network 'guest'\n\toption input 'REJECT'\n"
                + "\toption output 'ACCEPT'\n\toption forward 'REJECT'\n\n";
        var thinking = new ArrayList<String>();
        for (int part = 0; part < 3; part++) {
            for (int i = 1; i <= 150; i++) {
                thinking.add("Part " + part + ", line " + i + ": the router at 192.0.2." + i + " has "
                        + (i * 7919 % 10007) + " routes.\n");
            }
            for (int at = 0; at < block.length(); at += 37) thinking.add(block.substring(at, Math.min(block.length(), at + 37)));
        }
        var ollama = new SilentOllama(0, SilentOllama.reasons(thinking, "{\"tool\": \"noop\", \"params\": {}}"),
                SilentOllama.says("{\"done\": true, \"summary\": \"Both routers answer.\"}"));
        var rig = new LoopRig(tmp, List.of(NOOP), 600, ollama.provider());
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("goal", "audit both routers")));
        rig.cloud.think.add(respond("Both routers answer."));

        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(2, ollama.chats(), "the long reply was read to its end, and the delegation went on");
        String told = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(told.contains("Both routers answer."), told);
        assertFalse(told.contains("repeated the same"), told);
        assertTrue(rows(rig).stream().noneMatch(row -> row.startsWith("🔁")), rows(rig).toString());
    }

    @Test
    @DisplayName("a cloud reply that loops is ended the same way: the step runs nothing, the owner is told, the step is asked again")
    void aLoopingCloudReply(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        String looping = "| eth0 | up | 192.0.2.1 | 1500 | ok |\n".repeat(500);
        rig.cloud.think.add(c -> {
            for (int at = 0; at < looping.length(); at += 29) {
                c.progress().onProgress();
                c.progress().received(Part.ANSWER, looping.substring(at, Math.min(looping.length(), at + 29)));
            }
            return respond("never reached").answer(c);
        });
        rig.cloud.think.add(respond("Both routers answer."));

        AgentResult r = rig.turn(session(rig), "audit the routers");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Both routers answer.", r.response());
        String told = String.join("\n", userParts(rig.cloud.calls("think").get(1)));
        assertTrue(told.contains("Your previous reply never came: the call to the model failed ([anthropic] the "
                + "cloud model repeated the same 300 characters 4 times, after "), told);
        var note = rows(rig).stream().filter(row -> row.startsWith("🔁")).toList();
        assertEquals(1, note.size(), rows(rig).toString());
        assertTrue(note.get(0).startsWith("🔁 Step 1: the cloud model repeated the same 300 characters 4 times"),
                note.get(0));
        assertTrue(note.get(0).endsWith("characters of its answer. It was repeating itself, so its reply was ended, "
                + "and the step ran nothing."), note.get(0));
    }

    /**
     * A cloud call that streams reasoning for {@code events} events of 40 ms each, a line each,
     * then calls noop; {@code during} runs on the call's thread after the third event.
     */
    static Reply reasoning(int events, Runnable during) {
        return c -> {
            for (int i = 1; i <= events; i++) {
                Thread.sleep(40);
                c.progress().onProgress();
                c.progress().received(Part.REASONING, "Checking router " + i + " of " + events + ".\n");
                if (i == 3) during.run();
            }
            return call("noop", Map.of()).answer(c);
        };
    }

    @Test
    @DisplayName("while a call runs its chat is sent its live state, in place, from its start; once it has ended, nothing more")
    void theLiveStateWhileACallRuns(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.loop.liveEveryMs = 50;
        var statuses = rig.statuses();
        var doingMidCall = new AtomicReference<StatusMessage>();
        var opsMidCall = new AtomicReference<Map<String, Object>>();
        String[] taskId = new String[1];
        rig.cloud.think.add(c -> {
            taskId[0] = c.egress().taskId();
            return reasoning(12, () -> {
                doingMidCall.set(rig.emitter.doing("u1"));
                opsMidCall.set(rig.loop.liveCallOf(taskId[0]));
            }).answer(c);
        });
        rig.cloud.think.add(respond("Both routers answer."));

        AgentResult r = rig.turn(session(rig), "check the routers");
        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());

        var frames = live(statuses);
        var first = frames.stream().filter(f -> "step 1".equals(state(f).get("purpose"))).toList();
        assertTrue(first.size() >= 5, "sent as it began, every 50 ms of some 500, and as it ended: " + first.size());
        assertEquals(false, state(first.get(0)).get("arrived"), "the first frame is sent as the call begins");
        assertEquals("☁️ Cloud model · step 1 · waiting for its first words · 0s", first.get(0).text());
        var reasoning = first.stream().filter(f -> "reasoning".equals(state(f).get("phase"))).toList();
        assertFalse(reasoning.isEmpty(), "frames while it reasons: " + first);
        String text = reasoning.get(reasoning.size() - 1).text();
        assertTrue(text.matches("☁️ Cloud model · step 1 · reasoning · [0-9]+s · [0-9,]+ characters so far · "
                + "“Checking router [0-9]+ of 12\\.”"), text);
        var last = first.get(first.size() - 1);
        assertEquals(true, state(last).get("ended"));
        assertTrue(last.text().matches("☁️ Cloud model · step 1 · ended after [0-9]+s · [0-9,]+ characters"),
                last.text());
        assertEquals(1, first.stream().filter(f -> Boolean.TRUE.equals(state(f).get("ended"))).count(),
                "one frame says it ended, and it is the last of the call's");
        for (var f : frames) assertEquals(taskId[0], f.taskId(), "every frame is the task's");
        assertNull(frames.get(0).data().get(ChatStatusEmitter.BACKGROUND), "attended work is not marked");

        int sent = statuses.size();
        int liveSent = frames.size();
        Thread.sleep(300);
        assertEquals(liveSent, live(statuses).size(), "nothing after the calls ended: " + statuses.subList(sent, statuses.size()));

        // A page that connected mid-call was told the call's state as it then stood...
        assertNotNull(doingMidCall.get());
        assertEquals(StatusMessage.Type.LIVE, doingMidCall.get().type());
        assertEquals("reasoning", state(doingMidCall.get()).get("phase"));
        assertEquals("Checking router 3 of 12.", state(doingMidCall.get()).get("line"));
        // ...and the ops API read it, with the end of its reasoning.
        assertNotNull(opsMidCall.get());
        assertEquals("step 1", opsMidCall.get().get("purpose"));
        assertTrue(((String) opsMidCall.get().get("reasoningTail")).endsWith("Checking router 3 of 12.\n"),
                opsMidCall.get().toString());
        // Once the task has ended: no call under way, and nothing kept for a page.
        assertNull(rig.loop.liveCallOf(taskId[0]));
        assertNull(rig.emitter.doing("u1"));
        // Mutation: schedule only the first frame -> a few frames for half a second of reasoning.
    }

    @Test
    @DisplayName("between two calls, a page that connects is told the step the task is on: never nothing")
    void betweenCallsTheStepIsShown(@TempDir Path tmp) throws Exception {
        var seen = new AtomicReference<StatusMessage>();
        var emitter = new AtomicReference<ChatStatusEmitter>();
        var probe = tool("probe", List.of(), p -> {
            seen.set(emitter.get().doing("u1"));
            return "probed";
        });
        var rig = new LoopRig(tmp, List.of(probe));
        emitter.set(rig.emitter);
        rig.cloud.think.add(call("probe", Map.of()));
        rig.cloud.think.add(respond("Done."));

        AgentResult r = rig.turn(session(rig), "probe it");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertNotNull(seen.get(), "a task running a skill is doing something");
        assertEquals(StatusMessage.Type.STEP, seen.get().type());
        assertEquals("Running probe...", seen.get().text(), "the status it sent last");
        // Mutation: keep nothing but the live call -> null while the skill runs.
    }

    @Test
    @DisplayName("the live state of unattended work is marked as its own, and a page is not told of it as going on")
    void unattendedLiveStateIsBackground(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.loop.liveEveryMs = 50;
        var statuses = rig.statuses();
        var doingMidCall = new AtomicReference<StatusMessage>(new StatusMessage(StatusMessage.Type.STEP, "unset"));
        rig.cloud.think.add(reasoning(6, () -> doingMidCall.set(rig.emitter.doing("u1"))));
        rig.cloud.think.add(respond("Both routers answer."));

        AgentResult r = rig.loop.executeFull("u1", "check the routers", true);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var frames = live(statuses);
        assertTrue(frames.size() >= 3, frames.toString());
        for (var f : frames) {
            assertEquals(true, f.data().get(ChatStatusEmitter.BACKGROUND), "marked: " + f);
        }
        assertNull(doingMidCall.get(), "nobody's working state");
        // Mutation: send the frames unmarked -> they fill the open chat's strip and start its spinner.
    }
}
