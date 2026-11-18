package com.ownclaw.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.core.ResultDelivery;
import com.ownclaw.llm.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.ownclaw.agent.DelegationBehaviourTest.UNCOUNTED;
import static com.ownclaw.agent.DelegationBehaviourTest.executor;
import static com.ownclaw.agent.DelegationBehaviourTest.task;
import static com.ownclaw.agent.DelegationCallsTest.c;
import static com.ownclaw.agent.DelegationCallsTest.calls;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The cloud writes exact calls (delegate's 'calls') for skills whose schema it is never shown: on
 * unattended work and on a chat set to Cheaper every skill is deferred in its tools, and what it
 * read of one was the first sentence of its description. So it is told each skill's parameters
 * where it is told the skill -- find_tools, and the skills delegate's description names -- and a
 * call that does not fit them is refused before the first call of its delegation runs.
 * <p>
 * Production, every morning: the news run's first delegation sent smtp_send_email its text as
 * 'body'. The skill takes body_text, so the call failed and the second attempt worked; on other
 * days the cloud read the skill's whole source for the names (14,000 to 167,000 characters), or
 * left the calls to the local model (172 s for 30 s of work).
 */
class CallParametersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A skill that declares parameters, records what it is called with and answers from a function. */
    static final class Skill implements Tool {
        final String name;
        final String description;
        final Map<String, ToolParam> params;
        final Function<Map<String, Object>, ToolResult> body;
        final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

        Skill(String name, String description, Map<String, ToolParam> params,
              Function<Map<String, Object>, ToolResult> body) {
            this.name = name;
            this.description = description;
            this.params = params;
            this.body = body;
        }

        public String name() { return name; }
        public String description() { return description; }
        public Map<String, ToolParam> inputSchema() { return params; }
        public List<String> requiredCredentials() { return List.of(); }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
            calls.add(new LinkedHashMap<>(p));
            return body.apply(p);
        }
    }

    /** Parameters in the order a SKILL.yaml declares them. */
    static Map<String, ToolParam> declared(Object... pairs) {
        var m = new LinkedHashMap<String, ToolParam>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (ToolParam) pairs[i + 1]);
        return m;
    }

    static final String DIGEST = "{\"ok\":true,\"body_text\":\"Monday's headlines: the bridge reopens.\"}";

    /** The news digest: one optional parameter, as production's declares. */
    static Skill digest() {
        return new Skill("daily_news_digest", "Fetch and format today's news digest. Returns body_text and body_html.",
                declared("date", ToolParam.optional("string", "Target date YYYY-MM-DD, defaults to today UTC")),
                p -> ToolResult.success(DIGEST));
    }

    /** The mailer: its text is body_text, as production's is, and a call without it fails in the skill. */
    static Skill smtp() {
        return new Skill("smtp_send_email",
                "Send an email via SMTP using stored credentials (Gmail and generic SMTP). Keeps all existing behaviour.",
                declared("to", ToolParam.required("string", "Recipient email(s), comma-separated"),
                        "subject", ToolParam.required("string", "Email subject"),
                        "body_text", ToolParam.required("string", "Plain text email body (UTF-8)"),
                        "body_html", ToolParam.optional("string", "Optional HTML body (sent as multipart/alternative)")),
                p -> p.get("body_text") == null ? ToolResult.failure("ERROR: missing required parameter body_text")
                        : ToolResult.success("{\"ok\":true,\"message_id\":\"m-1\"}"));
    }

    static final String SMTP_PARAMETERS = String.join("\n",
            "  - to (string, required): Recipient email(s), comma-separated",
            "  - subject (string, required): Email subject",
            "  - body_text (string, required): Plain text email body (UTF-8)",
            "  - body_html (string, optional): Optional HTML body (sent as multipart/alternative)");

    /** The morning's call: the mail's text under 'body'. */
    static Map<String, Object> bodyAsBody() {
        var p = new LinkedHashMap<String, Object>();
        p.put("to", "owner@example.org");
        p.put("subject", "News");
        p.put("body", "{{call1.body_text}}");
        return p;
    }

    /** The same call with the name the skill declares. */
    static Map<String, Object> bodyAsBodyText() {
        var p = new LinkedHashMap<String, Object>();
        p.put("to", "owner@example.org");
        p.put("subject", "News");
        p.put("body_text", "{{call1.body_text}}");
        return p;
    }

    static LoopRig.Reply delegate(Map<String, Object> mail) {
        return call(AgentAction.DELEGATE, Map.of("calls", List.of(
                Map.of("tool", "daily_news_digest", "params", Map.of()),
                Map.of("tool", "smtp_send_email", "params", mail))));
    }

    static String delegateNote(LoopRig.Call think) {
        return think.config().tools().stream().filter(t -> AgentAction.DELEGATE.equals(t.name())).findFirst()
                .orElseThrow().description();
    }

    private LoopRig rig;

    @AfterEach
    void stopQueue() {
        if (rig != null) rig.queue.stop();
    }

    // ───────────────────────── what a model is told of a skill's parameters ─────────────────────────

    @Test
    @DisplayName("a skill's parameters are listed one to a line -- name, type, required or optional, the first sentence of its description -- every one, in the schema's order; a skill with none says so")
    void theLines() {
        assertEquals(SMTP_PARAMETERS, ToolFinder.parameters(smtp()));
        assertEquals("  (no parameters)", ToolFinder.parameters(ToolFinderTest.skill("web_fetch", "Fetch a page.", Map.of())));
        assertEquals("- daily_news_digest: Fetch and format today's news digest.\n"
                + "  - date (string, optional): Target date YYYY-MM-DD, defaults to today UTC", ToolFinder.listed(digest()));

        // Declared as the model that wrote the skill wrote it: a type a schema has under another
        // name, one it has not, a description of several sentences, of several lines, and none.
        Tool odd = ToolFinderTest.skill("ha_api", "Calls Home Assistant.", declared(
                "commands", ToolParam.required("List[dict]", "The commands to send. Each is an object with a type."),
                "when", ToolParam.optional("datetime", "When to run it\nISO 8601, local time"),
                "dry_run", ToolParam.optional("bool", null),
                "note", ToolParam.optional("", "   ")));
        assertEquals(String.join("\n",
                "  - commands (array, required): The commands to send.",
                "  - when (optional): When to run it",
                "  - dry_run (boolean, optional)",
                "  - note (optional)"), ToolFinder.parameters(odd));

        // Every one of them: a skill of production's declares 22.
        var many = new LinkedHashMap<String, ToolParam>();
        for (int i = 1; i <= 40; i++) many.put("p" + i, ToolParam.optional("integer", "Value " + i));
        assertEquals(40, ToolFinder.parameters(ToolFinderTest.skill("wide", "Takes many.", many)).lines().count());

        // The same bytes in every JVM: a map whose order is seeded anew each run is listed by name.
        assertEquals("  - a (string, optional)\n  - b (string, optional)\n  - c (string, optional)",
                ToolFinder.parameters(ToolFinderTest.skill("abc", "Three.", Map.of(
                        "c", ToolParam.optional("string", ""), "a", ToolParam.optional("string", ""),
                        "b", ToolParam.optional("string", "")))));
        // Mutations: list only the required ones -> body_html is missing from the first; print the
        // declared type as written -> "List[dict]" and "datetime"; the whole description -> "Each is
        // an object with a type."; stop after eight -> 8 lines of 40.
    }

    @Test
    @DisplayName("a parameter whose name begins with an underscore is the system's own: not listed, and not asked of a call that leaves it out")
    void systemNamesAreNotTheModels() {
        Tool reader = ToolFinderTest.skill("ocr_image_to_text", "Reads the text of an image.", declared(
                "_attached_files", ToolParam.required("array", "The files of the task."),
                "language", ToolParam.optional("string", "The language of the text")));

        assertEquals("  - language (string, optional): The language of the text", ToolFinder.parameters(reader));
        assertEquals(List.of(), LocalExecutor.misfit(reader, Map.of("language", "ces")),
                "what the system hands a skill is not the cloud's to pass");
        // Mutation: declared() keeps every declared name -> the line lists _attached_files, and a
        // call without it is refused for leaving out what only the system can give.
    }

    @Test
    @DisplayName("find_tools lists each match with its parameters under it, and says so of a skill with none; its first line and its paging are as they were")
    void whatFindToolsLists(@TempDir Path tmp) throws Exception {
        var tools = new java.util.ArrayList<Tool>(List.of(smtp(),
                ToolFinderTest.skill("imap_list_mailboxes", "List the mailboxes of the IMAP account.", Map.of())));
        for (int i = 0; i < 9; i++) {
            tools.add(ToolFinderTest.skill("unit_" + i, "Converts unit " + i + " into kelvins.",
                    declared("value", ToolParam.required("number", "The value to convert"))));
        }
        rig = new LoopRig(tmp, tools);
        rig.cloud.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "smtp imap")));
        rig.cloud.think.add(call(AgentAction.FIND_TOOLS, Map.of("query", "kelvins")));
        rig.cloud.think.add(respond("done"));

        AgentResult r = rig.turn(rig.chat.createSession("u1", "Mail"), "which skills send mail?");

        assertEquals(String.join("\n",
                "2 skills match \"smtp imap\". You can call them from your next step:",
                "- imap_list_mailboxes: List the mailboxes of the IMAP account.",
                "  (no parameters)",
                "- smtp_send_email: Send an email via SMTP using stored credentials (Gmail and generic SMTP).",
                SMTP_PARAMETERS), r.trajectory().turns().get(0).observation().output());
        assertTrue(TaskEndToEndTest.sentTo(rig, 1).contains("  - body_text (string, required): Plain text email body (UTF-8)"),
                "the cloud reads it: " + TaskEndToEndTest.sentTo(rig, 1));
        String paged = r.trajectory().turns().get(1).observation().output();
        assertTrue(paged.startsWith("9 skills match \"kelvins\"; these are 1 to 8, best first. You can call them from "
                + "your next step:\n- unit_0: Converts unit 0 into kelvins.\n  - value (number, required): The value "
                + "to convert\n- unit_1: "), paged);
        assertTrue(paged.endsWith("  - value (number, required): The value to convert\n"
                + "More: find_tools with the same query and page 2 (of 2)."), paged);
        // Mutation: list a match by its name and first sentence alone, as before -> no parameter
        // line in either answer.
    }

    // ───────────────────────── delegate's description ─────────────────────────

    @Test
    @DisplayName("delegate's description lists the parameters of each skill it names: unattended work's likeliest, and a chat's usual ones")
    void theNoteListsParameters() {
        var engine = new ThinkingEngine(new com.ownclaw.agent.tools.ToolRegistry(List.of(digest(), smtp())),
                new com.ownclaw.config.OwnClawConfig(), null);
        var orchestrating = new ThinkingEngine.StepMode(true, true, false);
        var run = new AgentContext("u1", "t1", "Daily news\nUsing daily_news_digest, send today's news with smtp_send_email.");
        run.setUnattended(true);
        engine.buildMessages(run, "anthropic", orchestrating);
        String scheduled = ChatCacheTest.delegate(engine, run);

        assertTrue(scheduled.endsWith("find_tools searches every skill, and lists the parameters of each it finds."
                + " The likeliest for this task:\n"
                + "- daily_news_digest: Fetch and format today's news digest.\n"
                + "  - date (string, optional): Target date YYYY-MM-DD, defaults to today UTC\n"
                + "- smtp_send_email: Send an email via SMTP using stored credentials (Gmail and generic SMTP).\n"
                + SMTP_PARAMETERS), scheduled);

        var chat = new AgentContext("u1", "t2", "send me the news");
        chat.setUsualTools(List.of("smtp_send_email"));
        engine.buildMessages(chat, "anthropic", orchestrating);
        assertTrue(ChatCacheTest.delegate(engine, chat).endsWith(" The owner's usual skills:\n"
                + "- smtp_send_email: Send an email via SMTP using stored credentials (Gmail and generic SMTP).\n"
                + SMTP_PARAMETERS), ChatCacheTest.delegate(engine, chat));
        // Mutation: the note names a skill by its first sentence alone, as before -> no parameter lines.
    }

    @Test
    @DisplayName("delegate's description is the same bytes on every step of a task, though the task's own skill_create rewrote a skill it names; the next task is told the skill as it is then")
    void theNoteHoldsThroughASkillCreate(@TempDir Path tmp) throws Exception {
        var mail = smtp();
        rig = SkillCreateGateTest.localFirst(tmp, List.of(digest(), mail));
        // What the task's skill_create makes of the skill: another first sentence, another parameter.
        var rewritten = new Skill("smtp_send_email", "Sends mail over SMTP, as plain text or HTML. Never retries.",
                declared("to", ToolParam.required("string", "Recipient email(s), comma-separated"),
                        "subject", ToolParam.required("string", "Email subject"),
                        "body_text", ToolParam.required("string", "Plain text email body (UTF-8)"),
                        "priority", ToolParam.optional("integer", "1 to 5")), mail.body);
        rig.skills.create = p -> {
            rig.registry.register(rewritten);
            return "Skill 'smtp_send_email' created and registered. It is now available as a tool.";
        };
        var spec = new LinkedHashMap<String, Object>();
        spec.put("name", "smtp_send_email");
        spec.put("description", "Sends mail over SMTP, as plain text or HTML. Never retries.");
        spec.put("parameters", "{}");
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, spec));
        rig.cloud.codegen.add(SkillCodegenTest.finished(SkillCodegenTest.module("")));
        // Then it calls the skill as it has just made it, with the parameter its note does not list.
        var withPriority = bodyAsBodyText();
        withPriority.put("priority", 1);
        rig.cloud.think.add(delegate(withPriority));
        rig.cloud.think.add(respond("Sent."));
        String news = "Daily news\nUsing daily_news_digest, send today's news with smtp_send_email.";

        AgentResult r = rig.loop.executeFull("u1", news, true);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var thinks = rig.cloud.calls("think");
        assertEquals(3, thinks.size());
        String first = delegateNote(thinks.get(0));
        assertTrue(first.contains("- smtp_send_email: Send an email via SMTP using stored credentials (Gmail and "
                + "generic SMTP).\n" + SMTP_PARAMETERS), first);
        assertFalse(first.contains("priority"), first);
        assertEquals("Sends mail over SMTP, as plain text or HTML.",
                ToolFinder.gist(rig.registry.find("smtp_send_email").orElseThrow().description()),
                "the premise: the skill the note quotes was rewritten by step 1");
        // The note is what the task was told at its start; a call is checked against the skill as
        // it is when the call runs.
        assertEquals(1, rewritten.calls.size(), "the call with the new parameter was refused: "
                + TaskEndToEndTest.sentTo(rig, 2));
        assertEquals(1, rewritten.calls.get(0).get("priority"));
        for (int i = 1; i < thinks.size(); i++) {
            assertEquals(first, delegateNote(thinks.get(i)), "request " + (i + 1) + " changed delegate's description");
            assertEquals(thinks.get(0).config().tools().stream().filter(t -> !t.deferred()).toList(),
                    thinks.get(i).config().tools().stream().filter(t -> !t.deferred()).toList(),
                    "request " + (i + 1) + " changed the tools the cached prefix begins with");
        }

        // The next task reads the registry anew.
        rig.cloud.think.add(delegate(bodyAsBodyText()));
        rig.cloud.think.add(respond("Sent."));
        rig.loop.executeFull("u1", news, true);
        String next = delegateNote(rig.cloud.calls("think").get(3));
        assertTrue(next.contains("- smtp_send_email: Sends mail over SMTP, as plain text or HTML.\n")
                && next.contains("\n  - priority (integer, optional): 1 to 5"), next);
        // Mutation: write the note anew on every step, as before -> requests 2 and 3 carry the
        // rewritten skill's sentence and parameters, and the whole conversation is cached again.
    }

    // ───────────────────────── a call that does not fit ─────────────────────────

    @Test
    @DisplayName("a call that names a parameter its skill does not declare, or leaves out one it requires, refuses the whole delegation before the first call runs -- the correct call before it too -- and the refusal lists the skill's parameters")
    void aCallThatDoesNotFitRefusesThemAll() {
        var digest = digest();
        var smtp = smtp();
        var usage = new DelegationBehaviourTest.Usage();
        var ctx = task();

        var outcome = executor(new DelegationBehaviourTest.Scripted(), usage, digest, smtp).execute(
                calls("", c("daily_news_digest", Map.of()), c("smtp_send_email", bodyAsBody())), ctx, UNCOUNTED);

        assertEquals(List.of(), digest.calls, "the first call ran, though the delegation was refused");
        assertEquals(List.of(), smtp.calls);
        assertFalse(outcome.ok());
        assertTrue(outcome.byCode(), "the cloud's to correct: the local tier had no part in it");
        assertEquals(0, outcome.stepCount());
        assertEquals(List.of(), outcome.produced(), "a refused call left a result");
        assertEquals(List.of(), ctx.artifacts());
        assertEquals(List.of(), usage.failedErrors, "a refusal is no failure of the skill");
        assertEquals("Not run: one of the 2 calls does not fit the parameters its skill declares, so none of them was "
                + "run -- every call is checked before the first one runs:\n"
                + "- call 2 (smtp_send_email): 'body' is not one of its parameters; 'body_text' is required and "
                + "was left out.\n"
                + "Parameters of smtp_send_email:\n" + SMTP_PARAMETERS + "\n"
                + "Send the calls again with these names -- all of them: nothing has run.\n\n"
                + "[No tool was executed during this delegation.]", outcome.text());
        // Mutation: runCalls without its check -> the digest runs, then the mailer fails by itself.
    }

    @Test
    @DisplayName("what fits and what does not: only the names of a call's own arguments are read, a reference is a value, null is no value, and every call that does not fit is said at once")
    void whatFits() {
        var smtp = smtp();
        var digest = digest();
        assertEquals(List.of(), LocalExecutor.misfit(smtp, bodyAsBodyText()), "body_text given as a reference is given");
        assertEquals(List.of(), LocalExecutor.misfit(digest, Map.of()), "an optional parameter may be left out");
        var nested = bodyAsBodyText();
        nested.put("body_html", Map.of("body", "<p>{{call1.body}}</p>", "_inline", true));
        assertEquals(List.of(), LocalExecutor.misfit(smtp, nested), "a name inside a value is no parameter's name");
        var asNull = bodyAsBodyText();
        asNull.put("subject", null);
        assertEquals(List.of("'subject' is required and was given no value"), LocalExecutor.misfit(smtp, asNull));
        assertEquals(List.of("'{{call1.body_text}}' is not one of its parameters", "'to' is required and was left out",
                        "'subject' is required and was left out", "'body_text' is required and was left out"),
                LocalExecutor.misfit(smtp, Map.of("{{call1.body_text}}", "x")), "a reference written as a name names nothing");
        assertEquals(List.of("'_attached_files' is not yours to pass: a name that begins with _ is the system's own"),
                LocalExecutor.misfit(digest, Map.of("_attached_files", List.of())));

        // Two calls that do not fit, of two skills: both are said, each skill's parameters once.
        var outcome = executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), digest, smtp)
                .execute(calls("Then tell the owner it went out.", c("daily_news_digest", Map.of("day", "today")),
                        c("smtp_send_email", bodyAsBody()), c("smtp_send_email", Map.of("to", "owner@example.org"))),
                        task(), UNCOUNTED);
        assertEquals("Not run: 3 of the 3 calls do not fit the parameters their skills declare, so none of them was "
                + "run -- every call is checked before the first one runs:\n"
                + "- call 1 (daily_news_digest): 'day' is not one of its parameters.\n"
                + "- call 2 (smtp_send_email): 'body' is not one of its parameters; 'body_text' is required and "
                + "was left out.\n"
                + "- call 3 (smtp_send_email): 'subject' is required and was left out; 'body_text' is required and "
                + "was left out.\n"
                + "Parameters of daily_news_digest:\n  - date (string, optional): Target date YYYY-MM-DD, defaults to "
                + "today UTC\n"
                + "Parameters of smtp_send_email:\n" + SMTP_PARAMETERS + "\n"
                + "Send the calls again with these names -- all of them: nothing has run. The local model was not "
                + "given the goal.\n\n[No tool was executed during this delegation.]", outcome.text());
        assertEquals(List.of(), digest.calls);
        // Mutations: read a value's keys as names too -> the nested call is refused; count a null as
        // given -> asNull fits; stop at the first call that does not fit -> calls 2 and 3 go unsaid;
        // leave a name that begins with _ to the other rules -> it is refused as a name the digest
        // does not declare, and passes a skill that declares nothing.
    }

    @Test
    @DisplayName("a skill that declares no parameters is not held to its names -- there is nothing to check them against -- and a call of the local model's own is not checked at all")
    void whatIsNotChecked() {
        // Declares nothing, as a skill whose declaration the registry could not read does.
        var undeclared = new Skill("legacy_report", "Builds the report.", Map.of(), p -> ToolResult.success("built"));
        var byCode = executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), undeclared)
                .execute(calls("", c("legacy_report", Map.of("month", "2026-10"))), task(), UNCOUNTED);
        assertTrue(byCode.ok(), byCode.text());
        assertEquals(List.of(Map.of("month", "2026-10")), undeclared.calls);
        // A system name is still not the cloud's to write, whatever the skill declares.
        var system = executor(new DelegationBehaviourTest.Scripted(), new DelegationBehaviourTest.Usage(), undeclared)
                .execute(calls("", c("legacy_report", Map.of("_credentials", Map.of("SMTP_PASS", "x")))), task(), UNCOUNTED);
        assertEquals("Not run: the call does not fit the parameters its skill declares, so it was not run:\n"
                + "- call 1 (legacy_report): '_credentials' is not yours to pass: a name that begins with _ is the "
                + "system's own.\nParameters of legacy_report:\n  (no parameters)\n"
                + "Send the call again with these names.\n\n[No tool was executed during this delegation.]", system.text());
        assertEquals(1, undeclared.calls.size(), "the call ran with the system's name in it");

        // The cloud names the tools and gives a goal: the calls are the local model's, made with the
        // schema it is offered, and go through the guards its calls always went through.
        var smtp = smtp();
        var local = new DelegationBehaviourTest.Scripted(
                DelegationBehaviourTest.call("smtp_send_email", Map.of("to", "owner@example.org", "body", "Hello")),
                DelegationBehaviourTest.done("the mailer wanted body_text"));
        var byGoal = executor(local, new DelegationBehaviourTest.Usage(), smtp)
                .execute(new DelegationPlan("Mail the owner a greeting.", List.of(), List.of(), List.of("smtp_send_email")),
                        task(), UNCOUNTED);
        assertEquals(1, smtp.calls.size(), "the local model's own call ran, and the skill answered it: " + byGoal.text());
        assertTrue(local.allSeen().contains("ERROR: missing required parameter body_text"), local.allSeen());
        // Mutation: hold a skill with no declared parameters to the second rule -> 'month' is
        // refused, and every skill whose declaration was lost can no longer be called by code.
    }

    @Test
    @DisplayName("through the loop, unattended: the refused delegation is a step that ran nothing -- no result, no row of the skill's use, nothing for self-repair -- and the cloud reads the skill's parameters in what it is told")
    void aRefusalLeavesNothingToRepair(@TempDir Path tmp) throws Exception {
        var digest = digest();
        var smtp = smtp();
        rig = new LoopRig(tmp, List.of(digest, smtp));
        rig.skills.source = name -> "def run(params):\n    return {'output': 'ran'}\n";
        var repair = new SkillSelfRepair(rig.jdbc, rig.config, rig.skills, rig.registry, rig.queue,
                new ResultDelivery(rig.chat, rig.emitter), rig.chat, rig.events, ZoneId.of("UTC"));
        repair.setCancellation(rig.cancellation);
        rig.loop.setSelfRepair(repair);
        rig.cloud.think.add(delegate(bodyAsBody()));
        rig.cloud.think.add(respond("The news could not be mailed."));
        var ctx = new AgentContext("u1", "0param01", "Daily news\nUsing daily_news_digest, send today's news with smtp_send_email.");
        ctx.setUnattended(true);

        AgentResult r = rig.loop.run(ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        assertEquals(List.of(), digest.calls, "not even the first call ran");
        assertEquals(List.of(), smtp.calls);
        assertEquals(List.of(), ctx.artifacts(), "a refused call left a result");
        assertEquals(Map.of(), SkillSelfRepair.found(ctx, r, (name, user) -> true), "nothing ran: nothing to repair");
        assertEquals(List.of(), rig.jdbc.queryForList("SELECT * FROM skill_repairs"));
        assertFalse(r.response().contains("Self-repair"), r.response());
        assertEquals(0, rig.jdbc.queryForObject("SELECT COUNT(*) FROM skill_usage", Integer.class),
                "a refusal is no use of a skill, and no failure of one");
        String told = TaskEndToEndTest.sentTo(rig, 1);
        assertTrue(told.contains("- call 2 (smtp_send_email): 'body' is not one of its parameters; 'body_text' is "
                + "required and was left out.\nParameters of smtp_send_email:\n" + SMTP_PARAMETERS), told);
        JsonNode step = JSON.readTree(rig.jdbc.queryForObject("SELECT details FROM events WHERE event_type = 'step' "
                + "AND json_extract(details, '$.tool') = 'delegate'", String.class));
        assertTrue(step.path("notStarted").asBoolean(), "the page says nobody carried it out: " + step);
        assertFalse(step.has("artifacts") || step.has("artifact"), step.toString());
        assertFalse(step.path("success").asBoolean(), step.toString());
        assertTrue(step.path("reason").asText().startsWith("Not run: one of the 2 calls does not fit the parameters "
                + "its skill declares, so none of them was run"), step.toString());
        // Mutation: runCalls without its check -> the digest runs, the mailer fails by itself, its
        // failed result is the last of its calls, and found() names smtp_send_email for a repair.
    }

    @Test
    @DisplayName("the morning's news run, end to end: told the mailer's parameters, a first delegation that still writes 'body' runs nothing, and the corrected one runs the digest once and mails its text")
    void theMorningRun(@TempDir Path tmp) throws Exception {
        var digest = digest();
        var smtp = smtp();
        rig = SkillCreateGateTest.localFirst(tmp, List.of(digest, smtp));
        rig.cloud.think.add(delegate(bodyAsBody()));
        rig.cloud.think.add(delegate(bodyAsBodyText()));
        rig.cloud.think.add(respond("The news went out."));
        var ctx = new AgentContext("u1", "0param02", "Daily news\nUsing daily_news_digest, send today's news with smtp_send_email.");
        ctx.setUnattended(true);

        AgentResult r = rig.loop.run(ctx);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var thinks = rig.cloud.calls("think");
        assertEquals(3, thinks.size());
        // The cloud is given no skill to call, and no schema of one: what it has is delegate's own list.
        List<ToolSpec> offered = ToolSpec.offered(thinks.get(0).config().tools(), thinks.get(0).messages());
        assertTrue(offered.stream().noneMatch(t -> "smtp_send_email".equals(t.name())), "the premise: " + offered);
        assertTrue(delegateNote(thinks.get(0)).contains("- smtp_send_email: Send an email via SMTP using stored "
                + "credentials (Gmail and generic SMTP).\n" + SMTP_PARAMETERS), delegateNote(thinks.get(0)));
        // The first delegation ran nothing, and said what the mailer takes.
        assertTrue(TaskEndToEndTest.sentTo(rig, 1).contains("- call 2 (smtp_send_email): 'body' is not one of its "
                + "parameters; 'body_text' is required and was left out."), TaskEndToEndTest.sentTo(rig, 1));
        // The corrected one ran both, once each.
        assertEquals(List.of(Map.of()), digest.calls, "the digest ran for the refused delegation too");
        assertEquals(1, smtp.calls.size());
        assertEquals("Monday's headlines: the bridge reopens.", smtp.calls.get(0).get("body_text"));
        assertEquals(List.of("daily_news_digest", "smtp_send_email"), ctx.artifacts().stream().map(Artifact::tool).toList());
        assertTrue(ctx.artifacts().stream().allMatch(Artifact::succeeded));
        assertEquals(Map.of(), SkillSelfRepair.found(ctx, r, (name, user) -> true));
        var steps = rig.jdbc.queryForList("SELECT details FROM events WHERE event_type = 'step' ORDER BY id", String.class);
        assertEquals(2, steps.size());
        assertTrue(JSON.readTree(steps.get(0)).path("notStarted").asBoolean(), steps.get(0));
        assertTrue(JSON.readTree(steps.get(1)).path("success").asBoolean(), steps.get(1));
        assertEquals(2, JSON.readTree(steps.get(1)).path("artifacts").size(), steps.get(1));
        // Mutation: runCalls without its check -> the first delegation runs the digest and fails in
        // the mailer, as every morning: the digest runs twice.
    }
}
