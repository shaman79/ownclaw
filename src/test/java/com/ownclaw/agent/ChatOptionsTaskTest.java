package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ScriptedAnthropic;
import com.ownclaw.llm.SilentOllama;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A chat's own time vs cost and thinking effort, chosen next to the message box: its task runs on
 * them, through the real queue path, loop, engine and gateway -- on the owner's defaults where it
 * chose nothing -- and every reader of them reads the task's, not the defaults.
 */
class ChatOptionsTaskTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("what a message chose wins over the defaults, each option on its own; nothing chosen is the defaults")
    void whatWasChosenThenTheDefaults() {
        var mentor = new OwnClawConfig().getMentor();
        mentor.setPreferCost(false);
        mentor.setThinkingEffort("medium");
        assertEquals(new TaskOptions(false, false, "medium"), TaskOptions.of(ChatOptions.NONE, mentor),
                "the defaults: Fastest, medium");
        assertEquals(new TaskOptions(true, false, "medium"), TaskOptions.of(new ChatOptions("free", null), mentor));
        assertEquals(new TaskOptions(false, true, "low"), TaskOptions.of(new ChatOptions("cheaper", "low"), mentor));
        assertEquals(new TaskOptions(false, false, "high"), TaskOptions.of(new ChatOptions(null, "high"), mentor));
        mentor.setLocalOnly(true);
        assertEquals(new TaskOptions(false, false, "medium"), TaskOptions.of(new ChatOptions("fast", null), mentor),
                "a chat on Fastest calls the cloud whatever the default");
        assertEquals(new TaskOptions(true, false, "medium"), TaskOptions.of(ChatOptions.NONE, mentor));
    }

    @Test
    @DisplayName("a chat on Free runs on the local model with the default Fastest, told why; a chat on the defaults calls the cloud")
    void aChatOnFreeRunsLocally(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, local);
        rig.config.getMentor().setPreferCost(false);
        local.think.add(call("noop", Map.of()));
        local.think.add(respond("done locally"));

        AgentResult r = rig.turn(LocalModeTest.session(rig), "check the network", new ChatOptions("free", null));

        assertEquals("done locally", r.response());
        assertTrue(rig.cloud.calls.isEmpty(), "the cloud model was called: " + rig.cloud.calls);
        assertTrue(LocalModeTest.all(local.calls("think").get(0)).contains("- Model: you are the local model, "
                + "running this task on your own: the owner has switched the cloud model off."));

        rig.cloud.think.add(respond("on the cloud"));
        assertEquals("on the cloud", rig.turn(LocalModeTest.session(rig), "and now?", ChatOptions.NONE).response());
        assertEquals(2, local.calls("think").size(), "the other chat's task never reached the local model");
        // Mutation: the router reading the default again -> the Free chat's task calls the cloud.
    }

    @Test
    @DisplayName("a chat on Fastest calls the cloud with the default Free")
    void aChatOnFastestCallsTheCloud(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        rig.cloud.think.add(respond("on the cloud"));

        assertEquals("on the cloud", rig.turn(LocalModeTest.session(rig), "check the network",
                new ChatOptions("fast", null)).response());
        assertTrue(local.calls.isEmpty(), "the local model was called: " + local.calls);
    }

    @Test
    @DisplayName("a chat on Free whose local model is down ends saying the cloud is switched off, and how to switch it on")
    void aChatOnFreeWithNoLocalModel(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(), 600, local);
        local.think.add(c -> {
            throw new LlmException("ollama", "Connection failed: refused", 0, new ConnectException("refused"));
        });

        AgentResult r = rig.turn(LocalModeTest.session(rig), "check the network", new ChatOptions("free", null));

        assertEquals(AgentResult.TerminationReason.ERROR, r.terminationReason());
        assertTrue(r.response().startsWith("**Stopped:** the local model (ollama) could not be reached -- no "
                + "connection to it -- and the cloud model is switched off (time vs cost is set to Free; Fastest "
                + "or Cheaper next to the message box switches it on).\n\n"), r.response());
        assertTrue(rig.cloud.calls.isEmpty());
        // Mutation: the ending reading the default (off) -> the switch goes unmentioned.
    }

    /**
     * Whether the cloud model was asked to orchestrate only on a step: delegate says the skills
     * are not given to it -- and then none is offered: in the array, deferred, with no TOOLS
     * message to offer it.
     */
    static boolean orchestratesOnly(Call think) {
        String delegate = think.config().tools().stream().filter(t -> AgentAction.DELEGATE.equals(t.name()))
                .findFirst().orElseThrow().description();
        boolean only = delegate.contains("you are not given the skills to run yourself");
        if (only) {
            assertTrue(ToolSpec.offered(think.config().tools(), think.messages()).stream()
                    .noneMatch(t -> "noop".equals(t.name())), "a skill offered all the same");
        }
        return only;
    }

    @Test
    @DisplayName("a chat on Fastest is offered the skills with the default Cheaper; one on Cheaper hands them to the local model with the default Fastest")
    void aChatsCostModeDecidesWhoRunsTheSkills(@TempDir Path tmp) throws Exception {
        // A local tier that can take delegated work, as production's does when it is healthy.
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, LocalModeTest.localModel(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    boolean localTierReady(AgentContext context) {
                        return true;
                    }
                });
        String chat = LocalModeTest.session(rig);
        for (int i = 0; i < 4; i++) rig.cloud.think.add(respond("done"));

        rig.turn(chat, "check the network", new ChatOptions("fast", null));
        rig.turn(chat, "check the network", ChatOptions.NONE);
        rig.config.getMentor().setPreferCost(false);
        rig.turn(chat, "check the network", new ChatOptions("cheaper", null));
        rig.turn(chat, "check the network", ChatOptions.NONE);

        var think = rig.cloud.calls("think");
        assertFalse(orchestratesOnly(think.get(0)), "Fastest: the cloud runs the skills");
        assertTrue(orchestratesOnly(think.get(1)), "the default Cheaper: they are the local model's");
        assertTrue(orchestratesOnly(think.get(2)), "Cheaper: they are the local model's");
        assertFalse(orchestratesOnly(think.get(3)), "the default Fastest: the cloud runs them");
        // Mutation: stepMode reading the default again -> the first and third tasks follow it.
    }

    @Test
    @DisplayName("a chat's thinking effort reaches the Anthropic request body, its steps' and its code's, over the default")
    void aChatsEffortReachesAnthropic(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var api = ScriptedAnthropic.answering(100, 0, 10, "Both routers answer.");
        rig.cloud.think.add(c -> api.provider().chat(List.of(LlmMessage.user("audit the routers")), c));

        AgentResult r = rig.turn(LocalModeTest.session(rig), "audit the routers", new ChatOptions(null, "low"));

        assertEquals("Both routers answer.", r.response());
        JsonNode body = JSON.readTree(api.bodies().getFirst());
        assertEquals("low", body.path("output_config").path("effort").asText(), body.toString());
        // Mutation: the think call reading the default again -> "high".
    }

    @Test
    @DisplayName("a chat on Free at low thinking asks the local model not to reason first")
    void aChatsEffortReachesOllama(@TempDir Path tmp) throws Exception {
        var ollama = new SilentOllama(0, SilentOllama.says(
                "{\"tool\": \"respond\", \"params\": {\"message\": \"Both routers answer.\"}}"));
        var rig = new LoopRig(tmp, List.of(), 600, ollama.provider());

        AgentResult r = rig.turn(LocalModeTest.session(rig), "audit the routers", new ChatOptions("free", "low"));

        assertEquals("Both routers answer.", r.response());
        assertTrue(rig.cloud.calls.isEmpty());
        JsonNode body = JSON.readTree(ollama.bodies().getFirst());
        assertTrue(body.has("think") && !body.path("think").asBoolean(true), body.toString());
    }

    @Test
    @DisplayName("unattended work runs on the defaults")
    void unattendedWorkRunsOnTheDefaults(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.config.getMentor().setThinkingEffort("medium");
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("done"));

        assertEquals("done", rig.loop.executeFull("u1", "the morning digest", true).response());
        for (Call c : rig.cloud.calls("think")) assertEquals("medium", c.config().effort());
    }
}
