package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local model running a task itself starts with the tools likeliest to fit it and finds the
 * rest, instead of reading every tool's description first: those were most of a 42,000-token
 * prompt it reads at about 90 tokens a second, six to eight minutes before its first step.
 */
class ToolFinderTest {

    static Tool skill(String name, String description, Map<String, ToolParam> params) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return description; }
            public Map<String, ToolParam> inputSchema() { return params; }
            public List<String> requiredCredentials() { return List.of(); }
            public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
                return ToolResult.success(name + " ran");
            }
        };
    }

    /** A library like the owner's: a few that fit a request, many that do not. */
    static List<Tool> library() {
        var tools = new ArrayList<Tool>(List.of(
                skill("smtp_send_email", "Send an email through the configured SMTP server. Returns the message id.",
                        Map.of("to", ToolParam.required("string", "Recipient address"))),
                skill("imap_unread_summarize", "Summarise the unread emails in an IMAP mailbox.", Map.of()),
                skill("openwrt_run", "Run a command on an OpenWrt router over SSH and return its output.",
                        Map.of("host", ToolParam.required("string", "Router address"))),
                skill("openwrtWifiConfig", "Read the wireless configuration of every router.", Map.of()),
                skill("web_fetch", "Fetch a web page and return its text.", Map.of()),
                skill("daily_menu_fetcher", "Fetch the lunch menus of the restaurants.", Map.of())));
        for (int i = 0; i < 20; i++) {
            tools.add(skill(String.format("filler_%02d", i), "Converts unit " + i + " into kelvins.", Map.of()));
        }
        return tools;
    }

    static List<String> names(ToolFinder.Page page) {
        return page.tools().stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("matches come best first, by name, description and parameters; plurals and case changes are the same word")
    void ranking() {
        assertEquals("smtp_send_email", names(ToolFinder.find(library(), "send emails", 1)).getFirst());
        assertEquals(List.of("openwrtWifiConfig"), names(ToolFinder.find(library(), "wifi", 1)),
                "a name in camel case is its words");
        assertEquals(List.of("smtp_send_email"), names(ToolFinder.find(library(), "recipient", 1)),
                "a parameter's description is searched too");
        assertTrue(names(ToolFinder.find(library(), "emails", 1)).contains("smtp_send_email"),
                "\"emails\" is \"email\"");
        assertEquals(0, ToolFinder.find(library(), "the of and", 1).total(), "common words match nothing");
        assertTrue(names(ToolFinder.find(library(), "WiFi", 1)).contains("openwrtWifiConfig"));
        assertTrue(names(ToolFinder.find(library(), "OpenWrt", 1)).containsAll(List.of("openwrt_run", "openwrtWifiConfig")),
                "a word written in parts is also the whole word");
        assertTrue(names(ToolFinder.find(library(), "e-mail", 1)).contains("smtp_send_email"));
        assertEquals(0, ToolFinder.find(library(), "bicycle", 1).total());
    }

    @Test
    @DisplayName("a page holds eight; the rest are on the next pages, none dropped")
    void paging() {
        var first = ToolFinder.find(library(), "converts kelvins", 1);
        assertEquals(20, first.total());
        assertEquals(ToolFinder.PAGE, first.tools().size());
        assertEquals(3, first.pages());
        var all = new ArrayList<String>();
        for (int p = 1; p <= first.pages(); p++) all.addAll(names(ToolFinder.find(library(), "converts kelvins", p)));
        assertEquals(20, all.size());
        assertEquals(20, all.stream().distinct().count());
        assertTrue(ToolFinder.find(library(), "converts kelvins", 4).tools().isEmpty());
        assertTrue(ToolFinder.find(library(), "converts kelvins", Integer.MAX_VALUE).tools().isEmpty(),
                "a page past the last is empty, however far past");
    }

    @Test
    @DisplayName("a plural is its singular; a word that only ends in s is itself")
    void singular() {
        assertEquals("menu", ToolFinder.singular("menus"));
        assertEquals("address", ToolFinder.singular("addresses"));
        assertEquals("summary", ToolFinder.singular("summaries"));
        assertEquals("box", ToolFinder.singular("boxes"));
        for (String same : List.of("news", "status", "class", "analysis")) assertEquals(same, ToolFinder.singular(same));
    }

    @Test
    @DisplayName("a match is listed with the first sentence of its description")
    void gist() {
        assertEquals("Reads a page, e.g. a menu, and returns it.", ToolFinder.gist("Reads a page, e.g. a menu, and returns it. Slow."));
        assertEquals("Send an email through the configured SMTP server.",
                ToolFinder.gist("Send an email through the configured SMTP server. Returns the message id."));
        assertEquals("One line", ToolFinder.gist("One line\nand the rest"));
        assertEquals("No full stop", ToolFinder.gist("No full stop"));
    }

    /** What the model was offered: what a provider without deferred loading sends. */
    static List<String> offered(Call call) {
        return ToolSpec.offered(call.config().tools(), call.messages()).stream().map(ToolSpec::name).toList();
    }

    @Test
    @DisplayName("the local model starts with the tools the request names or fits and the owner uses most, finds more, and keeps what it read")
    void theLocalModelFindsItsTools(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, library(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        rig.jdbc.update("INSERT INTO skill_usage (tool_name, user_id, task_id, success, duration_ms) "
                + "VALUES ('filler_07', 'u1', 't0', 1, 5)");
        local.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "router wifi")));
        local.think.add(call("openwrtWifiConfig", Map.of()));
        local.think.add(respond("done"));

        assertEquals("done", rig.turn(LocalModeTest.session(rig), "send an email to me via web_fetch").response());

        var calls = local.calls("think");
        List<String> first = offered(calls.get(0));
        assertTrue(first.contains(AgentAction.FIND_TOOLS), first.toString());
        assertTrue(first.contains("smtp_send_email"), "the best match for the request: " + first);
        assertTrue(first.contains("web_fetch"), "the tool the request names: " + first);
        assertTrue(first.contains("filler_07"), "the owner's most used: " + first);
        assertFalse(first.contains("openwrt_run"), "a tool nothing pointed to: " + first);
        assertTrue(first.stream().filter(n -> n.startsWith("filler_")).toList().equals(List.of("filler_07")), first.toString());

        List<String> second = offered(calls.get(1));
        assertEquals(first, second.subList(0, first.size()), "what it read is the start of what it is sent");
        assertTrue(second.subList(first.size(), second.size()).contains("openwrtWifiConfig"), second.toString());
        String found = calls.get(1).messages().stream().map(LlmMessage::content).reduce("", String::concat);
        assertTrue(found.contains("- openwrtWifiConfig: Read the wireless configuration of every router."), found);

        // The cloud's toolset is the same: every skill sent, deferred until offered, and find_tools.
        var cloudTask = new AgentContext("u1", "t2", "send an email");
        var cloudMode = new ThinkingEngine.StepMode(true, false, false);
        var cloudEngine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(library()), rig.config, null);
        cloudEngine.buildMessages(cloudTask, "anthropic", cloudMode);
        List<ToolSpec> cloud = cloudEngine.toolsFor(cloudTask, cloudMode);
        assertTrue(cloud.stream().anyMatch(t -> AgentAction.FIND_TOOLS.equals(t.name()) && !t.deferred()));
        assertTrue(cloud.stream().filter(ToolSpec::deferred).map(ToolSpec::name).toList()
                .containsAll(library().stream().map(Tool::name).toList()), "nothing usual: every skill deferred");
    }

    @Test
    @DisplayName("the first tools: those the chat so far names, the best matches for the request, the owner's most used")
    void firstTools() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "send an email");
        ctx.setConversationSummary("USER: scan it\n[OwnClaw's record of task 4b22f2c5, from its step log:\n1. ✓ filler_05]");
        ctx.setUsualTools(List.of("filler_11", "a_skill_since_deleted"));

        var mode = new ThinkingEngine.StepMode(true, false, true);
        var messages = engine.buildMessages(ctx, "ollama", mode);
        List<String> names = ToolSpec.offered(engine.toolsFor(ctx, mode), messages).stream()
                .map(ToolSpec::name).toList();

        assertTrue(names.contains("filler_05"), "named in the chat so far: " + names);
        assertTrue(names.contains("smtp_send_email"), "the best match: " + names);
        assertTrue(names.contains("filler_11"), "used most: " + names);
        assertFalse(names.contains("a_skill_since_deleted"), names.toString());
        assertEquals(List.of("filler_11", "filler_05"), names.stream().filter(n -> n.startsWith("filler_")).toList(),
                "the usual ones first, the same for every task; then the task's");
    }

    @Test
    @DisplayName("a task starts with the request's best few matches, not a whole page: the rest are a find away")
    void aFewMatches() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "it converts kelvins");
        var mode = new ThinkingEngine.StepMode(true, false, false);
        var messages = engine.buildMessages(ctx, "anthropic", mode);
        List<String> offered = ToolSpec.offered(engine.toolsFor(ctx, mode), messages).stream()
                .map(ToolSpec::name).filter(n -> n.startsWith("filler_")).toList();
        assertEquals(ThinkingEngine.FIRST_MATCHES, offered.size(), "20 match: " + offered);
        assertEquals(ToolFinder.find(library(), "it converts kelvins", 1).tools().stream().limit(ThinkingEngine.FIRST_MATCHES)
                .map(Tool::name).toList(), offered, "the best of them");
    }

    @Test
    @DisplayName("taking a task over, the local model starts with the tools the task already ran and created")
    void takenOverMidTask() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "check the routers");
        ctx.trajectory().record(new AgentAction("filler_03", Map.of(), ""),
                AgentObservation.success("filler_03", "273.15", Map.of(), 5));
        ctx.trajectory().record(new AgentAction(AgentAction.SKILL_CREATE, Map.of("name", "filler_09"), ""),
                AgentObservation.success(AgentAction.SKILL_CREATE, "Skill 'filler_09' created.", Map.of(), 5));

        var mode = new ThinkingEngine.StepMode(true, false, true);
        var messages = engine.buildMessages(ctx, "ollama", mode);
        List<String> names = ToolSpec.offered(engine.toolsFor(ctx, mode), messages).stream()
                .map(ToolSpec::name).toList();

        assertTrue(names.containsAll(List.of("filler_03", "filler_09")), names.toString());
    }

    @Test
    @DisplayName("a skill the local model creates is one of its tools from the next step")
    void aCreatedSkillIsGiven(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, library(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(call(AgentAction.SKILL_CREATE, Map.of("name", "filler_15", "description", "Converts.",
                "parameters", "{}")));
        local.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        local.think.add(respond("done"));

        rig.turn(LocalModeTest.session(rig), "what time is it");

        assertFalse(offered(local.calls("think").get(0)).contains("filler_15"), "the premise");
        assertTrue(offered(local.calls("think").get(1)).contains("filler_15"), offered(local.calls("think").get(1)).toString());
    }

    @Test
    @DisplayName("the same search a third time in a row is refused: its matches are in the context already")
    void aSearchLoopIsStopped(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, library(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        for (int i = 0; i < 3; i++) local.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "kelvins")));
        local.think.add(respond("done"));

        rig.turn(LocalModeTest.session(rig), "convert units");

        String told = last(local.calls("think").get(3));
        assertTrue(told.contains("You have searched for the same thing 2 times in a row"), told);
    }

    @Test
    @DisplayName("every model offered skills by find_tools is told about it, and no other")
    void theRuleIsTrue() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(library()),
                new com.ownclaw.config.OwnClawConfig(), null);
        var ctx = new AgentContext("u1", "t1", "send an email");
        String rule = "you can call only the tools you are given";
        for (var mode : List.of(new ThinkingEngine.StepMode(true, false, true), new ThinkingEngine.StepMode(true, false, false))) {
            assertTrue(engine.buildMessages(ctx, "ollama", mode).get(0).content().contains(rule), mode.toString());
        }
        for (var mode : List.of(new ThinkingEngine.StepMode(false, false, true), new ThinkingEngine.StepMode(true, true, false))) {
            assertFalse(engine.buildMessages(ctx, "ollama", mode).get(0).content().contains(rule), mode.toString());
        }
    }

    @Test
    @DisplayName("a skill named find_tools is offered to no model: the loop runs find_tools itself")
    void findToolsIsReserved() {
        var tools = new ArrayList<>(library());
        tools.add(skill(AgentAction.FIND_TOOLS, "A skill that took the name.", Map.of()));
        var cloud = com.ownclaw.agent.tools.ToolSchemas.build(SpecialActionSchemas.ALL, tools, List.of());
        assertTrue(cloud.stream().noneMatch(t -> AgentAction.FIND_TOOLS.equals(t.name())));
    }

    @Test
    @DisplayName("find_tools says how many match and where the rest are, and says so when nothing does")
    void whatFindToolsSays(@TempDir Path tmp) throws Exception {
        var local = LocalModeTest.localModel();
        var rig = new LoopRig(tmp, library(), 600, local);
        rig.config.getMentor().setLocalOnly(true);
        local.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "converts kelvins")));
        local.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "bicycle")));
        local.think.add(respond("done"));

        rig.turn(LocalModeTest.session(rig), "convert units");

        String afterFirst = last(local.calls("think").get(1));
        assertTrue(afterFirst.contains("20 skills match \"converts kelvins\"; these are 1 to 8, best first."), afterFirst);
        assertTrue(afterFirst.contains("More: find_tools with the same query and page 2 (of 3)."), afterFirst);
        String afterSecond = last(local.calls("think").get(2));
        assertTrue(afterSecond.contains("No skill matches \"bicycle\" among the 26."), afterSecond);
    }

    static String last(Call call) {
        var users = userParts(call);
        return String.join("\n", users);
    }
}
