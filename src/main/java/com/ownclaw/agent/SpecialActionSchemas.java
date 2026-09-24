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

            // No 'code' parameter, deliberately. AgentLoop calls generateSkillCodeWithCloud
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
                    "Store, list or delete facts that should survive this conversation.",
                    params(
                            "action", ToolParam.required("string", "store | list | delete"),
                            "key", ToolParam.optional("string", "Identifier for the fact."),
                            "content", ToolParam.optional("string", "The fact, for store."))),

            spec(AgentAction.SCHEDULE_MANAGE,
                    "Schedule work for later, or manage what is already scheduled.",
                    params(
                            "action", ToolParam.required("string",
                                    "schedule_once | schedule_recurring | list | cancel | pause | resume"),
                            "description", ToolParam.optional("string", "The task to run, as a message."),
                            "time", ToolParam.optional("string", "When, in natural language."),
                            "schedule", ToolParam.optional("string", "Recurrence, natural language or cron."),
                            "max_runs", ToolParam.optional("integer", "Stop after this many runs."),
                            "task_id", ToolParam.optional("integer", "Which task, for cancel/pause/resume."))),

            spec(AgentAction.DELEGATE,
                    "Hand a sub-goal to the local model, which runs it on this machine with the "
                            + "tools you name and your credentials, and costs nothing. Best for work "
                            + "on this machine, the LAN, servers and private data. About a minute "
                            + "per step, so prefer it when nobody is waiting. Give it a goal; it "
                            + "works out the steps. A delegation starts with no results and "
                            + "cannot see earlier ones, so say in words what it should fetch. "
                            + "Observations name results as {{N}}. When you have a tool that "
                            + "takes a result, put {{N}} or {{N.field}} as the whole value of that "
                            + "argument to pass it on verbatim without reading it.",
                    params(
                            "goal", ToolParam.required("string", "What to achieve, stated fully."),
                            // A string, not an array: OpenAI rejects an array schema with no
                            // items, and one bad schema fails every request that carries it.
                            "tools", ToolParam.optional("string", "Comma-separated exact names of "
                                    + "the tools it will need. Only these, and any the goal or a "
                                    + "scheduled task names, are loaded: the local model's context is small, and every "
                                    + "tool definition takes room it needs for the work."),
                            "max_steps", ToolParam.optional("integer", "Step ceiling, default 10."))));
}
