package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The cloud finds its skills as the local model does: every skill is sent deferred, the usual
 * ones are offered, and the task's first ones and what find_tools finds are offered by TOOLS
 * messages, where they were offered. Every skill's description used to be sent whole on every
 * step: ~170,000 characters of a chat's every request, 114,293 of a scheduled run's.
 */
class CloudToolsTest {

    static int indexOf(List<LlmMessage> messages, LlmMessage.Role role, String contains) {
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            if (m.role() == role && m.content().contains(contains)) return i;
        }
        return -1;
    }

    @Test
    @DisplayName("a chat task: skills deferred, the first ones offered after the task, a find offered after its result, the history only appended to")
    void aChatTask(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, ToolFinderTest.library());
        rig.cloud.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "router wifi")));
        rig.cloud.think.add(call("openwrtWifiConfig", Map.of()));
        rig.cloud.think.add(respond("done"));

        assertEquals("done", rig.turn(LocalModeTest.session(rig), "send an email to me").response());

        var calls = rig.cloud.calls("think");
        List<ToolSpec> tools = calls.get(0).config().tools();
        assertTrue(tools.stream().anyMatch(t -> AgentAction.FIND_TOOLS.equals(t.name()) && !t.deferred()));
        assertTrue(tools.stream().filter(t -> t.name().startsWith("filler_")).allMatch(ToolSpec::deferred),
                "kept past the gateway: " + tools);

        var first = calls.get(0).messages();
        int seed = indexOf(first, LlmMessage.Role.TOOLS, "smtp_send_email");
        assertEquals(2, seed, "the task's first skills, right after the task: " + first);

        var second = calls.get(1).messages();
        assertEquals(first.get(seed), second.get(seed), "where it was");
        int result = indexOf(second, LlmMessage.Role.USER, "skills match \"router wifi\"");
        assertTrue(result > seed, second.toString());
        assertEquals(LlmMessage.Role.TOOLS, second.get(result + 1).role(), "the find, after its result");
        assertTrue(second.get(result + 1).addedTools().contains("openwrtWifiConfig"));

        var third = calls.get(2).messages();
        assertEquals(second.subList(2, second.size()).stream().filter(m -> m.role() != LlmMessage.Role.USER).toList(),
                third.subList(2, second.size()).stream().filter(m -> m.role() != LlmMessage.Role.USER).toList(),
                "every message the second step sent is still there, in its place");
    }

    @Test
    @DisplayName("local-first work: no TOOLS message and no catalogue; delegate names the task's likeliest skills and find_tools searches the rest")
    void localFirstWork(@TempDir Path tmp) throws Exception {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(ToolFinderTest.library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "Fetch the lunch menus, then send them with smtp_send_email.");
        ctx.setUnattended(true);
        var mode = new ThinkingEngine.StepMode(true, true, false);

        var messages = engine.buildMessages(ctx, "anthropic", mode);
        var tools = engine.toolsFor(ctx, mode);

        assertTrue(messages.stream().noneMatch(m -> m.role() == LlmMessage.Role.TOOLS), "it calls no skill");
        assertTrue(tools.stream().noneMatch(t -> t.name().startsWith("filler_") || "smtp_send_email".equals(t.name())));
        String delegate = tools.stream().filter(t -> AgentAction.DELEGATE.equals(t.name())).findFirst()
                .orElseThrow().description();
        assertTrue(delegate.contains("- smtp_send_email: Send an email through the configured SMTP server."), delegate);
        assertTrue(delegate.contains("- daily_menu_fetcher: Fetch the lunch menus of the restaurants."), delegate);
        assertFalse(delegate.contains("filler_"), "no catalogue: " + delegate);
        assertTrue(tools.stream().anyMatch(t -> AgentAction.FIND_TOOLS.equals(t.name())));
        assertTrue(ctx.offeredTools().contains(AgentAction.FIND_TOOLS), "it may run find_tools");
        assertFalse(ctx.offeredTools().contains("smtp_send_email"), "and still no skill");

        // On the next step the same: the cached prefix holds.
        ctx.trajectory().record(new AgentAction(AgentAction.FIND_TOOLS, Map.of("query", "kelvins"), ""),
                AgentObservation.success(AgentAction.FIND_TOOLS, "20 skills match", Map.of(), 1));
        assertEquals(delegate, engine.toolsFor(ctx, mode).stream().filter(t -> AgentAction.DELEGATE.equals(t.name()))
                .findFirst().orElseThrow().description());
    }

    @Test
    @DisplayName("a skill named like a special action does not defer the action: the model keeps respond")
    void aSkillNamedRespond() {
        var tools = new java.util.ArrayList<>(ToolFinderTest.library());
        tools.add(ToolFinderTest.skill(AgentAction.RESPOND, "A skill that took the name.", Map.of()));
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(tools),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "hello");
        var mode = new ThinkingEngine.StepMode(true, false, false);
        engine.buildMessages(ctx, "anthropic", mode);
        var respond = engine.toolsFor(ctx, mode).stream().filter(t -> AgentAction.RESPOND.equals(t.name())).toList();
        assertEquals(1, respond.size());
        assertFalse(respond.getFirst().deferred(), "the model could not answer");
    }

    @Test
    @DisplayName("local-first work also names the owner's usual skills to delegate")
    void localFirstNamesTheUsual() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(ToolFinderTest.library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "do the morning thing");
        ctx.setUnattended(true);
        ctx.setUsualTools(List.of("openwrt_run"));
        var mode = new ThinkingEngine.StepMode(true, true, false);
        engine.buildMessages(ctx, "anthropic", mode);
        String delegate = engine.toolsFor(ctx, mode).stream().filter(t -> AgentAction.DELEGATE.equals(t.name()))
                .findFirst().orElseThrow().description();
        assertTrue(delegate.contains("- openwrt_run: Run a command on an OpenWrt router over SSH"), delegate);
    }

    @Test
    @DisplayName("find_tools where skills are not offered by TOOLS messages starts no seed")
    void findWithoutASeed(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, ToolFinderTest.library());
        var ctx = new AgentContext("u1", "t8", "convert units");
        var findTools = AgentLoop.class.getDeclaredMethod("findTools", Map.class, AgentContext.class);
        findTools.setAccessible(true);
        findTools.invoke(rig.loop, Map.of("query", "kelvins"), ctx);
        assertNull(ctx.toolsAdded(), "a later native step would never be given its first tools");
    }

    @Test
    @DisplayName("find_tools on local-first work says its matches are for delegate's 'tools'")
    void findForDelegate(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, ToolFinderTest.library());
        rig.cloud.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "kelvins")));
        rig.cloud.think.add(respond("done"));
        var ctx = new AgentContext("u1", "t9", "convert units");
        ctx.setOfferedTools(java.util.Set.of(AgentAction.FIND_TOOLS));   // what local-first work offers

        var findTools = AgentLoop.class.getDeclaredMethod("findTools", Map.class, AgentContext.class);
        findTools.setAccessible(true);
        String said = (String) findTools.invoke(rig.loop, Map.of("query", "kelvins"), ctx);

        assertTrue(said.contains("They are for delegate's 'tools': you do not call them yourself"), said);
    }
}
