package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolSchemas;
import com.ownclaw.llm.ToolSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The actions the loop handles itself, described so a provider can offer them natively.
 *
 * <p>These are not {@link com.ownclaw.agent.tools.Tool} beans and never will be: the owner
 * removed built-in Java tools by decree, and every real capability is a Python skill the agent
 * writes at runtime. But {@link AgentLoop} branches on these eight names before it ever consults
 * the registry, so a model offered only registry tools could not answer, ask a question or write
 * a skill. They are declared here purely so the provider knows they exist.
 *
 * <p>The descriptions are the prose already in the system prompt, restated as structure. When
 * native tools are on, that prose is omitted from the prompt — otherwise every action is
 * described twice and the change costs tokens instead of saving them.
 */
public final class SpecialActionSchemas {

    private SpecialActionSchemas() { /* static only */ }

    private static ToolSpec spec(String name, String description, Map<String, ToolParam> params) {
        return new ToolSpec(name, description, ToolSchemas.toJsonSchema(params));
    }

    private static Map<String, ToolParam> params(Object... pairs) {
        var m = new LinkedHashMap<String, ToolParam>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (ToolParam) pairs[i + 1]);
        }
        return m;
    }

    /** Every action {@link AgentLoop} handles before consulting the tool registry. */
    public static final List<ToolSpec> ALL = List.of(

            spec(AgentAction.RESPOND,
                    "Deliver the final answer to the user and end the task. Put the whole answer "
                            + "in 'message' — it is what the user reads.",
                    params("message", ToolParam.required("string", "The complete answer."))),

            spec(AgentAction.ASK_USER,
                    "Ask the user one question and stop until they answer. Only when you genuinely "
                            + "cannot proceed: on unattended work nobody is there to reply, so "
                            + "prefer deciding and stating the assumption.",
                    params("message", ToolParam.required("string", "The question to ask."))),

            // No 'code' parameter, deliberately. AgentLoop calls generateSkillCode
            // before SkillManager ever sees these params and injects the code it produced, so
            // anything the model writes here is discarded -- it was being asked to generate a
            // whole Python module on every skill_create for nothing. The description is the
            // specification; that is the thing to spend output tokens on.
            spec(AgentAction.SKILL_CREATE,
                    "Create or replace a Python skill, which then becomes a tool you can call. "
                            + "You do NOT write the code: describe the behaviour and it is "
                            + "generated. Specify WHAT, not HOW, and think about edge cases, "
                            + "output format and failure modes first — rework is expensive. Reuse "
                            + "the SAME name to fix an existing skill; never _v2, _fixed or _new. "
                            + "Any OS package or library is installable, so never claim something "
                            + "is unavailable.",
                    params(
                            "name", ToolParam.required("string", "Skill name: lowercase, underscores."),
                            "description", ToolParam.required("string",
                                    "The behaviour spec: what it does, edge cases, output format."),
                            // A JSON *string*, not a nested object: SkillManager.createSkill parses
                            // it that way, and changing that is a different change wearing this
                            // one's clothes.
                            // Required, because SkillManager rejects a missing value outright
                            // -- and the prose that used to say so was removed from the prompt
                            // when the tools array took over describing these. The cost of the
                            // gap is not a clear error either: AgentLoop generates the whole
                            // Python module with a cloud call FIRST, and only then finds out.
                            "parameters", ToolParam.required("string",
                                    "JSON object of parameter definitions, as a string: "
                                            + "{\"arg\": {\"type\": ..., \"description\": ..., "
                                            + "\"required\": ...}}. Pass {} for a skill that "
                                            + "takes no arguments."),
                            "requirements", ToolParam.optional("string", "pip requirements, one per line."),
                            "credentials", ToolParam.optional("string", "Comma-separated vault key names."),
                            "system_packages", ToolParam.optional("string",
                                    "Space-separated apt packages, installed into the container."),
                            "container_image", ToolParam.optional("string",
                                    "Docker base image. Omit unless a specific one is needed."),
                            "requires_network", ToolParam.optional("boolean", "Does it reach the network?"),
                            "has_side_effects", ToolParam.optional("boolean", "Does it change anything?"),
                            "timeout", ToolParam.optional("integer", "Seconds before it is killed."))),

            spec(AgentAction.SKILL_MANAGE,
                    "Inspect or remove skills: list them, read one's source, or delete one.",
                    params(
                            "action", ToolParam.required("string", "list | read | delete"),
                            "name", ToolParam.optional("string", "Skill name, for read and delete."))),

            spec(AgentAction.CREDENTIAL_MANAGE,
                    "List or check stored credentials. Never carries a secret VALUE: to store one, "
                            + "tell the user to type '/cred set KEY value', which writes straight "
                            + "to the vault without the secret passing through you.",
                    params(
                            "action", ToolParam.required("string", "list | check"),
                            "key", ToolParam.optional("string", "Vault key name, for check."))),

            spec(AgentAction.MEMORY_MANAGE,
                    "Store, list or delete facts that should survive this conversation, or recall "
                            + "past tasks. Of past tasks you are shown only this chat's, under Prior "
                            + "Context: recall returns every past task whose record matches the "
                            + "query, each in full, the most relevant first.",
                    params(
                            "action", ToolParam.required("string", "store | list | delete | recall"),
                            "key", ToolParam.optional("string", "Identifier for the fact."),
                            "content", ToolParam.optional("string", "The fact, for store."),
                            "query", ToolParam.optional("string", "What to look for, for recall."))),

            spec(AgentAction.SCHEDULE_MANAGE,
                    "Schedule work for later, or manage what is already scheduled.",
                    params(
                            "action", ToolParam.required("string",
                                    "schedule_once | schedule_recurring | list | cancel | pause | resume"),
                            "description", ToolParam.optional("string",
                                    "The task to run, as a self-contained message: the run does not see this chat."),
                            "time", ToolParam.optional("string", "When, in natural language."),
                            "schedule", ToolParam.optional("string", "Recurrence, natural language or cron."),
                            "max_runs", ToolParam.optional("integer", "Stop after this many runs."),
                            "task_id", ToolParam.optional("integer", "Which task, for cancel/pause/resume."))),

            spec(AgentAction.DELEGATE,
                    // The cloud's; the local model running a task itself is given its own below.
                    "Hand a sub-goal to the local model, which runs it on this machine with the "
                            + "tools you name and your credentials, and costs nothing. It reads "
                            + "what you cannot: a private result or a file the user sent reaches "
                            + "you only as a description, so to read, summarise, search, compare "
                            + "or answer a question about one, delegate and name its handle "
                            + "({{N}}) in the goal -- every earlier result the goal names is given "
                            + "to it whole, and it sees no other. Its answer comes back to you -- "
                            + "as a handle to pass on when it quotes private data. Never create a "
                            + "skill only to read or summarise data: a skill is for a deterministic program "
                            + "(parsing at scale, changing configuration, repeated runs). For work "
                            + "on this machine, the LAN and its servers, delegate a sequence of "
                            + "tool calls it can run. Speed: it reads about 100 tokens a second "
                            + "and writes about 8, so reading a long result or writing a long "
                            + "answer takes minutes. Give it a goal; it works out the steps. "
                            + "Observations name results as {{N}}. When you have a tool that "
                            + "takes a result, put {{N}} or {{N.field}} in that argument -- as the "
                            + "whole value, or inside text such as a message body -- and the result "
                            + "is filled in when the call runs, without your reading it.",
                    params(
                            "goal", ToolParam.required("string", "What to achieve, stated fully, "
                                    + "with the handle of each earlier result it should read."),
                            // A string, not an array: OpenAI rejects an array schema with no
                            // items, and one bad schema fails every request that carries it.
                            "tools", ToolParam.optional("string", "Comma-separated exact names of "
                                    + "the tools it will need. Only these, and any the goal or an "
                                    + "unattended (scheduled or /bg) task names, are loaded: every tool "
                                    + "definition takes room in the local model's context that the work needs."))));

    /**
     * The local model running a task itself is given the tools likeliest to fit it, not all of
     * them ({@code ThinkingEngine#toolsFor}); this finds the rest. Never offered to the cloud.
     */
    static final ToolSpec FIND_TOOLS = spec(AgentAction.FIND_TOOLS,
            "Search all the skills for what you need -- 'send email', 'router wifi settings', 'fetch a web "
                    + "page' -- by English keywords. The matches are listed best first, " + ToolFinder.PAGE
                    + " at a time, and you can call each of them from your next step, with its whole "
                    + "description and parameters. Search before creating a skill: one may already exist.",
            params(
                    "query", ToolParam.required("string", "What the tool should do, in English keywords."),
                    "page", ToolParam.optional("integer", "Which page of the matches, from 1: an answer "
                            + "says how many matches there are.")));

    /**
     * Delegate, as the local model running a task itself reads it ({@code ThinkingEngine#toolsFor}):
     * every skill is its own to call, so the work is its own, and a delegation is a run of itself --
     * the way a private result, which it too sees only as a description, is read.
     */
    static final String DELEGATE_ON_THE_LOCAL_MODEL = "Hand a sub-goal to a separate run of the local "
            + "model. You are the local model too, and every skill is yours to call -- find_tools finds "
            + "the ones you were not given -- so do the work yourself; delegate only to read private data. "
            + "A private result or a file the user sent reaches you only as a description: to read, summarise, search, compare or answer a question about one, delegate "
            + "and name its handle ({{N}}) in the goal -- every earlier result the goal names is given to "
            + "it whole, and it sees no other. Its answer comes back to you -- as a handle to pass on when "
            + "it quotes private data. Observations name results as {{N}}. When you have a tool that takes "
            + "a result, put {{N}} or {{N.field}} in that argument -- as the whole value, or inside text "
            + "such as a message body -- and the result is filled in when the call runs, without your "
            + "reading it.";
}
