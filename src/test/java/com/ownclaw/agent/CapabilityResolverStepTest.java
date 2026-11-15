package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The skill the loop makes at a task's first step when the capability resolver sees a gap, through
 * the real loop and the real resolver -- with no container runtime, as production has none, so its
 * pure-Python variants. No model chose that step: it passes the gate a model's skill_create passes
 * all the same.
 */
class CapabilityResolverStepTest {

    static final String ASKS = "Scan the network for devices and list each host with its open ports.";

    /**
     * A rig whose loop asks the real resolver.
     *
     * @param localFirst the local tier can take delegated work, so unattended work is local-first
     */
    static LoopRig rig(Path tmp, boolean localFirst, Tool... tools) throws Exception {
        return new LoopRig(tmp, List.of(tools), 600,
                localFirst ? LocalModeTest.localModel() : new StopWithoutLocalModelTest.Down(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    boolean localTierReady(AgentContext context) {
                        return localFirst;
                    }
                }, registry -> new CapabilityResolver(registry, null));
    }

    @Test
    @DisplayName("the resolver's skill passes the duplicate gate too: one an existing skill covers by name is not made or recorded, and the model takes the task's first step")
    void theResolversSkillPassesTheGate(@TempDir Path tmp) throws Exception {
        // The resolver's own look at the library misses this scanner -- two of its six keywords,
        // where it wants three -- and the gate does not: two of the name's three tokens are shared.
        var rig = rig(tmp.resolve("covered"), false, tool("lan_network_scanner", List.of(), p -> "four devices"));
        var hint = new CapabilityResolver(rig.registry, null).resolve(ASKS);
        assertNotNull(hint, "the premise: the resolver sees a gap");
        assertEquals("network_scanner", hint.suggestedName());
        var created = SkillCreateGateTest.creates(rig);
        var statuses = rig.statuses();
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));   // never asked for
        rig.cloud.think.add(call("lan_network_scanner", Map.of()));
        rig.cloud.think.add(respond("Four devices answer."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Network"), ASKS);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals("Four devices answer.", r.response());
        assertTrue(created.isEmpty(), "a second scanner was made: " + created);
        assertTrue(rig.cloud.calls("codegen").isEmpty(), "code was asked for");
        assertEquals(List.of("lan_network_scanner"), r.trajectory().turns().stream().map(t -> t.action().tool()).toList(),
                "the model's first step is the task's first: nothing of the resolver's is recorded");
        assertTrue(rig.cloud.calls("think").get(0).messages().stream()
                .noneMatch(m -> m.content() != null && m.content().contains("CapabilityResolver")), "nor replayed");
        assertTrue(statuses.stream().noneMatch(s -> String.valueOf(s.text()).contains("Creating skill")),
                statuses.toString());

        // With no such scanner the skill is made at step 1, before any model is asked for a step.
        var bare = rig(tmp.resolve("bare"), false);
        var made = SkillCreateGateTest.creates(bare);
        bare.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        bare.cloud.think.add(respond("Made a scanner."));
        bare.turn(bare.chat.createSession("u1", "Network"), ASKS);
        assertEquals(1, made.size(), made.toString());
        assertEquals("network_scanner", made.get(0).get("name"));
        assertEquals("codegen", bare.cloud.calls.get(0).purpose(), "the first call was the code's, not a step's");
        // Mutation: make the resolver's skill without asking the critic, as before -> a second scanner.
    }
}
