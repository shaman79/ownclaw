package com.ownclaw.agent;

import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.interfaces.web.SkillController;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import com.ownclaw.users.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Declarations of a skill's parameters that the reader of them once took for something they are
 * not, in full ({@code DynamicSkillRegistry#readParameters}): the cases of its second review (18
 * Nov 2026), each of which ran, or read right, before calls were held to a declaration at all.
 * A declaration is read in full only in a shape nothing else can be taken for; in doubt a call is
 * held to no name, and no name is listed. {@link SkillDeclarationTest} holds the reader's rules.
 */
class DeclarationInDoubtTest {

    /** The real skill_create over a registry of its own, its jail off. */
    static Tool make(Path tmp, String name, Object parameters, String code) throws Exception {
        Path generated = Files.createDirectories(tmp.resolve("generated"));
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(generated.toString());
        cfg.getSandbox().setPythonPath("python3");
        var jail = JailTestKit.off();
        var pyEnv = new PythonEnvironmentService(cfg, jail);
        pyEnv.init();
        var sandbox = new ProcessSandbox(jail);
        var registry = new ToolRegistry(List.of());
        var skills = new SkillManager(new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null),
                registry, cfg, sandbox, pyEnv, null, null);
        var p = new LinkedHashMap<String, Object>();
        p.put("name", name);
        p.put("description", "A skill of the test's.");
        p.put("code", code);
        p.put("parameters", parameters);
        String said = skills.createSkill(p);
        assertTrue(said.startsWith("Skill '" + name + "' created"), said);
        return registry.find(name).orElseThrow();
    }

    /** A skill as it stands on disk, loaded as the registry loads it. */
    static DynamicSkill onDisk(Path tmp, String name, String code, String... yaml) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        var lines = new ArrayList<>(List.of("name: " + name, "description: A skill of the test's."));
        lines.addAll(List.of(yaml));
        Files.writeString(dir.resolve("SKILL.yaml"), String.join("\n", lines) + "\n");
        Files.writeString(dir.resolve("skill.py"), code);
        return new DynamicSkillRegistry(null, null, null, null, null, null, null).loadSkill(dir);
    }

    static AgentObservation delegated(Path tmp, Tool skill, Map<String, Object> params) throws Exception {
        var rig = new LoopRig(tmp.resolve("rig"), List.of(skill));
        try {
            rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                    List.of(Map.of("tool", skill.name(), "params", params)))));
            rig.cloud.think.add(respond("Done."));
            AgentResult r = rig.turn(rig.chat.createSession("u1", "Declaration"), "do it");
            return r.trajectory().turns().get(0).observation();
        } finally {
            rig.queue.stop();
        }
    }

    /** Takes whatever it is handed: it reads no name by itself. */
    static final String FREE_FORM = String.join("\n",
            "def run(params):",
            "    kept = sorted(k for k in params if not k.startswith('_'))",
            "    return {'output': 'stored ' + ', '.join(kept), 'success': True}",
            "");

    @Test
    @DisplayName("a skill that takes any name, declared as a schema says that -- {\"type\": \"object\", \"additionalProperties\": true} -- is called with its names")
    void aFreeFormSkill(@TempDir Path tmp) throws Exception {
        Tool skill = make(tmp, "note_store", "{\"type\": \"object\", \"additionalProperties\": true}", FREE_FORM);
        AgentObservation obs = delegated(tmp, skill, Map.of("city", "Brno", "units", "metric"));
        assertTrue(obs.success() && obs.output().contains("stored city, units"), obs.output());
    }

    @Test
    @DisplayName("the same declared {\"type\": \"object\"} and nothing more")
    void aFreeFormSkillTypeAlone(@TempDir Path tmp) throws Exception {
        Tool skill = make(tmp, "note_store", "{\"type\": \"object\"}", FREE_FORM);
        AgentObservation obs = delegated(tmp, skill, Map.of("city", "Brno"));
        assertTrue(obs.success() && obs.output().contains("stored city"), obs.output());
    }

    /** Declares query and max_results; a helper builds the request's query in a dict it calls params. */
    static final String SEARCH = String.join("\n",
            "def _request(query, max_results):",
            "    params = {'q': query}",
            "    params['limit'] = max_results or 10",
            "    return params",
            "",
            "def run(params):",
            "    sent = _request(params['query'], params.get('max_results'))",
            "    return {'output': 'asked for ' + str(sent['limit']) + ' results', 'success': True}",
            "");

    @Test
    @DisplayName("a name the skill ignores -- 'limit' for max_results -- is refused, though a helper of the skill writes that key into a dict of its own called params")
    void aNameTheSkillIgnores(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "web_search", SEARCH,
                "parameters:",
                "  query:",
                "    type: string",
                "    required: true",
                "  max_results:",
                "    type: integer");
        var params = new LinkedHashMap<String, Object>();
        params.put("query", "bridges");
        params.put("limit", 50);
        assertEquals(List.of("'limit' is not one of its parameters"), LocalExecutor.misfit(skill, params));
    }

    @Test
    @DisplayName("a map of names that says none is required the schema's way -- \"required\": [] -- has no parameter called required")
    void noneRequired(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "page_fetch", FREE_FORM,
                "parameters:",
                "  url:",
                "    type: string",
                "    description: The page",
                "  required: []");
        assertEquals(List.of("url"), List.copyOf(skill.inputSchema().keySet()));
    }

    @Test
    @DisplayName("parameters called type and properties, the required one listed the schema's way, are those two parameters")
    void typeAndProperties(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "entity_create", FREE_FORM,
                "parameters:",
                "  type:",
                "    type: string",
                "    description: The kind of entity",
                "  properties:",
                "    type: object",
                "    description: Its attributes",
                "  required: [type]");
        assertEquals(List.of("type", "properties"), List.copyOf(skill.inputSchema().keySet()));
        assertEquals("string", skill.inputSchema().get("type").type());
    }

    @Test
    @DisplayName("a parameter called properties whose definition lists its fields, beside another parameter, loses neither")
    void propertiesByItsFields(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "page_create", FREE_FORM,
                "parameters:",
                "  database_id:",
                "    type: string",
                "    description: Where the page goes",
                "  properties:",
                "    Name:",
                "      type: string",
                "    Status:",
                "      type: string");
        assertEquals(List.of("database_id", "properties"), List.copyOf(skill.inputSchema().keySet()));
    }

    @Test
    @DisplayName("and where one of those fields is marked required, a call that hands the parameter its fields is not refused for leaving the field out beside it")
    void propertiesByItsFieldsOneRequired(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "page_create", FREE_FORM,
                "parameters:",
                "  database_id:",
                "    type: string",
                "    description: Where the page goes",
                "    required: true",
                "  properties:",
                "    Name:",
                "      type: string",
                "      required: true",
                "    Status:",
                "      type: string");
        var params = new LinkedHashMap<String, Object>();
        params.put("database_id", "d1");
        params.put("properties", Map.of("Name", "Minutes", "Status", "Draft"));
        assertEquals(List.of(), LocalExecutor.misfit(skill, params));
    }

    /** Declares url by a schema that also takes any header_ name: more than its properties say. */
    static final String WITH_HEADERS = String.join("\n",
            "def run(params):",
            "    url = params['url']",
            "    headers = {k[7:]: v for k, v in params.items() if k.startswith('header_')}",
            "    return {'output': 'fetched ' + url + ' with ' + str(sorted(headers)), 'success': True}",
            "");

    @Test
    @DisplayName("a declaration not read in full is still one after the skill is saved from the owner's page, unchanged: the page cannot edit parameters, and the save keeps the file's own")
    void savedFromThePageUnchanged(@TempDir Path tmp) throws Exception {
        OwnClawConfig cfg = new OwnClawConfig();
        cfg.getSkills().setGeneratedPath(Files.createDirectories(tmp.resolve("generated")).toString());
        cfg.getSandbox().setPythonPath("python3");
        var jail = JailTestKit.off();
        var pyEnv = new PythonEnvironmentService(cfg, jail);
        pyEnv.init();
        var sandbox = new ProcessSandbox(jail);
        var registry = new ToolRegistry(List.of());
        var dynamic = new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null);
        var skills = new SkillManager(dynamic, registry, cfg, sandbox, pyEnv, null, null);
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public boolean isOwner(String userId) { return "owner".equals(userId); }
        };
        var page = new SkillController(skills, dynamic, null, auth);

        String schema = "{\"type\": \"object\", \"properties\": {\"url\": {\"type\": \"string\"}}, "
                + "\"required\": [\"url\"], \"patternProperties\": {\"^header_\": {\"type\": \"string\"}}}";
        var create = new LinkedHashMap<String, Object>();
        create.put("name", "page_fetch");
        create.put("description", "Fetch a page and say so.");
        create.put("code", WITH_HEADERS);
        create.put("parameters", schema);
        assertTrue(skills.createSkill(create).startsWith("Skill 'page_fetch' created"));
        DynamicSkill skill = (DynamicSkill) registry.find("page_fetch").orElseThrow();
        var call = Map.<String, Object>of("url", "https://example.org", "header_accept", "text/html");
        assertFalse(skill.declaredInFull(), "the premise");
        assertEquals(List.of(), LocalExecutor.misfit(skill, call), "the premise");

        // The page saves the skill with what it was shown of it: its description, its code, and
        // for its parameters the registry's reading of them (SkillController#getSkill).
        var shown = new LinkedHashMap<String, Object>();
        skill.inputSchema().forEach((name, param) -> {
            var def = new LinkedHashMap<String, Object>();
            def.put("type", param.type());
            def.put("description", param.description());
            def.put("required", param.required());
            shown.put(name, def);
        });
        var save = new LinkedHashMap<String, Object>();
        save.put("description", "Fetch a page and say so.");
        save.put("code", WITH_HEADERS);
        save.put("parameters", shown);
        Map<String, Object> saved = page.updateSkill("page_fetch", save, "owner").getBody();
        assertTrue(String.valueOf(saved.get("message")).startsWith("Skill 'page_fetch' updated"), String.valueOf(saved));

        DynamicSkill after = (DynamicSkill) registry.find("page_fetch").orElseThrow();
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().readValue(schema, Object.class),
                dynamic.declaredAsWritten(after), "the file's own parameters, as they stood");
        assertFalse(after.declaredInFull(), "the save made the part that was read the whole declaration");
        assertEquals(List.of(), LocalExecutor.misfit(after, call));
        // Mutation: the save writes the parameters the page sends back, as it did -> SKILL.yaml
        // holds what was read, url alone, read in full, and header_accept is refused.
    }

    private static Object json(String text) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Object.class);
    }

    @Test
    @DisplayName("one name alone that a declaration is wrapped in is in doubt: a schema in shorthand whose properties are called title and description, a parameter called properties defined by its fields, a declaration under 'parameters' -- and beside another parameter such a name is a parameter like any other")
    void aWrapperAloneIsInDoubt() throws Exception {
        for (String declared : List.of(
                "{\"properties\": {\"title\": \"string\", \"description\": \"string\", \"date\": \"string\"}}",
                "{\"$schema\": \"https://example.org/schema\", \"properties\": {\"title\": \"string\", \"description\": \"string\"}}",
                "{\"properties\": {\"email\": {\"type\": \"string\", \"required\": true}, \"firstname\": {\"type\": \"string\"}}}",
                "{\"properties\": {\"url\": {\"type\": \"string\"}}}",
                "{\"parameters\": {\"url\": {\"type\": \"string\", \"required\": true}}}",
                "{\"input_schema\": {\"type\": \"object\", \"properties\": {\"url\": {\"type\": \"string\"}}}}",
                "{\"schema\": {\"type\": \"object\", \"description\": \"What it takes\"}}")) {
            assertFalse(DynamicSkillRegistry.readParameters(json(declared)).whole(), declared);
        }
        assertTrue(DynamicSkillRegistry.readParameters(json("{\"url\": {\"type\": \"string\"}, \"schema\": {\"type\": "
                + "\"object\", \"description\": \"The schema to validate against\"}}")).whole());
        // With its required names a schema needs no type to be one.
        assertTrue(DynamicSkillRegistry.readParameters(json("{\"properties\": {\"url\": {\"type\": \"string\"}}, "
                + "\"required\": [\"url\"]}")).whole());
        // Mutations: a schema's word is a parameter wherever a definition stands under it, alone
        // or not -> the first two, the fifth and the last are one parameter each, read in full;
        // properties that are all maps show a schema -> the third and the fourth are read in full.
    }

    @Test
    @DisplayName("a skill whose one parameter is called properties, defined by its fields: a call that hands it its fields is refused by neither the check of exact calls nor the critic of direct ones, whatever a field is marked")
    void aParameterDefinedByItsFieldsIsHeldToNothing(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "contact_create", FREE_FORM,
                "parameters:",
                "  properties:",
                "    email:",
                "      type: string",
                "      required: true",
                "    firstname:",
                "      type: string");
        assertFalse(skill.declaredInFull());
        var params = new LinkedHashMap<String, Object>();
        params.put("properties", Map.of("email", "ops0@example.org", "firstname", "Ada"));
        assertEquals(List.of(), LocalExecutor.misfit(skill, params));
        var critic = new CriticAgent(new ToolRegistry(List.of(skill)));
        var verdict = critic.evaluate(new AgentAction("contact_create", params, ""), new AgentContext("u1", "t1", "add her"));
        assertTrue(verdict.allowed(), verdict.blockReason());
        // Mutation: the critic holds a direct call to what was read of a declaration in doubt ->
        // "Required parameter 'email' is missing for tool 'contact_create'".
    }

    @Test
    @DisplayName("code reads a name through params whatever run calls its argument, and an assignment that also reads is a read")
    void readThroughParamsUnderAnotherName(@TempDir Path tmp) throws Exception {
        DynamicSkill renamed = onDisk(tmp, "page_fetch", String.join("\n",
                "def run(args):",
                "    params = args or {}",
                "    return {'output': str(params.get('timeout', 30)) + args['url'], 'success': True}",
                ""),
                "parameters:", "  url:", "    type: string", "    required: true");
        assertEquals(List.of(), LocalExecutor.misfit(renamed, Map.of("url", "https://example.org", "timeout", 5)));
        assertEquals(List.of("'depth' is not one of its parameters"),
                LocalExecutor.misfit(renamed, Map.of("url", "https://example.org", "depth", 2)));
        DynamicSkill counting = onDisk(tmp, "page_count", String.join("\n",
                "def run(params):",
                "    params['count'] += 1",
                "    params['limit'] = 10",
                "    return {'output': str(params['count']), 'success': True}",
                ""),
                "parameters:", "  url:", "    type: string");
        assertEquals(List.of("'limit' is not one of its parameters"),
                LocalExecutor.misfit(counting, Map.of("url", "https://example.org", "count", 1, "limit", 5)));
        // Mutations: only run's own name for its argument counts -> 'timeout' is refused; an
        // assignment that also reads is a write -> 'count' is refused too.
    }

    @Test
    @DisplayName("whatever is read in full is written by skill_create in a shape that reads back the same, in full -- parameters called by a schema's own words too; and what is read in full is never a schema's word taken for a parameter with nothing said of it")
    void whatIsReadInFullReadsBackTheSame() {
        var random = new java.util.Random(20261118);
        String[] names = {"url", "depth", "type", "properties", "required", "items", "title", "description", "name", "query"};
        String[] types = {"string", "integer", "object", "boolean", "array"};
        int inFull = 0, inDoubt = 0;
        for (int round = 0; round < 60_000; round++) {
            Object declared = declaration(random, names, types, 0);
            var read = DynamicSkillRegistry.readParameters(declared);
            if (!read.whole()) {
                inDoubt++;
                continue;
            }
            inFull++;
            // What skill_create writes of it: each name with its definition.
            var again = DynamicSkillRegistry.readParameters(read.byName());
            assertTrue(again.whole(), declared + " was read in full as " + read.byName() + ", which is not");
            assertEquals(read.byName(), again.byName(), String.valueOf(declared));
            for (var parameter : read.byName().entrySet()) {
                assertNotNull(parameter.getValue().get("type") instanceof String || parameter.getValue().get("description") instanceof String
                        || !List.of("type", "properties", "required", "items", "title", "description").contains(parameter.getKey())
                        ? Boolean.TRUE : null, declared + ": " + parameter.getKey() + " is a parameter with nothing said of it");
            }
        }
        assertTrue(inFull > 5_000 && inDoubt > 5_000, inFull + " in full, " + inDoubt + " in doubt");
        // Mutation: the reading counts as whole wherever the reader says so, as before the second
        // review -> {type: object, properties: {type: ..}} and its like are read in full as
        // parameters that read back as a schema's, or as nothing.
    }

    /** A declaration of some shape: a map of names, a schema, a list, or something in between. */
    private static Object declaration(java.util.Random r, String[] names, String[] types, int depth) {
        var map = new LinkedHashMap<String, Object>();
        int shape = r.nextInt(10);
        if (shape == 0) {
            var list = new ArrayList<Object>();
            for (int i = r.nextInt(4); i > 0; i--) {
                var item = new LinkedHashMap<String, Object>();
                if (r.nextInt(8) > 0) item.put("name", names[r.nextInt(names.length)]);
                if (r.nextInt(4) > 0) item.put("type", types[r.nextInt(types.length)]);
                list.add(r.nextInt(10) == 0 ? names[r.nextInt(names.length)] : item);
            }
            return list;
        }
        if (shape <= 3) {
            if (r.nextInt(4) > 0) map.put("type", r.nextInt(6) == 0 ? "array" : "object");
        }
        if (shape <= 4 && depth < 2) {
            map.put("properties", r.nextInt(8) == 0 ? "object" : declaration(r, names, types, depth + 1));
        }
        for (int i = r.nextInt(4); i > 0; i--) {
            String name = names[r.nextInt(names.length)];
            Object def = switch (r.nextInt(6)) {
                case 0 -> types[r.nextInt(types.length)];
                case 1 -> null;
                case 2 -> new LinkedHashMap<>(Map.of("type", types[r.nextInt(types.length)], "description", "What it is"));
                case 3 -> new LinkedHashMap<>(Map.of("required", true));
                case 4 -> depth < 2 ? declaration(r, names, types, depth + 1) : "string";
                default -> new LinkedHashMap<>(Map.of("type", types[r.nextInt(types.length)]));
            };
            map.put(name, def);
        }
        if (r.nextInt(3) == 0) {
            var required = new ArrayList<String>();
            for (int i = r.nextInt(3); i > 0; i--) required.add(names[r.nextInt(names.length)]);
            map.put("required", r.nextInt(8) == 0 ? Boolean.TRUE : required);
        }
        if (r.nextInt(8) == 0) map.put("additionalProperties", r.nextBoolean());
        return map;
    }
}
