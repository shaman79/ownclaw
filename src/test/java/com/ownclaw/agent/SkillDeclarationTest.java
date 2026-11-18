package com.ownclaw.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.sandbox.JailTestKit;
import com.ownclaw.sandbox.ProcessSandbox;
import com.ownclaw.skills.PythonEnvironmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
 * A skill declares its parameters once, in its SKILL.yaml, which skill_create writes from what a
 * model handed it. What is read of that declaration is what the cloud is listed and what a call
 * it writes is held to ({@link CallParametersTest}), so a declaration read in part makes a skill
 * that cannot be called by its own names. One reader reads it -- as skill_create writes the file
 * and as the registry loads it -- and what that reader could not read in full is said, not passed
 * off as whole.
 * <p>
 * The review of e16a1ff (2026-11-18): skill_create, handed its 'parameters' as its own schema asks
 * -- a JSON string -- wrote shorthand as it came, and the registry read only the definitions that
 * were maps. {"url": "string", "timeout": {"type": "integer"}} was a skill declaring timeout
 * alone, and a delegated call naming url was refused, with a list of parameters that left url out.
 * And a declaration read in full still need not be all a skill takes: a name its code reads and
 * its declaration leaves out -- production has two -- could not be passed at all.
 * <p>
 * The skills here are made by the real skill_create ({@link SkillManager#createSkill}) and run by
 * the real loop, through delegate's exact calls.
 */
class SkillDeclarationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Reads url, and two names more: method, which it has a default for, and headers_only. */
    static final String CODE = String.join("\n",
            "def run(params):",
            "    mode = 'headers of ' if params.get('headers_only') else ''",
            "    return {'output': 'fetched ' + mode + params['url'] + ' by ' + params.get('method', 'GET'), 'success': True}",
            "");

    /** The real skill_create: a SkillManager over a registry of its own, its jail off. */
    static final class Workshop {
        final ToolRegistry registry = new ToolRegistry(List.of());
        final SkillManager skills;

        Workshop(Path dir) throws Exception {
            OwnClawConfig cfg = new OwnClawConfig();
            cfg.getSkills().setGeneratedPath(Files.createDirectories(dir.resolve("generated")).toString());
            cfg.getSandbox().setPythonPath("python3");
            var jail = JailTestKit.off();
            var pyEnv = new PythonEnvironmentService(cfg, jail);
            pyEnv.init();
            var sandbox = new ProcessSandbox(jail);
            skills = new SkillManager(new DynamicSkillRegistry(cfg, registry, sandbox, null, pyEnv, null, null),
                    registry, cfg, sandbox, pyEnv, null, null);
        }

        /** What skill_create answers when it is handed these 'parameters'. */
        String create(String name, Object parameters) {
            var p = new LinkedHashMap<String, Object>();
            p.put("name", name);
            p.put("description", "Fetch a page and say so.");
            p.put("code", CODE);
            p.put("parameters", parameters);
            return skills.createSkill(p);
        }

        /** A skill written by skill_create with these 'parameters', as its registry then holds it. */
        DynamicSkill make(String name, Object parameters) {
            String said = create(name, parameters);
            assertTrue(said.startsWith("Skill '" + name + "' created"), said);
            return (DynamicSkill) registry.find(name).orElseThrow();
        }
    }

    /** What the skill's SKILL.yaml holds under 'parameters'. */
    static Object written(DynamicSkill skill) throws Exception {
        return YAML.readValue(skill.skillDir().resolve("SKILL.yaml").toFile(), Map.class).get("parameters");
    }

    /** A skill as it stands on disk, with these lines under its name and description; loaded as the registry loads it. */
    static DynamicSkill onDisk(Path tmp, String name, String... yaml) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        var lines = new ArrayList<>(List.of("name: " + name, "description: Fetch a page and say so."));
        lines.addAll(List.of(yaml));
        Files.writeString(dir.resolve("SKILL.yaml"), String.join("\n", lines) + "\n");
        Files.writeString(dir.resolve("skill.py"), CODE);
        return new DynamicSkillRegistry(null, null, null, null, null, null, null).loadSkill(dir);
    }

    /** One chat turn whose cloud model delegates this one exact call, then answers: what the call's step was told. */
    static AgentObservation delegated(Path tmp, Tool skill, Map<String, Object> params) throws Exception {
        var rig = new LoopRig(tmp.resolve("rig"), List.of(skill));
        try {
            rig.cloud.think.add(call(AgentAction.DELEGATE, Map.of("calls",
                    List.of(Map.of("tool", skill.name(), "params", params)))));
            rig.cloud.think.add(respond("Done."));
            AgentResult r = rig.turn(rig.chat.createSession("u1", "Fetch"), "fetch the page");
            return r.trajectory().turns().get(0).observation();
        } finally {
            rig.queue.stop();
        }
    }

    private static DynamicSkillRegistry.Declaration read(String json) throws Exception {
        return DynamicSkillRegistry.readParameters(JSON.readValue(json, Object.class));
    }

    static final String URL_AND_DEPTH = "  - url (string, required): The page to fetch\n  - depth (integer, optional)";

    // ───────────────────────── what skill_create writes ─────────────────────────

    @Test
    @DisplayName("skill_create handed its parameters as its schema asks -- a JSON string -- with one of them in shorthand makes a skill that declares them all: a delegated call naming the one in shorthand runs; and one in shorthand throughout is not a skill with no parameters")
    void shorthandInAJsonString(@TempDir Path tmp) throws Exception {
        var workshop = new Workshop(tmp);
        DynamicSkill skill = workshop.make("page_fetch",
                "{\"url\": \"string\", \"timeout\": {\"type\": \"integer\", \"description\": \"Seconds to wait\"}}");

        assertEquals(Map.of("url", Map.of("type", "string"),
                "timeout", Map.of("type", "integer", "description", "Seconds to wait")), written(skill));
        assertEquals("  - url (string, optional)\n  - timeout (integer, optional): Seconds to wait",
                ToolFinder.parameters(skill));
        AgentObservation ran = delegated(tmp, skill, Map.of("url", "https://example.org"));
        assertTrue(ran.success() && ran.output().contains("fetched https://example.org by GET"), ran.output());

        DynamicSkill crawl = workshop.make("page_crawl", "{\"url\": \"string\", \"depth\": \"integer\"}");
        assertEquals(Map.of("url", Map.of("type", "string"), "depth", Map.of("type", "integer")), written(crawl));
        assertEquals("  - url (string, optional)\n  - depth (integer, optional)", ToolFinder.parameters(crawl));
        assertEquals(List.of("'address' is not one of its parameters"),
                LocalExecutor.misfit(crawl, Map.of("address", "https://example.org")));
        // Mutations: the reader drops a definition that is no map, as the registry's did -> the
        // first skill declares timeout alone and the call naming url is refused, the second
        // declares nothing and is listed "(no parameters)"; skill_create writes a JSON string's
        // object as it came -> SKILL.yaml holds url: "string".
    }

    @Test
    @DisplayName("skill_create handed a JSON Schema as an object writes the map of its properties, the required ones marked: the call naming a property runs, and one leaving out a required property is refused for that")
    void aSchemaHandedAsAnObject(@TempDir Path tmp) throws Exception {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", Map.of("url", Map.of("type", "string", "description", "The page to fetch")));
        schema.put("required", List.of("url"));
        DynamicSkill skill = new Workshop(tmp).make("page_fetch", schema);

        assertEquals(Map.of("url", Map.of("type", "string", "description", "The page to fetch", "required", true)),
                written(skill));
        assertEquals("  - url (string, required): The page to fetch", ToolFinder.parameters(skill));
        assertEquals(List.of("'url' is required and was left out"), LocalExecutor.misfit(skill, Map.of()));
        AgentObservation ran = delegated(tmp, skill, Map.of("url", "https://example.org"));
        assertTrue(ran.success() && ran.output().contains("fetched https://example.org by GET"), ran.output());
        // Mutations: skill_create writes an object as it was handed -> SKILL.yaml holds the
        // schema, not the map of its properties; the reader leaves a schema's required names
        // unmarked -> url is optional, and the call that leaves it out is not refused.
    }

    @Test
    @DisplayName("a JSON Schema that leaves out its \"type\": \"object\" is a schema all the same: the call naming a property runs")
    void aSchemaWithoutItsType(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = new Workshop(tmp).make("page_fetch",
                "{\"properties\": {\"url\": {\"type\": \"string\"}}, \"required\": [\"url\"]}");

        assertEquals(Map.of("url", Map.of("type", "string", "required", true)), written(skill));
        AgentObservation ran = delegated(tmp, skill, Map.of("url", "https://example.org"));
        assertTrue(ran.success() && ran.output().contains("fetched https://example.org by GET"), ran.output());
        // Mutation: a schema is one only with its type "object" beside its properties, as e16a1ff
        // read it -> the skill's one parameter is "properties".
    }

    @Test
    @DisplayName("whatever shape a model hands skill_create -- definitions, shorthand, a list of named definitions, a JSON Schema; as a string or as an object -- SKILL.yaml holds one: each parameter's name with its definition, a required one marked in it")
    void oneShapeOnDisk(@TempDir Path tmp) throws Exception {
        var workshop = new Workshop(tmp);
        String url = "{\"type\": \"string\", \"description\": \"The page to fetch\"";
        var shapes = new LinkedHashMap<String, String>();
        shapes.put("definitions", "{\"url\": " + url + ", \"required\": true}, \"depth\": {\"type\": \"integer\"}}");
        shapes.put("shorthand", "{\"url\": " + url + ", \"required\": true}, \"depth\": \"integer\"}");
        shapes.put("its_required_listed", "{\"url\": " + url + "}, \"depth\": \"integer\", \"required\": [\"url\"]}");
        shapes.put("named", "[{\"name\": \"url\", \"type\": \"string\", \"description\": \"The page to fetch\", "
                + "\"required\": true}, {\"name\": \"depth\", \"type\": \"integer\"}]");
        shapes.put("schema", "{\"type\": \"object\", \"properties\": {\"url\": " + url + "}, \"depth\": "
                + "{\"type\": \"integer\"}}, \"required\": [\"url\"], \"additionalProperties\": false}");
        shapes.put("schema_in_shorthand", "{\"type\": \"object\", \"properties\": {\"url\": " + url + "}, \"depth\": "
                + "\"integer\"}, \"required\": [\"url\"]}");
        // As a model writes JSON: unquoted names, single quotes, a comma too many.
        shapes.put("lenient", "{url: {'type': 'string', 'description': 'The page to fetch', 'required': true,}, depth: 'integer'}");

        Object one = Map.of("url", Map.of("type", "string", "description", "The page to fetch", "required", true),
                "depth", Map.of("type", "integer"));
        for (var shape : shapes.entrySet()) {
            DynamicSkill asString = workshop.make("fetch_" + shape.getKey(), shape.getValue());
            assertEquals(one, written(asString), shape.getKey() + ", as a string");
            assertEquals(URL_AND_DEPTH, ToolFinder.parameters(asString), shape.getKey());
            assertTrue(asString.declaredInFull(), shape.getKey());
            if (shape.getKey().equals("lenient")) continue;
            DynamicSkill asObject = workshop.make("fetch_" + shape.getKey() + "_object",
                    JSON.readValue(shape.getValue(), Object.class));
            assertEquals(one, written(asObject), shape.getKey() + ", as an object");
            assertEquals(URL_AND_DEPTH, ToolFinder.parameters(asObject), shape.getKey());
        }

        // Handed none, a skill declares none; handed nothing it can read as parameters, none is made.
        DynamicSkill bare = workshop.make("fetch_bare", "{}");
        assertNull(written(bare));
        assertEquals("  (no parameters)", ToolFinder.parameters(bare));
        assertEquals("ERROR: parameters field is required.", workshop.create("fetch_blank", " "));
        assertEquals("ERROR: parameters field is required.", workshop.create("fetch_blank", null));
        assertEquals("ERROR: parameters field is required.", workshop.create("fetch_blank", "[{\"type\": \"string\"}]"));
        assertEquals("ERROR: Invalid parameters JSON: a JSON object of parameter definitions is expected, "
                + "{\"arg\": {\"type\": ..., \"description\": ..., \"required\": ...}}", workshop.create("fetch_blank", "5"));
        assertTrue(workshop.registry.find("fetch_blank").isEmpty());
        // Mutations: skill_create writes what it was handed, read or not -> every shape but the
        // first is on disk as it came; a list that names no parameter is written as it came ->
        // a skill is made of it; a JSON text that is no object and no list is taken -> a skill
        // is made of "5".
    }

    // ───────────────────────── what the reader reads ─────────────────────────

    @Test
    @DisplayName("no entry of a declaration is dropped: a definition written as text is a parameter -- the text its type when it names one, its description otherwise -- and one written as nothing is a string")
    void noEntryIsDropped() throws Exception {
        var read = read("{\"a\": \"string\", \"b\": \"INT\", \"c\": \"The page to fetch\", \"d\": null, \"e\": 5, "
                + "\"f\": {\"type\": \"boolean\", \"required\": true}, \"g\": [\"x\", \"y\"], \"h\": \" list \"}");

        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h"), List.copyOf(read.byName().keySet()));
        assertEquals(Map.of("type", "string"), read.byName().get("a"));
        assertEquals(Map.of("type", "int"), read.byName().get("b"));
        assertEquals(Map.of("type", "string", "description", "The page to fetch"), read.byName().get("c"));
        assertEquals(Map.of("type", "string"), read.byName().get("d"));
        assertEquals(Map.of("type", "string", "description", "5"), read.byName().get("e"));
        assertEquals(Map.of("type", "boolean", "required", true), read.byName().get("f"));
        assertEquals(Map.of("type", "string", "description", "[x, y]"), read.byName().get("g"));
        assertEquals(Map.of("type", "list"), read.byName().get("h"));
        // Read, every one -- and with the list that stands where g's definition should, not known
        // to be read: a list is no definition. Without it the declaration is a map of names.
        assertFalse(read.whole());
        assertTrue(read("{\"a\": \"string\", \"b\": \"INT\", \"c\": \"The page to fetch\", \"d\": null, \"e\": 5, "
                + "\"f\": {\"type\": \"boolean\", \"required\": true}, \"h\": \" list \"}").whole());
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), true), DynamicSkillRegistry.readParameters(null));
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), true), read("{}"));

        // A map of names that lists its required ones the schema's way: they are marked, and the
        // list is no parameter. One that lists anything else is a parameter of that name, as is
        // one that is a definition.
        var listed = read("{\"url\": {\"type\": \"string\"}, \"depth\": \"integer\", \"required\": [\"url\"]}");
        assertEquals(Map.of("url", Map.of("type", "string", "required", true), "depth", Map.of("type", "integer")),
                listed.byName());
        assertEquals(List.of("url", "required"),
                List.copyOf(read("{\"url\": \"string\", \"required\": [\"token\"]}").byName().keySet()));
        assertEquals(Map.of("type", "boolean", "description", "Whether a reply is required"),
                read("{\"url\": \"string\", \"required\": {\"type\": \"boolean\", \"description\": \"Whether a reply "
                        + "is required\"}}").byName().get("required"));
        // Mutations: text is always a description -> a is {type: string, description: "string"};
        // text is always a type -> c's type is "the page to fetch"; a definition that is no map
        // is skipped -> a, b, c, d, e, g and h are gone; a "required" beside the names is always
        // a parameter -> url is not marked, and the skill takes a parameter called required.
    }

    @Test
    @DisplayName("a declaration written as a JSON Schema is its properties, the required ones marked -- with its type in any case, as {type: object}, or left out -- and a parameter that is called properties is not a schema")
    void aSchemaIsItsProperties(@TempDir Path tmp) throws Exception {
        String properties = "\"properties\": {\"url\": {\"type\": \"string\", \"description\": \"The page to fetch\"}, "
                + "\"depth\": {\"type\": \"integer\"}}";
        Map<String, Object> one = Map.of("url", Map.of("type", "string", "description", "The page to fetch", "required",
                true), "depth", Map.of("type", "integer"));
        for (String schema : List.of(
                "{\"type\": \"object\", " + properties + ", \"required\": [\"url\"]}",
                "{\"type\": \"Object\", " + properties + ", \"required\": [\"url\"]}",
                "{" + properties + ", \"required\": [\"url\"]}",
                // Words about the schema, and that it takes no other name: none of them a parameter.
                "{\"$schema\": \"https://example.org/schema\", \"title\": \"Fetch\", \"description\": "
                        + "\"What page_fetch takes\", \"type\": \"object\", " + properties + ", \"required\": [\"url\"], "
                        + "\"additionalProperties\": false}")) {
            assertEquals(one, read(schema).byName(), schema);
            assertTrue(read(schema).whole(), schema);
        }
        // A name it requires and does not define is a parameter all the same.
        assertEquals(Map.of("url", Map.of("type", "string", "required", true), "token", Map.of("required", true)),
                read("{\"type\": \"object\", \"properties\": {\"url\": {\"type\": \"string\"}}, \"required\": [\"url\", "
                        + "\"token\"]}").byName());
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), true), read("{\"type\": \"object\", \"properties\": {}}"));
        // Its properties alone do not show it, though every one is a definition: it is as well
        // one parameter called properties, defined by its fields. Read as the schema, in doubt.
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of("url", Map.of("type", "string")), false),
                read("{\"properties\": {\"url\": {\"type\": \"string\"}}}"));

        // Parameters that are called properties and type: their definitions say text, not maps.
        var named = read("{\"properties\": {\"type\": \"object\", \"description\": \"The properties to set\"}, "
                + "\"type\": {\"type\": \"string\", \"description\": \"The kind of thing\"}}");
        assertEquals(List.of("properties", "type"), List.copyOf(named.byName().keySet()));
        assertTrue(named.whole());
        // Properties with nothing in them and nothing beside them: a schema of no parameter, or
        // a parameter called properties with nothing said of it. Read as the first, not for certain.
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), false), read("{\"properties\": {}}"));

        // What skill_create once made of a schema handed as an object -- {type: object} for the
        // word, a definition whose description is the list for its required names -- is read as
        // far as it goes and is not known to be read: taken for the word, {type: object} made a
        // list of parameters called type and properties read back as nothing (the second review).
        // No skill on disk has it.
        var onceWritten = read("{\"type\": {\"type\": \"object\"}, " + properties + ", \"required\": {\"type\": \"string\", "
                + "\"description\": \"[url]\"}}");
        assertFalse(onceWritten.whole());
        DynamicSkill old = onDisk(tmp, "page_fetch",
                "parameters:",
                "  type:",
                "    type: \"object\"",
                "  properties:",
                "    url:",
                "      type: \"string\"",
                "      description: \"The page to fetch\"",
                "    depth:",
                "      type: \"integer\"",
                "  required:",
                "    type: \"string\"",
                "    description: \"[url]\"");
        assertEquals(ToolFinder.NOT_READ, ToolFinder.parameters(old));
        assertFalse(old.declaredInFull());
        assertEquals(List.of(), LocalExecutor.misfit(old, Map.of("url", "https://example.org")));
        // Mutations: a schema needs its type -> the one without it is a map of names, its one
        // parameter "properties"; the type's word is read as it is written -> the schema typed
        // "Object" is said not to be read in full; {type: object} is not the word -> nor is the
        // one skill_create once wrote, on disk too; a required that is no list is not read ->
        // that skill has url optional; properties that are not all definitions are a schema's
        // -> the parameters called properties and type are "type" and "description"; properties
        // with nothing in them are a schema's by themselves -> the parameter called properties
        // is gone; a required name with no definition is left out -> token is gone.
    }

    @Test
    @DisplayName("a schema whose properties are in shorthand is a schema where its type says \"object\" or its required names are its properties'; a map under \"properties\" that nothing shows to be a schema's and that reads as no one parameter's definition is read as that parameter, and not in full")
    void propertiesInShorthand() throws Exception {
        String properties = "\"properties\": {\"url\": \"string\", \"depth\": \"integer\"}";
        Map<String, Object> marked = Map.of("url", Map.of("type", "string", "required", true), "depth", Map.of("type", "integer"));
        Map<String, Object> unmarked = Map.of("url", Map.of("type", "string"), "depth", Map.of("type", "integer"));

        var both = read("{\"type\": \"object\", " + properties + ", \"required\": [\"url\"]}");
        assertEquals(marked, both.byName());
        assertTrue(both.whole());
        var byItsType = read("{\"type\": \"object\", " + properties + "}");
        assertEquals(unmarked, byItsType.byName());
        assertTrue(byItsType.whole());
        var byItsRequiredNames = read("{" + properties + ", \"required\": [\"url\"]}");
        assertEquals(marked, byItsRequiredNames.byName());
        assertTrue(byItsRequiredNames.whole());

        // Nothing shows it a schema's beyond doubt -- no type, no required name of its own, its
        // properties in shorthand: every key of it is a schema's word, so it is read as one, and
        // the declaration is not known to be read.
        var unclear = read("{" + properties + "}");
        assertEquals(List.of("url", "depth"), List.copyOf(unclear.byName().keySet()));
        assertFalse(unclear.whole());
        var requiringAnother = read("{" + properties + ", \"required\": [\"token\"]}");
        assertEquals(List.of("url", "depth", "token"), List.copyOf(requiringAnother.byName().keySet()));
        assertFalse(requiringAnother.whole());
        // A type that says "object" beside what reads as a definition, in whole or in part: a
        // schema whose parameters are called type and format, or the parameters type and
        // properties. Read as the schema, and neither is taken for certain.
        assertEquals(List.of("type", "format"), List.copyOf(read("{\"type\": \"object\", \"properties\": {\"type\": "
                + "\"string\", \"format\": \"string\"}}").byName().keySet()));
        assertEquals(List.of("type", "name"), List.copyOf(read("{\"type\": \"object\", \"properties\": {\"type\": "
                + "\"string\", \"name\": \"string\"}}").byName().keySet()));
        for (String either : List.of("{\"type\": \"object\", \"properties\": {\"type\": \"string\", \"format\": \"string\"}}",
                "{\"type\": \"object\", \"properties\": {\"type\": \"string\", \"name\": \"string\"}}")) {
            assertFalse(read(either).whole(), either);
        }
        // A list is no definition, and no map of properties.
        var aList = read("{\"properties\": [{\"name\": \"url\", \"type\": \"string\"}]}");
        assertEquals(List.of("properties"), List.copyOf(aList.byName().keySet()));
        assertFalse(aList.whole());
        // A parameter that is called properties, in shorthand, is read like any other, and so is
        // one called type whose type is an object's -- and neither is taken for certain: a word
        // of a schema's with no definition under it may be the schema's own. Defined, it is a
        // parameter (aSchemaIsItsProperties).
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of("properties", Map.of("type", "object"), "depth",
                Map.of("type", "integer")), false), read("{\"properties\": \"object\", \"depth\": \"integer\"}"));
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of("type", Map.of("type", "object"), "depth",
                Map.of("type", "integer")), false), read("{\"type\": \"object\", \"depth\": \"integer\"}"));
        // Mutations: a schema's properties must all be maps, whatever its type says -> the one
        // known by its type is the parameters type and properties; its required names show
        // nothing -> the one known by them is properties and required; a type that says object
        // settles it -> the two that are either are schemas, read in full; it settles it unless
        // every key is a definition's word -> the second of them is; a map of names is always
        // read in full -> "unclear", "requiringAnother", the two and the list are; a map under
        // properties is a definition whatever its keys -> the first two of those are; and
        // whatever type stands beside it -> the two are; a list under it is one -> the list is;
        // a type that says object is doubted with no properties beside it -> the last is not
        // read in full.
    }

    @Test
    @DisplayName("a parameter's description written as null is no description, and its type written as null is a string's: not the word \"null\"")
    void nullIsNotAWord(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "page_fetch",
                "parameters:",
                "  url:",
                "    type: string",
                "    description: null",
                "    required: true",
                "  depth:",
                "    type: null",
                "    description:");

        assertEquals("  - url (string, required)\n  - depth (string, optional)", ToolFinder.parameters(skill));
        assertEquals("", skill.inputSchema().get("url").description());
        // Mutations: a description is the text of whatever the declaration holds, as before ->
        // "  - url (string, required): null"; a type likewise -> depth has the type "null",
        // which is none a schema has: "  - depth (optional)".
    }

    // ───────────────────────── a name the code reads ─────────────────────────

    @Test
    @DisplayName("a name the skill does not declare and its code reads is let through -- the call naming it runs, and the skill gets it -- while one its code does not read is refused as it was: the skill would ignore it without a word")
    void aNameTheCodeReads(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = new Workshop(tmp).make("page_fetch", "{\"url\": {\"type\": \"string\", \"required\": true}}");
        var params = new LinkedHashMap<String, Object>();
        params.put("url", "https://example.org");
        params.put("headers_only", true);

        assertEquals(List.of(), LocalExecutor.misfit(skill, params));
        AgentObservation ran = delegated(tmp, skill, params);
        assertTrue(ran.success() && ran.output().contains("fetched headers of https://example.org"), ran.output());
        assertEquals(List.of("'body' is not one of its parameters"),
                LocalExecutor.misfit(skill, Map.of("url", "https://example.org", "body", "Hello")));

        // Asked of the code as it is now, not as it was when the skill was loaded; and code that
        // cannot be read reads nothing.
        Files.writeString(skill.skillDir().resolve("skill.py"),
                "def run(params):\n    return {'output': params['url'], 'success': True}\n");
        assertEquals(List.of("'headers_only' is not one of its parameters"), LocalExecutor.misfit(skill, params));
        Files.delete(skill.skillDir().resolve("skill.py"));
        assertEquals(List.of("'headers_only' is not one of its parameters"), LocalExecutor.misfit(skill, params));
        // Mutations: misfit does not ask the code -> headers_only is refused, and the cloud has
        // no way to pass it; a skill is taken to read every name -> 'body' is let through, and
        // ignored; the code is read once, when the skill is loaded -> headers_only is still let
        // through after the code that read it is gone.
    }

    /** Whether a skill whose code holds this line reads 'x' from the parameters it is handed. */
    private static boolean readsX(DynamicSkill skill, String line) throws Exception {
        Files.writeString(skill.skillDir().resolve("skill.py"), "def run(params):\n    " + line + "\n    return {}\n");
        return skill.readsParameter("x");
    }

    @Test
    @DisplayName("code reads a name where it takes it from params by that name -- params.get, params[...], params.pop, params.setdefault, 'name' in params, in either quote -- and not where it names another parameter, another variable or a value")
    void howCodeReadsAName(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = onDisk(tmp, "page_fetch", "parameters:", "  url: string");
        for (String reads : List.of(
                "v = params.get('x', 30)", "v = params.get(\"x\")", "v = params['x']", "v = params[\"x\"]",
                "v = params.pop('x', None)", "v = params.pop(\"x\")", "params.setdefault('x', 5)",
                "params.setdefault(\"x\", 5)", "if 'x' in params: pass", "if \"x\" not in params: pass",
                "v = params . get ( 'x' )", "v = params [ \"x\" ]", "v = self.params.get('x')",
                "v = params.get('y') or params.get('x')")) {
            assertTrue(readsX(skill, reads), reads);
        }
        for (String readsNone : List.of(
                "v = params.get('x_list')", "v = params.get('max')", "v = params['xy']", "v = options.get('x')",
                "v = my_params['x']", "if params.get('mode') == 'x': pass", "if 'x' in params_seen: pass",
                "v = params.get(x)", "v = params.getlist('x')", "v = params.get('y', 'x')", "v = 'x'")) {
            assertFalse(readsX(skill, readsNone), readsNone);
        }
        // A name is its text, whatever a pattern would make of it.
        Files.writeString(skill.skillDir().resolve("skill.py"),
                "def run(params):\n    return params.get('aXb') or params.get('c.d')\n");
        assertTrue(skill.readsParameter("aXb"));
        assertTrue(skill.readsParameter("c.d"));
        assertFalse(skill.readsParameter("a.b"), "as a pattern, a.b is aXb");
        assertFalse(skill.readsParameter("a(b"), "and a(b is none at all");
        assertFalse(skill.readsParameter("a"));
        // Mutations: a form left out of the pattern -- get, the subscript, pop, setdefault, in,
        // not in -> the lines written that way read nothing; only one quote counts -> half of
        // them; the quote need not close -> 'x_list' and 'xy' read x; any variable's get counts
        // -> options and my_params do; params need not begin its word -> my_params does; nor
        // end it -> 'x' in params_seen does; the name is a pattern -> a.b is read where the code
        // reads aXb, and asking for a(b throws.
    }

    @Test
    @DisplayName("a parameter declared required is required though the code has a default for it: the call that leaves it out is refused, and nothing runs")
    void aRequiredParameterTheCodeDefaults(@TempDir Path tmp) throws Exception {
        DynamicSkill skill = new Workshop(tmp).make("page_fetch", "{\"url\": {\"type\": \"string\", \"required\": true}, "
                + "\"method\": {\"type\": \"string\", \"required\": true}}");

        AgentObservation refused = delegated(tmp, skill, Map.of("url", "https://example.org"));

        assertFalse(refused.success(), refused.output());
        assertTrue(refused.output().contains("- call 1 (page_fetch): 'method' is required and was left out."),
                refused.output());
        assertFalse(refused.output().contains("fetched"), refused.output());
        // Mutation: a required name the code reads may be left out -> the call runs, by GET.
    }

    // ───────────────────────── a declaration that could not be read in full ─────────────────────────

    @Test
    @DisplayName("a declaration the reader could not read in full is said to be so, once per load and by the skill's name: the skill is listed with what was read and that it may take others -- never \"(no parameters)\" -- and a call of it is not refused for a name outside them")
    void aDeclarationNotReadInFull(@TempDir Path tmp) throws Exception {
        var log = new ListAppender<ILoggingEvent>();
        log.start();
        var registryLog = (Logger) LoggerFactory.getLogger(DynamicSkillRegistry.class);
        registryLog.addAppender(log);
        DynamicSkill partly, notAtAll, wholly;
        try {
            // A schema with a keyword that holds more names than its properties do.
            partly = onDisk(tmp, "page_fetch",
                    "parameters:",
                    "  type: object",
                    "  properties:",
                    "    url:",
                    "      type: string",
                    "      description: The page to fetch",
                    "  required: [url]",
                    "  patternProperties:",
                    "    \"^header_\":",
                    "      type: string");
            notAtAll = onDisk(tmp, "page_crawl", "parameters: \"url, then depth\"");
            wholly = onDisk(tmp, "page_head", "parameters:", "  url: string");
        } finally {
            registryLog.detachAppender(log);
        }

        assertFalse(partly.declaredInFull());
        assertFalse(notAtAll.declaredInFull());
        assertTrue(wholly.declaredInFull());
        var warned = log.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(2, warned.size(), warned.toString());
        assertTrue(warned.get(0).startsWith("Skill 'page_fetch': the declaration of its parameters in SKILL.yaml could "
                + "not be read in full"), warned.get(0));
        assertTrue(warned.get(1).startsWith("Skill 'page_crawl': "), warned.get(1));
        assertFalse(warned.toString().contains("header_") || warned.toString().contains("then depth"),
                "the name alone: " + warned);

        // Nothing that was read of a declaration in doubt is listed: it may have been read as
        // something it is not, and names a skill does not have are the call the cloud then writes.
        assertEquals("  (its parameters are not known: their declaration could not be read in full)",
                ToolFinder.parameters(partly));
        assertEquals(ToolFinder.NOT_READ, ToolFinder.parameters(notAtAll));
        assertEquals("  - url (string, optional)", ToolFinder.parameters(wholly));

        // A name outside what was read is not known to be wrong, and what was read is not known
        // to be right: the call is held to neither rule.
        assertEquals(List.of(), LocalExecutor.misfit(partly, Map.of("url", "https://example.org", "header_accept", "text/html")));
        assertEquals(List.of(), LocalExecutor.misfit(partly, Map.of("header_accept", "text/html")));
        assertEquals(List.of("'_credentials' is not yours to pass: a name that begins with _ is the system's own"),
                LocalExecutor.misfit(partly, Map.of("url", "https://example.org", "_credentials", Map.of())));
        assertEquals(List.of(), LocalExecutor.misfit(notAtAll, Map.of("url", "https://example.org", "depth", 2)));
        assertEquals(List.of("'depth' is not one of its parameters"),
                LocalExecutor.misfit(wholly, Map.of("url", "https://example.org", "depth", 2)));
        // Mutations: misfit holds a call to the names read, whole or not -> header_accept is
        // refused; the list ends with its last parameter -> the cloud is told url is all it
        // takes; a skill of which nothing was read is listed like one that declares nothing ->
        // "(no parameters)"; the registry loads every skill as read in full -> all of these;
        // no warning at load -> none is logged; a warning for every skill -> three are.
    }

    @Test
    @DisplayName("what is not read in full: a declaration that is no map and no list, a schema that says more than its properties and required names, a schema of something that is no object, an entry of a list that names no parameter -- each read as far as it goes")
    void whatIsNotReadInFull() throws Exception {
        String url = "\"properties\": {\"url\": {\"type\": \"string\"}}";
        for (String partly : List.of(
                "{\"type\": \"object\", " + url + ", \"additionalProperties\": true}",
                "{\"type\": \"object\", " + url + ", \"additionalProperties\": {\"type\": \"string\"}}",
                "{\"type\": \"object\", " + url + ", \"patternProperties\": {\"^header_\": {\"type\": \"string\"}}}",
                "{\"type\": \"object\", " + url + ", \"oneOf\": [{\"required\": [\"url\"]}]}",
                "{\"type\": \"object\", " + url + ", \"required\": true}",
                "{\"type\": \"object\", " + url + ", \"required\": [\"url\", 5]}",
                "{\"type\": \"object\", " + url + ", \"title\": {\"en\": \"Fetch\"}}",
                "{\"type\": \"array\", " + url + "}",
                "{\"type\": [\"object\", \"null\"], " + url + "}",
                "[{\"name\": \"url\", \"type\": \"string\"}, {\"type\": \"integer\"}]",
                "[\"url\", 5]",
                "[\"url\", \"the depth to go to\"]")) {
            var read = read(partly);
            assertFalse(read.whole(), partly);
            assertEquals(List.of("url"), List.copyOf(read.byName().keySet()), partly);
        }
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), false), read("\"url, then depth\""));
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of(), false), read("5"));
        // A list of names alone is read, each a string -- and is not known to be all: an item of
        // a list that is one of the shapes nothing else is taken for says what it is.
        assertEquals(new DynamicSkillRegistry.Declaration(Map.of("url", Map.of("type", "string"), "depth",
                Map.of("type", "string")), false), read("[\"url\", \"depth\"]"));
        // Mutations: a keyword the reader does not know is passed over -> the schemas with
        // patternProperties and oneOf are read in full; additionalProperties is passed over
        // whatever it says -> so are the two that take other names; a required that is no list
        // of names is passed over -> so are those two; any type is an object's -> the array's
        // and the list's are whole; an item of a list that names nothing is skipped -> the three
        // lists are whole; text with blanks in it is a name -> the last list is whole, with a
        // parameter called "the depth to go to"; a title is passed over whatever it holds -> so
        // is the one that is a map; what is no map and no list declares nothing -> the text and
        // the number are whole.
    }

    @Test
    @DisplayName("skill_create keeps a declaration it could not read in full as it was handed -- nothing of it is lost -- and the skill it makes is one whose declaration was not read in full")
    void notReadInFullAtCreation(@TempDir Path tmp) throws Exception {
        var workshop = new Workshop(tmp);
        String schema = "{\"type\": \"object\", \"properties\": {\"url\": {\"type\": \"string\"}}, \"required\": [\"url\"], "
                + "\"patternProperties\": {\"^header_\": {\"type\": \"string\"}}}";
        DynamicSkill skill = workshop.make("page_fetch", schema);

        assertEquals(JSON.readValue(schema, Object.class), written(skill));
        assertFalse(skill.declaredInFull());
        assertEquals(ToolFinder.NOT_READ, ToolFinder.parameters(skill));
        assertEquals(List.of(), LocalExecutor.misfit(skill, Map.of("url", "https://example.org", "header_accept", "text/html")));

        String list = "[{\"name\": \"url\", \"type\": \"string\"}, {\"type\": \"integer\"}]";
        DynamicSkill fromAList = workshop.make("page_crawl", JSON.readValue(list, Object.class));
        assertEquals(JSON.readValue(list, Object.class), written(fromAList));
        assertFalse(fromAList.declaredInFull());
        assertEquals(ToolFinder.NOT_READ, ToolFinder.parameters(fromAList));
        // Mutation: skill_create writes what was read of it, whole or not -> SKILL.yaml holds url
        // alone, the registry reads that in full, and a call naming header_accept is refused.
    }
}
