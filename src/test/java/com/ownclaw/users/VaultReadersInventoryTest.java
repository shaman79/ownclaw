package com.ownclaw.users;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every read of a vault value, every grant made and every launch of a skill's code, found in the
 * source.
 * <p>
 * A skill reads a credential only when its account granted it. That holds while a skill's
 * credentials are read in one place, which asks the grants ({@link CredentialVault#forSkill});
 * while nothing else that decrypts a value hands it to a skill; while a grant is written by the
 * one command its account sends; and while a skill's code is started only where its input is what
 * that one place handed over. Nothing at run time can tell a second reader from the first, so
 * this reads src/main/java: each of these calls may stand only in the files below, each only with
 * the arguments below, and a new one anywhere fails the build until it has been read and listed
 * here -- with what it does with the value.
 */
class VaultReadersInventoryTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * A file's source without its comments, and with what its string literals hold blanked when
     * {@code blankStrings}: a mention in a Javadoc or a log line is no call.
     */
    private static String code(Path file, boolean blankStrings) throws IOException {
        String s = Files.readString(file);
        var out = new StringBuilder(s.length());
        boolean inString = false, inChar = false, inLine = false, inBlock = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (inLine) { if (c == '\n') { inLine = false; out.append(c); } continue; }
            if (inBlock) { if (c == '*' && next == '/') { inBlock = false; i++; } continue; }
            if (inString) {
                if (c == '\\') {
                    if (!blankStrings) out.append(c).append(next);
                    i++;
                } else if (c == '"') {
                    inString = false;
                    out.append(c);
                } else if (!blankStrings || c == '\n') {
                    out.append(c);
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && next == '/') { inLine = true; i++; continue; }
            if (c == '/' && next == '*') { inBlock = true; i++; continue; }
            if (s.startsWith("\"\"\"", i)) {
                // A text block, to its closing quotes: a quote or a slash inside it is its text.
                int end = s.indexOf("\"\"\"", i + 3);
                if (end < 0) end = s.length() - 3;
                out.append('"');
                if (!blankStrings) out.append(s, i + 3, end);
                out.append('"');
                i = end + 2;
                continue;
            }
            if (c == '"') { inString = true; out.append(c); continue; }
            if (c == '\'') { inChar = true; continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static List<Path> javaFiles() throws IOException {
        assertTrue(Files.isDirectory(MAIN), "run from the project directory: " + MAIN.toAbsolutePath());
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** The text between the parenthesis at {@code from} and its match, whitespace collapsed. */
    private static String arguments(String src, int from) {
        int depth = 1, i = from;
        boolean inString = false;
        for (; i < src.length() && depth > 0; i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return src.substring(from, i - 1).replaceAll("\\s+", " ").trim();
    }

    /** The arguments of a call, split where a comma separates two of them. */
    private static List<String> split(String arguments) {
        var out = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char c = arguments.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(arguments.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(arguments.substring(start).trim());
        return out;
    }

    /**
     * Every place {@code name(} stands in the code of src/main/java -- a call or the method's own
     * declaration -- by file, each with what stands between its parentheses.
     *
     * @param name a regular expression for what stands before the parenthesis
     */
    private static Map<String, List<String>> every(String name) throws IOException {
        return everyMatch(Pattern.compile("(?<![A-Za-z0-9_$])" + name + "\\s*\\("));
    }

    /** By file, what stands between the parentheses after each match of {@code call}, which ends at the opening one. */
    private static Map<String, List<String>> everyMatch(Pattern call) throws IOException {
        var out = new TreeMap<String, List<String>>();
        for (Path file : javaFiles()) {
            String src = code(file, true);
            var found = new ArrayList<String>();
            Matcher m = call.matcher(src);
            while (m.find()) found.add(arguments(src, m.end()));
            if (!found.isEmpty()) out.put(MAIN.relativize(file).toString().replace('\\', '/'), found);
        }
        return out;
    }

    private static final String VAULT = "com/ownclaw/users/CredentialVault.java";
    private static final String GRANTS = "com/ownclaw/users/CredentialGrantService.java";
    private static final String SKILL = "com/ownclaw/agent/tools/DynamicSkill.java";
    private static final String SKILL_MANAGER = "com/ownclaw/agent/SkillManager.java";
    private static final String LOOP = "com/ownclaw/agent/AgentLoop.java";
    private static final String REGISTRY = "com/ownclaw/agent/tools/DynamicSkillRegistry.java";
    private static final String COMMANDS = "com/ownclaw/interfaces/CommandHandler.java";

    private static final String READ_IT = " Read the call: a value of the vault goes to a skill through "
            + "CredentialVault#forSkill alone, which asks the grants. List it here only when what it reads "
            + "cannot reach a skill, and say what it is for.";

    @Test
    @DisplayName("a skill's credentials are read in one place: forSkill is called by DynamicSkill's run and by nothing else")
    void forSkillIsCalledByTheSkillAlone() throws IOException {
        assertEquals(Map.of(
                VAULT, List.of("String userId, String skill, List<String> keys"),          // its declaration
                SKILL, List.of("context.userId(), name, requiredCredentials")),             // the run of a skill
                every("forSkill"), "forSkill(" + READ_IT);
        // Mutation: a second call of forSkill, in CommandHandler -> listed, and not allowed.
    }

    @Test
    @DisplayName("the vault's values are decrypted only where this lists: for forSkill, for the scrubbing set, and for the link secret's check")
    void theValueReaders() throws IOException {
        assertEquals(Map.of(
                VAULT, List.of(
                        "userId, keys",                          // forSkill, after the grants were asked
                        "userId, List.of(key)",                  // getCredential, below
                        "String userId, List<String> keys",      // its declaration
                        "userId, secret"),                       // publishSecrets: the secret ones, for VaultSecrets
                // The secret-named values, to keep them out of what is stored or leaves: for the fact
                // capture, which keeps no fact that is one (secretValuesOf), and at a task's start, to
                // scrub them from every prompt, row and screen (AgentContext#setSecretValues). Never
                // an argument of a call.
                LOOP, List.of("userId, secretKeys", "userId, secretKeys")),
                every("getCredentials"), "getCredentials(" + READ_IT);
        assertEquals(Map.of(
                VAULT, List.of("String userId, String key"),                                   // its declaration
                // The link secret, to check a link's signature (LinkController#verify): compared, never returned.
                "com/ownclaw/interfaces/web/LinkController.java", List.of("userId, SECRET_KEY")),
                every("getCredential"), "getCredential(" + READ_IT);
        // The scrubbing set holds decrypted values too: read by the task's context, for scrubbing
        // and the cloud gateway, and by the status emitter to see whether there is anything to scrub.
        assertEquals(Map.of(
                "com/ownclaw/agent/AgentContext.java", List.of("userId"),
                "com/ownclaw/observability/ChatStatusEmitter.java", List.of("userId")),
                every("VaultSecrets\\s*\\.\\s*of"), "VaultSecrets.of(" + READ_IT);
        // Mutation: CommandHandler reads getCredentials -> listed, and not allowed.
    }

    @Test
    @DisplayName("where a skill's input is made, no decrypted value is in reach but forSkill's: not the vault's readers, not the scrubbing set, not the task's secret values")
    void aSkillsRunReadsTheVaultThroughForSkillAlone() throws IOException {
        String skill = code(MAIN.resolve(SKILL), true);
        for (String reader : List.of("getCredentials", "getCredential(", "VaultSecrets", "secretValues", "AgentContext")) {
            assertFalse(skill.contains(reader), SKILL + " names " + reader + "." + READ_IT);
        }
        assertEquals(1, Pattern.compile("credentialVault\\s*\\.\\s*forSkill\\s*\\(").matcher(skill).results().count());
        // Mutation: run() reads VaultSecrets.of(context.userId()) -> named.
    }

    @Test
    @DisplayName("a grant is written by /grant alone, the one-time grant by the registry's start alone, and a command is read only from what a chat's user sent")
    void aGrantIsMadeByTheCommandAlone() throws IOException {
        assertEquals(Map.of(
                GRANTS, List.of("String userId, String skill, Collection<String> keys"),   // its declaration
                COMMANDS, List.of("userId, skill, keys")),                                  // /grant
                every("grant"), "A grant is made by its account, in a command it sends: nothing a model can call makes one.");
        // The one-time grant has two forms since the reviews of the release: for every holder, and
        // -- what the registry asks -- with whose skill each is, so that another account's skill is
        // granted to its author alone. The first only hands on to the second, restricting nobody.
        assertEquals(Map.of(
                GRANTS, List.of(
                        "Map<String, ? extends Collection<String>> declared",                                // for every holder: its declaration
                        "declared, Map.of()",                                                                 // ... asking the one below
                        "Map<String, ? extends Collection<String>> declared, Map<String, String> onlyFor"),  // by author: its declaration
                REGISTRY, List.of("declared, onlyFor")),                                                      // keepGranted(), at a start
                every("grantExisting"), "What worked before the check is granted once, at the first start.");

        // The rows themselves: written nowhere but in the service.
        Pattern writes = Pattern.compile("(?i)(insert\\s+(or\\s+\\w+\\s+)?into|update|replace\\s+into|delete\\s+from)\\s+credential_grants");
        for (Path file : javaFiles()) {
            if (file.endsWith(GRANTS)) continue;
            assertFalse(writes.matcher(code(file, false)).find(), file + " writes credential_grants: only CredentialGrantService does");
        }
        assertTrue(writes.matcher(code(MAIN.resolve(GRANTS), false)).find(), "the reader sees the writes it guards");

        // A command is run by CommandHandler#handle, and only the two chats hold a handler: what
        // their user typed, or picked. No task, tool or model's text is handed to one.
        var holders = new ArrayList<String>();
        var callers = new TreeMap<String, Long>();
        for (Path file : javaFiles()) {
            String rel = MAIN.relativize(file).toString().replace('\\', '/');
            String src = code(file, true);
            if (!rel.equals(COMMANDS) && Pattern.compile("CommandHandler\\s+[a-z]\\w*").matcher(src).find()) holders.add(rel);
            long calls = Pattern.compile("[cC]ommandHandler\\s*\\.\\s*handle\\s*\\(").matcher(src).results().count();
            if (calls > 0) callers.put(rel, calls);
        }
        List<String> chats = List.of("com/ownclaw/interfaces/telegram/TelegramBotService.java",
                "com/ownclaw/interfaces/web/ChatWebSocketHandler.java");
        assertEquals(chats, holders, "who holds a CommandHandler");
        assertEquals(Map.of(chats.get(0), 1L, chats.get(1), 1L), callers, "who hands it a message");
        // Mutations: the loop grants what a skill waits for (credentialVault.grants().grant(...)) -> listed,
        // and not allowed; the registry asking the one-time grant for every holder -> its call is another.
    }

    @Test
    @DisplayName("a grant is taken back by its account's /revoke, or ends with its skill: for every account at once only where the skill manager deletes a skill, makes one under a free name, or writes another account's again")
    void aGrantIsEndedWithItsSkillByTheSkillManagerAlone() throws IOException {
        assertEquals(Map.of(
                GRANTS, List.of("String userId, String skill, Collection<String> keys"),   // its declaration
                COMMANDS, List.of("userId, skill, keys")),                                  // /revoke
                every("revoke"), "An account takes back its own grants, in a command it sends.");

        // Everyone's grants of a skill at once (CredentialGrantService#end): through the registry's
        // endGrants, which the skill manager calls where a skill's code ends or changes hands.
        assertEquals(Map.of(
                REGISTRY, List.of("String skill, String keep"),                             // its declaration
                // A skill made under a name no skill held: whatever was left under it. Written again
                // by another account than the owner: everyone's but that account's. Deleted: all.
                SKILL_MANAGER, List.of("name, null", "name, requester", "name, null")),
                every("endGrants"), "Who ends the grants of a skill for every account: read the call. Not a retirement, "
                        + "nor a restore, nor anything a model can call but through skill_create and skill_manage.");
        // And no other way to the rows: every call of something's end( that is handed two things, as
        // the service's is (a matcher's end(), a model call's end(progress) are none).
        Map<String, List<String>> ends = everyMatch(Pattern.compile("\\.\\s*end\\s*\\("));
        ends.values().forEach(calls -> calls.removeIf(arguments -> arguments.isEmpty() || split(arguments).size() != 2));
        ends.values().removeIf(List::isEmpty);
        assertEquals(Map.of(REGISTRY, List.of("skill, keep")), ends,
                "CredentialGrantService#end is called by DynamicSkillRegistry#endGrants alone.");
        // Mutations: a retirement ends the skill's grants -> a second endGrants in the registry;
        // deleting a credential ends grants by itself (grants.end(...) in the vault) -> listed, and not allowed.
    }

    @Test
    @DisplayName("a skill's code is started only by its run, with the input forSkill's values went into, and by skill_create's load check, with none")
    void everyLaunchOfSkillCode() throws IOException {
        var input = new TreeMap<String, List<String>>();
        every("sandbox\\s*\\.\\s*execute").forEach((file, calls) ->
                input.computeIfAbsent(file, f -> new ArrayList<>()).addAll(calls.stream().map(a -> split(a).get(3)).toList()));
        every("containerSandbox\\s*\\.\\s*execute").forEach((file, calls) ->
                input.computeIfAbsent(file, f -> new ArrayList<>()).addAll(calls.stream().map(a -> "container: " + split(a).get(4)).toList()));

        assertEquals(Map.of(
                // The run and its self-healed retry, on the host and in a container: the one input.
                SKILL, List.of("inputJson", "inputJson", "container: inputJson", "container: inputJson"),
                // The load check (it imports skill.py) and the syntax check: no input. The values put
                // back into code: the code and the placeholders' values, on the service's own script.
                SKILL_MANAGER, List.of("null", "null", "CodeValues.input(code, values)")),
                input, "A launch of Python: what is on its standard input? A skill's credentials travel there.");
        // The one input of a run: its parameters, and under their own key what forSkill handed over.
        String skill = code(MAIN.resolve(SKILL), false);
        assertEquals(1, Pattern.compile("String\\s+inputJson\\s*=").matcher(skill).results().count());
        assertTrue(skill.contains("effectiveParams.put(CREDENTIALS, creds);"), "the credentials' place in the input");
        // Mutation: the load check started with an input -> its null is something else.
    }
}
