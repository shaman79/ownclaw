package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.Replies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A task with no cloud model to call: the owner switched it off (local only), or it cannot be
 * reached -- no internet, a rejected key, no credit, an outage the retries did not outlast. The
 * task goes on on the local model, says so, and the next one tries the cloud again. Until this,
 * a cloud with a key set counted as available whatever happened to it, so every step failed, was
 * asked again, and three of those ended the task.
 */
class LocalModeTest {

    static final Tool NOOP = tool("noop", List.of(), p -> "nothing to report");

    /** A scripted local model: think calls answered from {@code think}, code from {@code codegen}. */
    static Cloud localModel() {
        var local = new Cloud();
        local.name = "ollama";
        return local;
    }

    /** The cloud cannot be reached: the name does not resolve. */
    static Reply noInternet() {
        return c -> {
            throw new LlmException("anthropic", "Connection failed: api.anthropic.com", 0,
                    new UnknownHostException("api.anthropic.com"));
        };
    }

    static String session(LoopRig rig) {
        return rig.chat.createSession("u1", "Network");
    }

    static List<String> rows(LoopRig rig) {
        return rig.jdbc.queryForList("SELECT content FROM conversations WHERE role = 'progress' ORDER BY id",
                String.class);
    }

    static String all(Call call) {
        return call.messages().stream().map(LlmMessage::content).collect(Collectors.joining("\n"));
    }

    static final String NO_INTERNET = "no connection to it, and the internet may be down";

    @Test
    @DisplayName("with no internet the task goes on on the local model, saying so; the next task tries the cloud again")
    void noInternetGoesLocal(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(NOOP), 600, local);
        rig.cloud.think.add(noInternet());
        rig.cloud.think.add(noInternet());
        local.think.add(call("noop", Map.of()));
        local.think.add(respond("done locally"));
        String session = session(rig);

        AgentResult r = rig.turn(session, "check the network");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("done locally\n\n_🏠 Written by the local model: the cloud model could not be used -- "
                + NO_INTERNET + "._", r.response());
        assertEquals(2, rig.cloud.calls("think").size(), "asked once more, then handed over");
        var asked = local.calls("think");
        assertEquals(2, asked.size());
        String prompt = all(asked.get(0));
        assertTrue(prompt.contains("- Model: you are the local model, running this task on your own: the cloud "
                + "model could not be used -- " + NO_INTERNET + "."), prompt);
        assertFalse(prompt.contains("written as placeholders"), "no gateway stands between it and the results");
        assertFalse(prompt.contains("delegate it (free, stays on the host)"), "it would delegate to itself");
        assertFalse(prompt.contains("Delegate where the local model is the right tool"), prompt);
        List<String> rows = rows(rig);
        assertTrue(rows.contains("🏠 The cloud model (anthropic) can't be used -- " + NO_INTERNET + ". The rest "
                + "of this task runs on the local model: slower, and best effort."), String.valueOf(rows));
        assertTrue(rows.stream().anyMatch(c -> c.startsWith("🏠 Step 1 · noop")),
                "the step the cloud could not take is the local model's step 1: " + rows);

        rig.cloud.think.add(respond("back on the cloud"));
        assertEquals("back on the cloud", rig.turn(session, "and now?").response());
        assertEquals(3, rig.cloud.calls("think").size(), "the next task asked the cloud first");
    }

    @Test
    @DisplayName("a connection that breaks off once is asked again on the cloud, and a reply sets the count back")
    void aBreakIsAskedAgain(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(NOOP), 600, local);
        rig.cloud.think.add(noInternet());
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(noInternet());
        rig.cloud.think.add(respond("done"));

        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals("done", r.response());
        assertEquals(4, rig.cloud.calls("think").size());
        assertTrue(local.calls.isEmpty(), "the task never left the cloud");
        assertTrue(rows(rig).stream().noneMatch(c -> c.contains("can't be used")), String.valueOf(rows(rig)));
    }

    @Test
    @DisplayName("a key the cloud rejects, no credit, or an outage the retries did not outlast hands the task over at once")
    void notServedGoesLocalAtOnce(@TempDir Path tmp) throws Exception {
        record Case(LlmException e, String because) {}
        var cases = List.of(
                new Case(new LlmException("anthropic", "HTTP 401: invalid x-api-key", 401, null),
                        "it rejected the API key (HTTP 401)"),
                new Case(new LlmException("anthropic", "HTTP 400: {\"error\":{\"message\":\"Your credit balance is "
                        + "too low to access the Anthropic API.\"}}", 400, null), "the account has no credit left"),
                new Case(new LlmException("anthropic", "HTTP 529: overloaded", 529, null),
                        "it kept failing (HTTP 529) however long it was given"));
        int n = 0;
        for (var c : cases) {
            var local = localModel();
            var rig = new LoopRig(tmp.resolve("c" + n++), List.of(), 600, local);
            rig.cloud.think.add(x -> { throw c.e(); });
            local.think.add(respond("done locally"));

            assertTrue(rig.turn(session(rig), "check the network").response().startsWith("done locally\n\n_🏠"));
            assertEquals(1, rig.cloud.calls("think").size(), c.because());
            assertTrue(rows(rig).contains("🏠 The cloud model (anthropic) can't be used -- " + c.because()
                    + ". The rest of this task runs on the local model: slower, and best effort."), String.valueOf(rows(rig)));
        }
    }

    @Test
    @DisplayName("a request the cloud read and rejected is no reason to change models: the task ends saying why")
    void aRejectedRequestStaysAnEnding(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.cloud.think.add(c -> { throw new LlmException("anthropic", "HTTP 400: messages: field required", 400, null); });

        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(local.calls.isEmpty(), "sent to the local model, the task would only hide the fault");
    }

    @Test
    @DisplayName("local only: no cloud model is called, and the local model is told why it runs the task alone")
    void localOnly(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(NOOP), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(call("noop", Map.of()));
        local.think.add(respond("done locally"));

        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals("done locally", r.response());
        assertTrue(rig.cloud.calls.isEmpty(), "the cloud model was called: " + rig.cloud.calls);
        assertTrue(all(local.calls("think").get(0)).contains("- Model: you are the local model, running this "
                + "task on your own: the owner has switched the cloud model off."));
        assertTrue(rows(rig).stream().noneMatch(c -> c.contains("can't be used")), "nothing failed");
    }

    @Test
    @DisplayName("local only with no local model: the task ends saying so, and how to switch the cloud back on")
    void localOnlyWithNoLocalModel(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(c -> {
            throw new LlmException("ollama", "Connection failed: refused", 0, new ConnectException("refused"));
        });

        AgentResult r = rig.turn(session(rig), "check the network");

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** the local model (ollama) could not be reached -- no "
                + "connection to it -- and the cloud model is switched off (the owner's /local off switches it "
                + "on).\n\n"),
                r.response());
        assertTrue(rig.cloud.calls.isEmpty());
    }

    @Test
    @DisplayName("the code a skill needs is written on the local model when the cloud cannot be reached, and the task stays there")
    void codeIsWrittenLocally(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.cloud.codegen.add(noInternet());
        rig.cloud.codegen.add(noInternet());
        local.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        var ctx = SkillCodegenTest.task();

        var written = rig.loop.generateSkillCode(SkillCodegenTest.spec(), ctx);

        assertNull(written.error(), written.error());
        assertEquals(2, rig.cloud.calls("codegen").size(), "asked once more, then handed over");
        assertEquals(1, local.calls("codegen").size());
        assertTrue(ctx.onLocal(), "the rest of the task runs on the local model");
        assertEquals(7_000, ctx.localTokens(), "the local model's reply is the local tier's");
    }

    @Test
    @DisplayName("a repair whose connection broke off is the same repair asked again, not code written from the start")
    void aBrokenRepairIsAskedAgain(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(), 600, localModel());
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("def broken(:")));
        rig.cloud.codegen.add(noInternet());
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));

        var written = rig.loop.generateSkillCode(SkillCodegenTest.spec(), SkillCodegenTest.task());

        assertNull(written.error(), written.error());
        var calls = rig.cloud.calls("codegen");
        assertEquals(3, calls.size());
        assertTrue(all(calls.get(1)).contains("def broken(:"), "the premise: the second call is the repair");
        assertEquals(all(calls.get(1)), all(calls.get(2)), "the repair was not the one asked again");
    }

    @Test
    @DisplayName("the local model taking the task is not made to delegate: tools of its own, find_tools, and a delegate described for it")
    void theLocalModelIsGivenTheTools(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(NOOP), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        var ctx = new AgentContext("u1", "t1", "check the network with noop");
        ctx.setUnattended(true);
        ctx.setLocalTierReady(true);
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(List.of(NOOP)), rig.config,
                new LlmRouter(local, null, rig.config, null));

        var mode = engine.stepMode(ctx, local);

        assertTrue(mode.local());
        assertFalse(mode.localFirst(), "the registry withheld from the model that runs the tools");
        var tools = engine.toolsFor(ctx, mode);
        assertTrue(tools.stream().anyMatch(t -> "noop".equals(t.name())));
        String delegate = tools.stream().filter(t -> AgentAction.DELEGATE.equals(t.name())).findFirst()
                .orElseThrow().description();
        assertTrue(delegate.contains("do the work yourself; delegate only to read private data"), delegate);
        assertFalse(delegate.contains("For work on this machine, the LAN and its servers, delegate"), delegate);
    }

    @Test
    @DisplayName("the local model's failed reply -- a 5xx, a stream that broke off -- is a step asked again, as it was")
    void aLocalFailureIsAskedAgain(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(c -> { throw new LlmException("ollama", "HTTP 500: llama runner process has terminated", 500, null); });
        local.think.add(c -> {
            throw new LlmException("ollama", "Connection failed: unexpected end of stream", 0,
                    new java.io.EOFException("unexpected end of stream"));
        });
        local.think.add(respond("done"));

        assertEquals("done", rig.turn(session(rig), "check the network").response());
        assertEquals(3, local.calls("think").size());
    }

    @Test
    @DisplayName("a reply that never came does not count as one: mixed failures still hand the task over")
    void noReplyIsNoAnswer(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.cloud.think.add(noInternet());
        rig.cloud.think.add(c -> { throw new LlmException("anthropic", "the reply stream ended before message_stop", 0, null); });
        rig.cloud.think.add(noInternet());
        local.think.add(respond("done locally"));

        assertTrue(rig.turn(session(rig), "check the network").response().startsWith("done locally"));
        assertEquals(3, rig.cloud.calls("think").size());
    }

    @Test
    @DisplayName("a reply that came and could not be read is no broken connection")
    void anUnreadableReplyIsNoBrokenConnection() {
        var unreadable = new LlmException("anthropic", "Connection failed: bad event", 0,
                new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character"));
        assertFalse(unreadable.connectionFailed());
        assertFalse(unreadable.unreachable(), "the task would move to the local model over one bad event");
        var refused = new LlmException("ollama", "Connection failed: refused", 0, new ConnectException("refused"));
        assertTrue(refused.cannotConnect() && refused.connectionFailed());
        var broke = new LlmException("ollama", "Connection failed: eof", 0, new java.io.EOFException("eof"));
        assertTrue(broke.connectionFailed());
        assertFalse(broke.cannotConnect(), "a stream that broke off is not a host nobody answers on");
    }

    @Test
    @DisplayName("the analysis of the skill library goes to the model the task's calls go to")
    void theAnalysisIsLocalToo(@TempDir Path tmp) throws Exception {
        var local = localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(call(AgentAction.SKILL_MANAGE, Map.of("action", "analyze")));
        local.think.add(c -> Replies.of("{\"summary\": \"lean\"}", 100, 10, 0, 0, "end_turn"));
        local.think.add(respond("done"));

        assertEquals("done", rig.turn(session(rig), "tidy up my skills").response());
        assertEquals(1, local.calls("analyze").size());
        assertTrue(rig.cloud.calls.isEmpty(), "the cloud model was called: " + rig.cloud.calls);
    }
}
