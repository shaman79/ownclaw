package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.observability.ChatStatusEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Which calls carry the owner's thinking effort: every step of a task and the skill code it writes,
 * through the real gateway to the cloud model; the steps of a task the local model runs itself; and
 * every turn of a delegation. How each provider renders it is ThinkingEffortRequestTest's.
 */
class ThinkingEffortCallsTest {

    @Test
    @DisplayName("the cloud model's steps and the skill code it writes carry the level, through the gateway")
    void theCloudCalls(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP));
        rig.config.getMentor().setThinkingEffort("medium");
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(respond("done"));
        assertEquals("done", rig.turn(LocalModeTest.session(rig), "check the network").response());
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        assertNull(rig.loop.generateSkillCode(SkillCodegenTest.spec(), SkillCodegenTest.task()).error());

        assertEquals(2, rig.cloud.calls("think").size());
        assertEquals(1, rig.cloud.calls("codegen").size());
        for (Call c : rig.cloud.calls) assertEquals("medium", c.config().effort(), c.purpose());
    }

    @Test
    @DisplayName("a task the local model runs itself carries the level on each of its steps")
    void theLocalModelsOwnTask(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, List.of(LocalModeTest.NOOP), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        rig.config.getMentor().setThinkingEffort("low");
        local.think.add(call("noop", Map.of()));
        local.think.add(respond("done locally"));

        assertEquals("done locally", rig.turn(LocalModeTest.session(rig), "check the network").response());
        assertEquals(2, local.calls("think").size());
        for (Call c : local.calls("think")) assertEquals("low", c.config().effort());
    }

    @Test
    @DisplayName("every turn of a delegation carries the level")
    void aDelegation() {
        var config = new OwnClawConfig();
        config.getMentor().setThinkingEffort("low");
        var llm = new DelegationBehaviourTest.Scripted(DelegationBehaviourTest.call("noop", Map.of()),
                DelegationBehaviourTest.done("nothing to report"));
        var noop = new DelegationBehaviourTest.FakeTool("noop", false, List.of(),
                p -> com.ownclaw.agent.tools.ToolResult.success("nothing to report"));
        new LocalExecutor(new LlmRouter(llm, null, null, null), new ToolRegistry(List.of(noop)),
                new ChatStatusEmitter(), new DelegationBehaviourTest.Usage(), config)
                .execute(DelegationBehaviourTest.plan("check the network"), DelegationBehaviourTest.task(),
                        DelegationBehaviourTest.UNCOUNTED);

        assertEquals(2, llm.configs.size(), "the tool call, then done");
        for (LlmRequestConfig c : llm.configs) assertEquals("low", c.effort());
    }
}
