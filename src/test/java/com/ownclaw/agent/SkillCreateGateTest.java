package com.ownclaw.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The duplicate-skill gate, through the real loop. The critic's rule against a skill whose name
 * narrows or nearly repeats an existing one was written in September and never ran: the loop asked
 * the critic about skill_manage, find_tools and the registry's tools, and skill_create went straight
 * to the code generation. These drive a skill_create the way a model sends one and assert what the
 * loop does with it: what is blocked, what the model is told, and what is made all the same.
 */
class SkillCreateGateTest {

    static final String TRASH = "imap_move_to_trash_by_sender";
    static final String BIN = "imap_move_to_bin_by_sender_gmail";

    /** A skill_create as a model sends one. */
    static Map<String, Object> spec(String name) {
        var p = new LinkedHashMap<String, Object>();
        p.put("name", name);
        p.put("description", "Does what " + name + " says.");
        p.put("parameters", "{}");
        return p;
    }

    static Map<String, Object> forced(String name, Object force) {
        var p = spec(name);
        p.put("force", force);
        return p;
    }

    /** What SkillManager is handed, create by create: nothing, while nothing is created. */
    static List<Map<String, Object>> creates(LoopRig rig) {
        var created = new CopyOnWriteArrayList<Map<String, Object>>();
        rig.skills.create = p -> {
            created.add(new HashMap<>(p));
            return "Skill '" + p.get("name") + "' created and registered. It is now available as a tool.";
        };
        return created;
    }

    /** A rig whose local tier can take delegated work, so a chat on Cheaper (the default) is local-first. */
    static LoopRig localFirst(Path tmp, List<Tool> tools) throws Exception {
        return new LoopRig(tmp, tools, 600, LocalModeTest.localModel(),
                (registry, config, router) -> new ThinkingEngine(registry, config, router) {
                    @Override
                    boolean localTierReady(AgentContext context) {
                        return true;
                    }
                });
    }

    /** The names of the skills and actions a think call offers the model. */
    static List<String> offered(LoopRig.Call think) {
        return ToolSpec.offered(think.config().tools(), think.messages()).stream().map(ToolSpec::name).toList();
    }

    /** What the model is shown of the steps so far, on a think call. */
    static String shown(LoopRig.Call think) {
        return String.join("\n", LoopRig.userParts(think));
    }

    static List<String> told(AgentResult r) {
        return r.trajectory().turns().stream().map(t -> t.observation().output()).toList();
    }

    /** What the critic and the loop log, from now on. */
    static ListAppender<ILoggingEvent> logs() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(CriticAgent.class)).addAppender(appender);
        ((Logger) LoggerFactory.getLogger(AgentLoop.class)).addAppender(appender);
        return appender;
    }

    static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(CriticAgent.class)).detachAppender(appender);
        ((Logger) LoggerFactory.getLogger(AgentLoop.class)).detachAppender(appender);
    }

    @Test
    @DisplayName("a skill_create whose name nearly repeats an existing skill's is blocked before any code is written: the model is told which skill covers it and is offered it, and nothing is created")
    void aNearDuplicateIsBlocked(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool(TRASH, List.of(), p -> "moved")));
        var created = creates(rig);
        var statuses = rig.statuses();
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec(BIN)));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));   // never asked for
        rig.cloud.think.add(respond("The existing skill does it."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Mail"), "clear out the newsletters");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(created.isEmpty(), "a skill was made: " + created);
        assertTrue(rig.cloud.calls("codegen").isEmpty(), "code was asked for");
        assertTrue(rig.registry.find(BIN).isEmpty());
        assertEquals("BLOCKED: '" + BIN + "' looks like a duplicate of the existing skill '" + TRASH + "', so nothing "
                + "was created. Call '" + TRASH + "': you can call it from your next step. If it does not cover this "
                + "case, extend it with skill_create under its own name. If '" + BIN + "' is genuinely a different "
                + "capability, create it with force: true and say in its description how it differs.", told(r).get(0));
        assertFalse(r.trajectory().turns().get(0).observation().success());
        var thinks = rig.cloud.calls("think");
        assertTrue(shown(thinks.get(1)).contains(told(r).get(0)), "the model was not told: " + shown(thinks.get(1)));
        // "Call it" holds: the model can call only what it is offered, and it was not offered this.
        assertFalse(offered(thinks.get(0)).contains(TRASH), "the premise: " + offered(thinks.get(0)));
        assertTrue(offered(thinks.get(1)).contains(TRASH), "the existing skill is not offered: " + offered(thinks.get(1)));
        // Nothing says a skill is being made: no step in the chat, no status.
        assertTrue(ProgressMessagesTest.progress(rig).isEmpty(), ProgressMessagesTest.progress(rig).toString());
        assertTrue(statuses.stream().noneMatch(s -> String.valueOf(s.text()).contains("Creating skill")),
                statuses.toString());
        // Mutation: leave skill_create out of the critique again -> the code is asked for and the skill made.
    }

    @Test
    @DisplayName("a skill_create that narrows an existing skill's name is blocked: the model is told to call the general one with the specifics")
    void aNarrowerNameIsBlocked(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("web_search", List.of(), p -> "found")));
        var created = creates(rig);
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("web_search_bikes")));
        rig.cloud.think.add(call("web_search", Map.of("query", "bikes")));
        rig.cloud.think.add(respond("Three shops sell them."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Bikes"), "where can I buy a bike?");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(created.isEmpty(), "a skill was made: " + created);
        assertTrue(rig.cloud.calls("codegen").isEmpty(), "code was asked for");
        assertEquals("BLOCKED: 'web_search_bikes' is a narrower version of the existing skill 'web_search', so nothing "
                + "was created. Call 'web_search' and pass the specifics as parameters: you can call it from your next "
                + "step. If 'web_search' genuinely cannot do this, extend it with skill_create under its own name, or "
                + "create 'web_search_bikes' with force: true and say in its description what 'web_search' is missing.",
                told(r).get(0));
        assertEquals("found", told(r).get(1), "and the general skill ran");
    }

    @Test
    @DisplayName("a name no skill may have -- a variant of an existing skill's -- is refused in SkillManager's own words, forced or not: the gate does not judge it, and force could not help it")
    void aRefusedNameIsNotTheGates(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("web_fetch", List.of(), p -> "<html>")));
        var created = creates(rig);
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("web_fetch_v2")));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, forced("web_fetch_v2", true)));
        rig.cloud.think.add(respond("I will fix web_fetch itself."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Fetch"), "the page comes back empty");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        for (int step : new int[] {0, 1}) {
            assertTrue(told(r).get(step).startsWith("ERROR: Skill 'web_fetch' already exists. Do NOT create "
                    + "'web_fetch_v2'. To fix or update a skill, use skill_create with the SAME name 'web_fetch'"),
                    told(r).get(step));
            assertFalse(told(r).get(step).contains("force"), "it is offered a way out that is none: " + told(r).get(step));
        }
        assertTrue(created.isEmpty(), "a skill was made: " + created);
        assertTrue(rig.cloud.calls("codegen").isEmpty(), "code was asked for");
        // Mutation: ask the critic before the name is checked -> the first is blocked as a narrower
        // version of web_fetch and told to force it, which the name's refusal then turns down.
    }

    @Test
    @DisplayName("on local-first work the cloud calls no skill, so the block says to name the existing one to delegate -- in a chat on Cheaper and on unattended work alike")
    void onLocalFirstWorkTheBlockNamesDelegate(@TempDir Path tmp) throws Exception {
        var rig = localFirst(tmp, List.of(tool("web_search", List.of(), p -> "found"),
                tool(TRASH, List.of(), p -> "moved")));
        var created = creates(rig);
        // A chat on the default, Cheaper.
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("web_search_bikes")));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec(BIN)));
        rig.cloud.think.add(respond("Both exist already."));
        AgentResult chat = rig.turn(rig.chat.createSession("u1", "Bikes"), "where can I buy a bike?");
        // Unattended work: its calls run first, since an answer before anything ran is no answer there.
        rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                List.of(Map.of("tool", "web_search", "params", Map.of("query", "bikes"))))));
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("web_search_bikes")));
        rig.cloud.think.add(respond("Three shops sell them."));
        AgentResult unattended = rig.loop.executeFull("u1", "Find where bikes are sold.", true);

        var thinks = rig.cloud.calls("think");
        assertTrue(ChatOptionsTaskTest.orchestratesOnly(thinks.get(0)), "the premise: the chat is local-first");
        assertTrue(ChatOptionsTaskTest.orchestratesOnly(thinks.get(3)), "the premise: so is the unattended task");
        assertTrue(created.isEmpty(), "a skill was made: " + created);
        String narrower = "BLOCKED: 'web_search_bikes' is a narrower version of the existing skill 'web_search', so "
                + "nothing was created. Name 'web_search' in delegate's 'tools' and state the specifics in the goal: "
                + "you do not call it yourself. If 'web_search' genuinely cannot do this, extend it with skill_create "
                + "under its own name, or create 'web_search_bikes' with force: true and say in its description what "
                + "'web_search' is missing.";
        assertEquals(narrower, told(chat).get(0));
        // The second in a row that ran nothing carries the loop's warning after it.
        assertTrue(told(chat).get(1).startsWith("BLOCKED: '" + BIN + "' looks like a duplicate of the existing skill '"
                + TRASH + "', so nothing was created. Name '" + TRASH + "' in delegate's 'tools': you do not call it "
                + "yourself. If it does not cover this case, extend it with skill_create under its own name. If '" + BIN
                + "' is genuinely a different capability, create it with force: true and say in its description how it "
                + "differs."), told(chat).get(1));
        assertEquals(AgentResult.TerminationReason.COMPLETED, unattended.terminationReason(), unattended.response());
        assertEquals(narrower, told(unattended).get(1));
        for (String block : List.of(told(chat).get(0), told(chat).get(1), told(unattended).get(1))) {
            assertFalse(block.contains("Call '"), "it is told to call a skill it is not given: " + block);
        }
        // Mutation: word the block for a model that is given the skills, whatever the mode -> "Call 'web_search'".
    }

    @Test
    @DisplayName("the text protocol's skill_create passes the same gate, and is told of force in its prompt: blocked without it, made with it")
    void theTextProtocolIsGatedToo(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("web_search", List.of(), p -> "found")));
        rig.config.getMentor().setNativeTools(false);
        var created = creates(rig);
        String create = "{\"tool\": \"skill_create\", \"params\": {\"name\": \"web_search_bikes\", \"description\": "
                + "\"Search the bike shops' own catalogues, which web_search does not reach.\", \"parameters\": \"{}\"";
        rig.cloud.think.add(c -> Replies.of(create + "}}", 100, 10));
        rig.cloud.think.add(c -> Replies.of(create + ", \"force\": \"true\"}}", 100, 10));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.think.add(c -> Replies.of("{\"tool\": \"respond\", \"params\": {\"message\": \"Made it.\"}}", 100, 10));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Bikes"), "where can I buy a bike?");

        assertEquals("Made it.", r.response());
        assertTrue(told(r).get(0).startsWith("BLOCKED: 'web_search_bikes' is a narrower version of the existing skill "
                + "'web_search', so nothing was created. Call 'web_search' and pass the specifics as parameters"),
                told(r).get(0));
        assertEquals(1, created.size(), "made once, by the call that forced it: " + created);
        assertFalse(created.get(0).containsKey("force"), created.toString());
        String system = rig.cloud.calls("think").get(0).messages().get(0).content();
        assertTrue(system.contains("  force: true creates it although an existing skill looks similar; say in the "
                + "description how it differs.\n"), system);
    }

    @Test
    @DisplayName("force: true creates a skill the gate would block, with a warning logged; force is declared in skill_create's schema and is no part of the code's specification or of the skill")
    void forceCreatesIt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool(TRASH, List.of(), p -> "moved")));
        var created = creates(rig);
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, forced(BIN, true)));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.think.add(respond("Made it."));
        var logs = logs();
        AgentResult r;
        try {
            r = rig.turn(rig.chat.createSession("u1", "Mail"), "clear out the newsletters");
        } finally {
            release(logs);
        }

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(r.trajectory().turns().get(0).observation().success(), told(r).get(0));
        assertEquals(1, created.size(), created.toString());
        assertEquals(BIN, created.get(0).get("name"));
        assertFalse(created.get(0).containsKey("force"), "SkillManager was handed force: " + created);
        var code = rig.cloud.calls("codegen");
        assertEquals(1, code.size());
        assertTrue(code.get(0).messages().stream().noneMatch(m -> m.content().contains("force")),
                "the code's specification holds force: " + code.get(0).messages());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage()
                .equals("skill_create for '" + BIN + "' forced past the duplicate check")), "no warning was logged");
        // Declared, so a model can know of it: an optional boolean of skill_create's own.
        ToolSpec skillCreate = rig.cloud.calls("think").get(0).config().tools().stream()
                .filter(t -> AgentAction.SKILL_CREATE.equals(t.name())).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var properties = (Map<String, Map<String, Object>>) skillCreate.inputSchema().get("properties");
        assertEquals("boolean", properties.get("force").get("type"), String.valueOf(properties.get("force")));
        assertEquals("True creates it although an existing skill looks similar; say in the description how it differs.",
                properties.get("force").get("description"));
        assertFalse(((List<?>) skillCreate.inputSchema().get("required")).contains("force"));
        // Mutation: hand the call's arguments on as they came -> SkillManager is handed force.
    }

    @Test
    @DisplayName("a skill_create the gate blocked was no attempt at the skill: the forced creation after it, failing, is told its own error and of no earlier failure")
    void aBlockIsNoFailedAttempt(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool(TRASH, List.of(), p -> "moved")));
        String error = "ERROR: '" + BIN + "' was written but does not load, so it was not registered.";
        rig.skills.create = p -> error;
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec(BIN)));              // blocked
        for (int i = 0; i < 2; i++) {
            rig.cloud.think.add(call(AgentAction.SKILL_CREATE, forced(BIN, true)));   // made, and does not load
            rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        }
        rig.cloud.think.add(respond("It cannot be built here."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Mail"), "clear out the newsletters");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertTrue(told(r).get(0).startsWith("BLOCKED: "), told(r).get(0));
        assertEquals(error, told(r).get(1), "the block was counted as an attempt that failed");
        assertTrue(told(r).get(2).startsWith(error + "\n\nskill_create for '" + BIN + "' failed 1 time before in this "
                + "task."), told(r).get(2));
        assertFalse(told(r).get(2).contains("BLOCKED"), told(r).get(2));
        // Mutation: list every failed skill_create step of the name -> the first real failure is
        // told it "failed 1 time before" and to change the approach, for a block.
    }

    @Test
    @DisplayName("a skill that exists is re-created under its own name, whatever else the library holds: a near-duplicate beside it, or the general skill it narrows")
    void theSameNameIsRecreated(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool(TRASH, List.of(), p -> "moved"), tool(BIN, List.of(), p -> "moved"),
                tool("web_search", List.of(), p -> "found"), tool("web_search_bikes", List.of(), p -> "found")));
        var created = creates(rig);
        for (String name : List.of(TRASH, BIN, "web_search_bikes", "web_search")) {
            rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec(name)));
            rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        }
        rig.cloud.think.add(respond("All four are fixed."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Skills"), "fix the four skills");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(TRASH, BIN, "web_search_bikes", "web_search"),
                created.stream().map(p -> p.get("name")).toList(), told(r).toString());
        assertTrue(told(r).stream().noneMatch(t -> t.startsWith("BLOCKED")), told(r).toString());
        // Mutation: skip only the name itself in the comparison, as before -> the first is blocked
        // for the second, the second for the first, and web_search_bikes for web_search.
    }

    @Test
    @DisplayName("the same failing skill_create, sent six times over, is never refused for being repeated: each is made, and fails by itself")
    void aRepeatedCreateIsNeverRefused(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("web_search", List.of(), p -> "found")));
        int[] attempts = {0};
        rig.skills.create = p -> "ERROR: '" + p.get("name") + "' was written but does not load (attempt "
                + (++attempts[0]) + ").";
        for (int i = 0; i < 6; i++) {
            rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("router_fix")));
            rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        }
        rig.cloud.think.add(respond("It cannot be built here."));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Router"), "fix the router");

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(6, attempts[0], "an attempt was refused: " + told(r));
        assertEquals(6, rig.cloud.calls("codegen").size());
        assertTrue(told(r).stream().noneMatch(t -> t.startsWith("BLOCKED")), told(r).toString());
        // Mutation: block the fourth identical skill_create, as the critic's rule did -> attempts stop at three.
    }

    @Test
    @DisplayName("a name that generalises an existing skill's passes, with a warning to retire the narrower one")
    void aGeneralisingNamePasses(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(tool("web_search_bikes", List.of(), p -> "found")));
        var created = creates(rig);
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec("web_search")));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        rig.cloud.think.add(respond("Made the general one."));
        var logs = logs();
        AgentResult r;
        try {
            r = rig.turn(rig.chat.createSession("u1", "Search"), "make a search for anything");
        } finally {
            release(logs);
        }

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(1, created.size(), told(r).toString());
        assertEquals("web_search", created.get(0).get("name"));
        String warning = "critic warning: 'web_search' generalises web_search_bikes — retire the narrower skill(s) "
                + "once this works";
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().endsWith(warning)),
                "the warning was not logged: " + logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        // Mutation: compare the names before asking whether one generalises the other -> web_search
        // is blocked for its own narrower sibling (two of three tokens shared).
    }
}
