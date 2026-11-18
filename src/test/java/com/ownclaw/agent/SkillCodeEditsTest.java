package com.ownclaw.agent;

import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.observability.ChatStatusEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ownclaw.agent.LoopRig.Call;
import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static com.ownclaw.agent.SkillCodeInPartsTest.*;
import static com.ownclaw.agent.SkillCodegenTest.*;
import static com.ownclaw.agent.SkillEditsTest.block;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An existing skill changed by edit blocks instead of written whole again ({@link SkillEdits}),
 * through the real loop and the real gateway -- its placeholder table in the database -- with a
 * scripted model where the provider stands. Three repairs of November 2026 each changed a few lines
 * and each wrote the whole file again: 35,528, 22,632 and 21,268 output tokens, $2.15 together.
 * <p>
 * What is pinned here: the lines a block names are looked for in the code exactly as the model was
 * shown it, and changed in the copy the skill keeps; what the blocks give is checked, restored and
 * kept as a reply's complete code is; blocks that cannot be applied change nothing and are asked
 * for again; edits that go round ask for the complete code the old way, once; and a new skill, and
 * every skill on the local model, is asked for as before.
 */
class SkillCodeEditsTest {

    /** skill.py: a value inside a longer word, a short one, and lines that hold none. */
    static final String ON_DISK = String.join("\n",
            "import json",
            "",
            "ARCHIVE = '/tmp/ExampleHome5G_backup.tar'",
            "GUEST = 'AP7'",
            "KEEP = 3",
            "",
            "def run(params):",
            "    names = params.get('names', [])",
            "    return {'output': json.dumps([ARCHIVE, GUEST, names[:KEEP]]), 'success': True}");

    /** The copy kept beside it: the code as the model wrote it, its placeholders where the values are. */
    static final String COPY = ON_DISK.replace("ExampleHome5G", "<ssid_1>").replace("AP7", "<ssid_2>");

    /** What the request said in the system prompt, and under the code, before edit blocks were asked for. */
    static final String OUTPUT_BEFORE = "Output: ```python fence + ```requirements fence.\n\n";
    static final String ASK_BEFORE = "Return complete fixed code.\n";

    /** The specification of a fix of the skill: it names both networks, so the table knows them. */
    static Map<String, Object> fix() {
        var spec = backupSpec();
        spec.put("name", "router_backup");
        return spec;
    }

    /** A rig whose skill exists, kept with its code as the model wrote it. */
    static LoopRig existing(Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.skills.source = name -> ON_DISK;
        rig.skills.asWritten = name -> COPY;
        return rig;
    }

    static String user(Call c) {
        return c.messages().get(1).content();
    }

    /** The code as the provider received it: what stands in the request's fence. */
    static String shown(Call c) {
        String text = user(c);
        int from = text.indexOf("Broken code:\n```python\n") + "Broken code:\n```python\n".length();
        int to = text.indexOf("\n```\n" + SkillEdits.ASK);
        assertTrue(from > 22 && to > from, "the request holds the code and asks for edit blocks: " + text);
        return text.substring(from, to);
    }

    /** The request as it was before edit blocks were asked for: the same, but for the two texts that ask. */
    static List<LlmMessage> theOldWay(Call asked) {
        var out = new ArrayList<LlmMessage>();
        for (LlmMessage m : asked.messages().subList(0, 2)) {
            out.add(new LlmMessage(m.role(), m.content().replace(SkillEdits.OUTPUT, OUTPUT_BEFORE)
                    .replace(SkillEdits.ASK, ASK_BEFORE), m.authored(), m.marked()));
        }
        return out;
    }

    static List<String> texts(List<ChatStatusEmitter.StatusMessage> statuses) {
        return statuses.stream().map(ChatStatusEmitter.StatusMessage::text).toList();
    }

    @Test
    @DisplayName("one block: the line it names is replaced, and no other; skill.py is the old one with that line changed and its values where they were, the copy the same with its placeholders; the step says how many edits")
    void oneBlock(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        var statuses = rig.statuses();
        rig.cloud.codegen.add(ends("The limit only.\n\n" + block("KEEP = 3", "KEEP = 5") + "\n```requirements\n```\n"));
        var ctx = task();

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), ctx);

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(1, calls.size());
        assertEquals(COPY, shown(calls.get(0)), "the premise: the model was shown the copy, placeholders and all");
        assertTrue(calls.get(0).messages().get(0).content().contains(SkillEdits.OUTPUT));
        assertFalse(user(calls.get(0)).contains(ASK_BEFORE), "the whole file is not asked for");
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.params().get("code"),
                "skill.py: the line changed, every other as it was, the values in them");
        assertEquals(COPY.replace("KEEP = 3", "KEEP = 5"), out.asWritten(), "the copy: the same change, no value in it");
        assertNull(out.params().get("requirements"), "an empty ```requirements fence names none");
        assertTrue(texts(statuses).contains("Skill code changed by 1 edit (8 of its " + COPY.length()
                + " characters replaced by 8) · cloud"), texts(statuses).toString());
        assertEquals(7_000, ctx.cloudTokens(), "one call's tokens, counted as every code call's are");
        // Mutation: carry the change out on skill.py, not on the copy -> the copy kept holds 'AP7'.
    }

    @Test
    @DisplayName("several blocks: a value the blocks do not touch stays in skill.py and a placeholder in the copy; one they do touch -- its line kept, a line added that names another -- is put back where the model wrote it; the requirements are the reply's")
    void severalBlocks(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.cloud.codegen.add(ends(block("GUEST = '<ssid_2>'", "GUEST = '<ssid_2>'\nNETWORKS = ['<ssid_1>', GUEST]")
                + block("KEEP = 3", "KEEP = 5")
                + block("    names = params.get('names', [])", "    names = sorted(params.get('names', []))")
                + "\n```requirements\nrequests\n```\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(String.join("\n", "import json", "", "ARCHIVE = '/tmp/ExampleHome5G_backup.tar'", "GUEST = 'AP7'",
                "NETWORKS = ['ExampleHome5G', GUEST]", "KEEP = 5", "", "def run(params):",
                "    names = sorted(params.get('names', []))",
                "    return {'output': json.dumps([ARCHIVE, GUEST, names[:KEEP]]), 'success': True}"),
                out.params().get("code"));
        assertEquals(String.join("\n", "import json", "", "ARCHIVE = '/tmp/<ssid_1>_backup.tar'", "GUEST = '<ssid_2>'",
                "NETWORKS = ['<ssid_1>', GUEST]", "KEEP = 5", "", "def run(params):",
                "    names = sorted(params.get('names', []))",
                "    return {'output': json.dumps([ARCHIVE, GUEST, names[:KEEP]]), 'success': True}"), out.asWritten());
        assertEquals("requests", out.params().get("requirements"));
        assertEquals(1, rig.cloud.calls("codegen").size());
        // Mutation: put the values back in the reply before its blocks are applied -> "GUEST = 'AP7'" is no line
        // of the code the model was shown, and block 1 is not found.
    }

    @Test
    @DisplayName("the lines a block names are looked for in what the gateway sent, which is not the copy -- a placeholder the table does not know and an address the model wrote itself went out as other text -- and changed in the copy: the lines the model wrote are its own, every other the copy's")
    void lookedForInWhatTheModelWasShown(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        // As a first reply wrote it: a placeholder of nobody's, and an address of the model's own.
        String copy = COPY.replace("KEEP = 3", "KEEP = 3\nOTHER = '<ssid_9>'\nREPORT_TO = 'reports@example.org'");
        String onDisk = ON_DISK.replace("KEEP = 3", "KEEP = 3\nOTHER = '<ssid_9>'\nREPORT_TO = 'reports@example.org'");
        rig.skills.source = name -> onDisk;
        rig.skills.asWritten = name -> copy;
        String[] ph = new String[2];   // what the two went out as
        rig.cloud.codegen.add(c -> {
            String shown = shown(rig.cloud.calls("codegen").get(0));
            assertNotEquals(copy, shown, "the premise: what went out is not the copy");
            var other = java.util.regex.Pattern.compile("OTHER = '(<text_\\d+>)'\nREPORT_TO = '(<email_\\d+>)'").matcher(shown);
            assertTrue(other.find(), shown);
            ph[0] = other.group(1);
            ph[1] = other.group(2);
            // The model copies the lines it reads: one repeated to add a line under it, one changed.
            return ends(block("REPORT_TO = '" + ph[1] + "'", "REPORT_TO = '" + ph[1] + "'\nCC = '" + ph[1] + "'")
                    + block("OTHER = '" + ph[0] + "'", "OTHER = '" + ph[0] + "'  # nobody's")).answer(c);
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(1, rig.cloud.calls("codegen").size(), "found at the first reply");
        assertEquals(onDisk.replace("REPORT_TO = 'reports@example.org'", "REPORT_TO = 'reports@example.org'\nCC = 'reports@example.org'")
                .replace("OTHER = '<ssid_9>'", "OTHER = '<ssid_9>'  # nobody's"), out.params().get("code"),
                "skill.py: the text and the address where the model named them, put back");
        assertEquals(copy.replace("REPORT_TO = 'reports@example.org'", "REPORT_TO = 'reports@example.org'\nCC = '" + ph[1] + "'")
                .replace("OTHER = '<ssid_9>'", "OTHER = '" + ph[0] + "'  # nobody's"), out.asWritten(),
                "the copy: the line repeated is its own still; the line added and the line changed are the model's");

        // Lines copied from the copy on disk -- what the model never read -- are not in the code it was shown.
        var other = existing(tmp.resolve("other"));
        other.skills.source = name -> onDisk;
        other.skills.asWritten = name -> copy;
        other.cloud.codegen.add(ends(block("OTHER = '<ssid_9>'", "OTHER = 'x'")));
        other.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5")));
        assertNull(other.loop.generateSkillCode(fix(), task()).error());
        assertTrue(other.cloud.calls("codegen").get(1).messages().getLast().content().startsWith(
                "Block 1 was not applied: its SEARCH text is not in the code."), "refused, and asked again");
        // Mutation: look the lines up in the copy as it was read from disk -> the model's own lines are not found.
    }

    @Test
    @DisplayName("a skill kept without a copy: skill.py goes out with every known value replaced, the blocks are applied to that, and skill.py comes back with its values; the copy its next change starts from is made")
    void withoutACopy(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.asWritten = name -> null;
        rig.cloud.codegen.add(c -> {
            String shown = shown(rig.cloud.calls("codegen").get(0));
            assertEquals(COPY, shown, "the premise: skill.py, its values replaced by the gateway");
            return ends(block("GUEST = '<ssid_2>'", "GUEST = '<ssid_2>'  # the guests' network")
                    + block("KEEP = 3", "KEEP = 5")).answer(c);
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(ON_DISK.replace("GUEST = 'AP7'", "GUEST = 'AP7'  # the guests' network").replace("KEEP = 3", "KEEP = 5"),
                out.params().get("code"));
        assertEquals(COPY.replace("GUEST = '<ssid_2>'", "GUEST = '<ssid_2>'  # the guests' network").replace("KEEP = 3", "KEEP = 5"),
                out.asWritten(), "kept as written, no value in it");
        String sent = SkillCodeAsWrittenTest.everything(rig.cloud.calls("codegen").get(0));
        assertFalse(sent.contains("ExampleHome5G_backup") || sent.contains("'AP7'"), "sent in clear: " + sent);
        // Mutation: apply the blocks to skill.py as it is on disk -> "GUEST = '<ssid_2>'" is no line of it.
    }

    @Test
    @DisplayName("through the real skill manager: a new skill is written whole; then one block, then several, each change is on disk in skill.py with the values and in the copy beside it without them; blocks that are refused leave both files as they were")
    void onDisk(@TempDir Path tmp) throws Exception {
        SkillManager real = SyntaxCheckTest.manager(tmp.resolve("real"));
        var rig = new LoopRig(tmp.resolve("rig"), List.of());
        rig.skills.source = real::readSkillCode;
        rig.skills.asWritten = name -> real.readAsWritten(name, "u1");
        rig.skills.syntax = real::checkPythonSyntax;
        rig.skills.createFor = (params, requester) -> real.createSkill(params, rig.skills.keptAsWritten.getLast(),
                requester, () -> false, () -> { });
        Path skillPy = tmp.resolve("real").resolve("generated").resolve("router_backup").resolve("skill.py");
        String session = rig.chat.createSession("u1", "Backup");
        var statuses = rig.statuses();
        Map<String, Object> create = Map.of("name", "router_backup", "description", "Backs up the router.", "parameters", "{}");

        // A new skill: the complete code, as ever.
        String[] ph = new String[2];
        rig.cloud.think.add(c -> {
            var shown = CodegenPrivacyReviewTest.shownInThink(rig);
            assertEquals(2, shown.size(), "the premise: both networks shown as placeholders");
            ph[0] = shown.get(0);
            ph[1] = shown.get(1);
            return call(AgentAction.SKILL_CREATE, create).answer(c);
        });
        rig.cloud.codegen.add(c -> ends("```python\n" + ON_DISK.replace("ExampleHome5G", ph[0]).replace("AP7", ph[1])
                + "\n```\n").answer(c));
        rig.cloud.think.add(respond("Made."));
        rig.turn(session, "back up my router; its network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\"");
        assertEquals(ON_DISK, Files.readString(skillPy));
        String copy = ON_DISK.replace("ExampleHome5G", ph[0]).replace("AP7", ph[1]);
        assertEquals(copy, real.readAsWritten("router_backup", "u1"));
        assertFalse(user(rig.cloud.calls("codegen").get(0)).contains("edit blocks"), "a new skill is asked for whole");

        // One block.
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, create));
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5")));
        rig.cloud.think.add(respond("Five."));
        rig.turn(session, "keep five names, not three");
        String five = ON_DISK.replace("KEEP = 3", "KEEP = 5");
        assertEquals(five, Files.readString(skillPy));
        assertEquals(copy.replace("KEEP = 3", "KEEP = 5"), real.readAsWritten("router_backup", "u1"));

        // Several, from the copy the one block left: one of them on a line that holds a value.
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, create));
        rig.cloud.codegen.add(c -> ends(block("GUEST = '" + ph[1] + "'", "GUEST = '" + ph[1] + "'\nNETWORKS = ['" + ph[0] + "', GUEST]")
                + block("    names = params.get('names', [])", "    names = sorted(params.get('names', []))")).answer(c));
        rig.cloud.think.add(respond("Sorted."));
        rig.turn(session, "sort the names, and list both networks");
        String sorted = five.replace("GUEST = 'AP7'", "GUEST = 'AP7'\nNETWORKS = ['ExampleHome5G', GUEST]")
                .replace("    names = params.get('names', [])", "    names = sorted(params.get('names', []))");
        assertEquals(sorted, Files.readString(skillPy));
        String sortedCopy = sorted.replace("ExampleHome5G", ph[0]).replace("AP7", ph[1]);
        assertEquals(sortedCopy, real.readAsWritten("router_backup", "u1"));

        // Blocks one of which is not found: neither file is touched, and the corrected reply is applied whole.
        rig.cloud.think.add(call(AgentAction.SKILL_CREATE, create));
        rig.cloud.codegen.add(ends(block("KEEP = 5", "KEEP = 7") + block("KEEP = 6", "KEEP = 8")));
        rig.cloud.codegen.add(c -> {
            assertEquals(sorted, Files.readString(skillPy), "skill.py after the refused reply");
            assertEquals(sortedCopy, real.readAsWritten("router_backup", "u1"), "and the copy");
            return ends(block("KEEP = 5", "KEEP = 9")).answer(c);
        });
        rig.cloud.think.add(respond("Nine."));
        rig.turn(session, "keep nine");
        assertEquals(sorted.replace("KEEP = 5", "KEEP = 9"), Files.readString(skillPy),
                "block 1 of the refused reply was not applied: KEEP = 5 was still there to replace");
        assertEquals(sortedCopy.replace("KEEP = 5", "KEEP = 9"), real.readAsWritten("router_backup", "u1"));

        List<String> said = texts(statuses).stream().filter(t -> t.startsWith("Skill code ")).toList();
        assertEquals(3, said.size(), "each change of the existing skill said once: " + said);
        assertTrue(said.get(0).startsWith("Skill code changed by 1 edit (") && said.get(1).startsWith("Skill code changed by 2 edits (")
                && said.get(2).startsWith("Skill code changed by 1 edit ("), said.toString());
        // Mutation: keep no copy of code that edit blocks gave -> the next change finds none, and is the whole file again.
    }

    /** One reply whose blocks are refused: what is wrong in it, and what the model is told. */
    record Refused(String what, String reply, String told) {}

    @Test
    @DisplayName("blocks that cannot be applied -- not found, nothing to look for, nothing changed, malformed -- change nothing: the reply goes back as the model's turn with which block and why, and the corrected reply is applied to the code as it was shown")
    void everyRefusal(@TempDir Path tmp) throws Exception {
        // Each begins with a block that could be applied: it is not, when a later one cannot be.
        String first = block("KEEP = 3", "KEEP = 5");
        var cases = List.of(
                new Refused("not found", first + block("KEEP = 4", "KEEP = 6"),
                        "Block 2 was not applied: its SEARCH text is not in the code as the blocks before it left it. Line 1 "
                                + "of its SEARCH text is no line of the code: copy the lines from the code above, character "
                                + "for character."),
                new Refused("nothing to look for", first + block("", "KEEP = 6"),
                        "Block 2 was not applied: its SEARCH text is empty, so nothing says which lines it replaces."),
                new Refused("nothing changed", first + block("import json", "import json"),
                        "Block 2 changes nothing: its REPLACE text is its SEARCH text. Leave such a block out."),
                new Refused("malformed", first + SkillEdits.SEARCH + "\nimport json\n" + SkillEdits.REPLACE + "\n",
                        "Block 2 has no ======= line between its <<<<<<< SEARCH line and its >>>>>>> REPLACE line."));
        for (Refused refused : cases) {
            var rig = existing(tmp.resolve(refused.what().replace(' ', '_')));
            rig.cloud.codegen.add(ends(refused.reply()));
            rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 7")));

            AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

            assertNull(out.error(), refused.what() + ": " + out.error());
            List<Call> calls = rig.cloud.calls("codegen");
            assertEquals(2, calls.size(), refused.what());
            List<LlmMessage> again = calls.get(1).messages();
            assertEquals(calls.get(0).messages(), again.subList(0, 2), refused.what() + ": the same request, asked on");
            assertEquals(LlmMessage.assistant(refused.reply().stripTrailing()), again.get(2),
                    refused.what() + ": the model's own reply, as its turn");
            assertEquals(LlmMessage.user(refused.told() + "\n\n" + SkillEdits.AGAIN), again.get(3), refused.what());
            assertEquals(4, again.size());
            assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 7"), out.params().get("code"),
                    refused.what() + ": KEEP = 3 was still there: block 1 of the refused reply was not applied");
            assertEquals(COPY.replace("KEEP = 3", "KEEP = 7"), out.asWritten(), refused.what());
        }
        // Mutation: apply the blocks that can be applied and pass over the one that cannot -> no repair is asked for,
        // and the skill is changed by what was left of a reply.
    }

    @Test
    @DisplayName("lines that stand at several places: the model is told at how many, and to add the lines around them; with those, the block is applied")
    void atSeveralPlaces(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        String twice = COPY.replace("def run(params):", "def count(names):\n    total = 0\n    return total\n\ndef run(params):\n    total = 0");
        rig.skills.asWritten = name -> twice;
        rig.skills.source = name -> twice.replace("<ssid_1>", "ExampleHome5G").replace("<ssid_2>", "AP7");
        rig.cloud.codegen.add(ends(block("    total = 0", "    total = 1")));
        rig.cloud.codegen.add(ends(block("def run(params):\n    total = 0", "def run(params):\n    total = 1")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals("Block 1 was not applied: its SEARCH text stands at 2 places in the code. Add the lines before or "
                + "after it to SEARCH, and to REPLACE, until it matches exactly one place.\n\n" + SkillEdits.AGAIN,
                rig.cloud.calls("codegen").get(1).messages().getLast().content());
        assertEquals(twice.replace("def run(params):\n    total = 0", "def run(params):\n    total = 1"), out.asWritten(),
                "the one in run, and not the one in count");
        // Mutation: apply at the first place -> count() starts at 1, and run() at 0.
    }

    @Test
    @DisplayName("edits that go round -- the same blocks again, or the same failure in other blocks -- are given up, and the complete code is asked for once, by the request of before: answered, the skill is made of it, and the step says it was written whole")
    void goingRoundAsksForTheWholeCode(@TempDir Path tmp) throws Exception {
        String missing = block("KEEP = 4", "KEEP = 6");
        // The same reply again; and the same lines not found again, in a reply that is another.
        for (String second : List.of(missing, "Again:\n" + block("import json", "import json\nimport os") + missing)) {
            var rig = existing(tmp.resolve("r" + second.length()));
            var statuses = rig.statuses();
            rig.cloud.codegen.add(ends(missing));
            rig.cloud.codegen.add(ends(second));
            rig.cloud.codegen.add(finished(COPY.replace("KEEP = 3", "KEEP = 6")));
            var ctx = task();

            AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), ctx);

            assertNull(out.error(), out.error());
            List<Call> calls = rig.cloud.calls("codegen");
            assertEquals(3, calls.size(), "the blocks, their repair, and the complete code -- no more blocks asked for");
            assertEquals(theOldWay(calls.get(0)), calls.get(2).messages(),
                    "the request of before, alone: nothing of the exchange given up goes with it");
            assertTrue(user(calls.get(2)).contains(ASK_BEFORE) && !user(calls.get(2)).contains("edit blocks"), user(calls.get(2)));
            assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 6"), out.params().get("code"));
            assertEquals(COPY.replace("KEEP = 3", "KEEP = 6"), out.asWritten());
            List<String> said = texts(statuses);
            assertTrue(said.contains("Asking for the edits again · cloud (attempt 1)"), said.toString());
            assertTrue(said.contains("Edits not applied · writing the whole code · cloud"), said.toString());
            assertTrue(said.contains("Skill code regenerated whole (" + COPY.length() + " characters) · cloud"), said.toString());
            assertEquals(3 * 7_000, ctx.cloudTokens(), "every call counted, as before");
            assertEquals(1, calls.get(2).config().egress().handedOut().size(),
                    "the gateway holds the last reply only: the blocks given up are forgotten");
        }
        // Mutation: give the code up where the edits go round -> "ERROR: the edit blocks ... kept coming back", and no skill.
    }

    @Test
    @DisplayName("after the edits are given up the complete code is written as it always was: its own repairs, and its own end when those go round -- once, never back to blocks")
    void theOldWayEndsAsItDid(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 5: invalid syntax" : null;
        String broken = COPY + "\ndef broken(:\n    pass";
        rig.cloud.codegen.add(ends(block("KEEP = 4", "KEEP = 6")));
        rig.cloud.codegen.add(ends(block("KEEP = 4", "KEEP = 6")));
        rig.cloud.codegen.add(finished(broken));
        rig.cloud.codegen.add(finished(broken));
        rig.cloud.codegen.add(finished(COPY));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertEquals("ERROR: Python syntax error: line 5: invalid syntax", out.error(), "as the complete code's repairs end");
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(4, calls.size(), "two for the blocks, two for the code: nothing is asked a third way");
        assertTrue(calls.get(3).messages().getLast().content().endsWith("Fix the error. Return the COMPLETE corrected code "
                + "in a ```python fence, and the ```requirements fence as asked."), "the repair of before");
        assertFalse(SkillCodeAsWrittenTest.everything(calls.get(3)).contains("edit blocks"), "no word of blocks in it");
        // Mutation: go back to asking for blocks after the complete code failed -> a fifth call, and a loop of two ways.
    }

    @Test
    @DisplayName("a syntax error in what the blocks give: the model is told the error at the result's own line, with the result's lines around it under their numbers -- a line that holds a placeholder by its number alone -- and corrected blocks are applied to the same code")
    void aSyntaxErrorInTheResult(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.syntax = code -> {
            String[] lines = code.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].equals("KEEP = (5")) {
                    return "  File \"skill.py\", line " + (i + 1) + "\n    KEEP = (5\n           ^\nSyntaxError: '(' was never closed";
                }
            }
            return null;
        };
        // A line added above moves every line below it: the error is at line 6 of the result, line 5 of the code.
        String broken = block("import json", "import json\nimport os") + block("KEEP = 3", "KEEP = (5");
        rig.cloud.codegen.add(ends(broken));
        rig.cloud.codegen.add(ends(block("import json", "import json\nimport os") + block("KEEP = 3", "KEEP = 5")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(2, calls.size());
        List<LlmMessage> repair = calls.get(1).messages();
        assertEquals(calls.get(0).messages(), repair.subList(0, 2));
        assertEquals(LlmMessage.assistant(broken.stripTrailing()), repair.get(2));
        assertEquals("With your edit blocks applied, the code failed Python's syntax check at line 6 of the result: "
                + "SyntaxError: '(' was never closed.\n"
                + "The result around that line, each line under its number in the result:\n"
                + "3: \n"
                + "4: (a line that names a value: not repeated here)\n"
                + "5: (a line that names a value: not repeated here)\n"
                + "6: KEEP = (5\n"
                + "7: \n"
                + "8: def run(params):\n"
                + "9:     names = params.get('names', [])\n\n" + SkillEdits.AGAIN, repair.get(3).content());
        for (LlmMessage m : repair) {
            assertFalse(m.content().contains("ExampleHome5G") || m.content().contains("'AP7'"), "a value went: " + m.content());
        }
        assertEquals(ON_DISK.replace("import json", "import json\nimport os").replace("KEEP = 3", "KEEP = 5"),
                out.params().get("code"), "the corrected blocks, applied to the code as it was shown");
        // Mutation: ask for the COMPLETE corrected code, as a complete reply's repair does -> the whole file again.
    }

    @Test
    @DisplayName("the lines told with a syntax error are the code's as the model was shown it, never the copy's own: a copy that holds the user's values -- the local model wrote it -- has a value inside a longer word beside the error, and no request holds it")
    void aSyntaxErrorBesideAValueTheCopyHolds(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.asWritten = name -> ON_DISK;   // as the local model writes one: the values themselves
        rig.skills.syntax = code -> code.contains("KEEP = (5") ? "  File \"skill.py\", line 5\n    KEEP = (5\n           ^\n"
                + "SyntaxError: '(' was never closed" : null;
        rig.cloud.codegen.add(c -> {
            assertEquals(COPY, shown(rig.cloud.calls("codegen").get(0)), "the premise: its values were replaced on the way out");
            return ends(block("KEEP = 3", "KEEP = (5")).answer(c);
        });
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<LlmMessage> repair = rig.cloud.calls("codegen").get(1).messages();
        assertTrue(repair.getLast().content().contains("3: (a line that names a value: not repeated here)\n"
                + "4: (a line that names a value: not repeated here)\n5: KEEP = (5\n"), repair.getLast().content());
        for (Call c : rig.cloud.calls("codegen")) {
            String sent = SkillCodeAsWrittenTest.everything(c);
            assertFalse(sent.contains("ExampleHome5G_backup") || sent.contains("'AP7'"), "a value went to the cloud: " + sent);
        }
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.params().get("code"));
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.asWritten(), "the copy is the skill's own lines, as it was");
        // Mutation: tell the lines of the result as it is kept -> "ARCHIVE = '/tmp/ExampleHome5G_backup.tar'" and
        // "GUEST = 'AP7'" go to the cloud in the repair: the filter of prose replaces neither.
    }

    @Test
    @DisplayName("each attempt's blocks are looked up in the code as that attempt's own request sent it: a network the table learns between two attempts is replaced in the code the second time, and the corrected blocks name the line as it reads then")
    void whatEachAttemptWasShown(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        String copy = COPY.replace("KEEP = 3", "KEEP = 3\nNOTE = 'MyHomeNet'");   // a literal of the model's own
        rig.skills.asWritten = name -> copy;
        rig.skills.source = name -> ON_DISK.replace("KEEP = 3", "KEEP = 3\nNOTE = 'MyHomeNet'");
        rig.cloud.codegen.add(c -> {
            assertTrue(shown(rig.cloud.calls("codegen").getLast()).contains("NOTE = 'MyHomeNet'"), "the premise: sent as it is");
            // Meanwhile another skill of the account's is written, and its specification names that network.
            rig.cloud.codegen.addFirst(finished(module("")));
            var other = spec();
            other.put("description", "Watches the network ssid=\"MyHomeNet\".");
            assertNull(rig.loop.generateSkillCode(other, task()).error());
            return ends(block("NOTE = 'my home net'", "NOTE = 'home'")).answer(c);   // refused: no such line
        });
        String[] line = new String[1];
        rig.cloud.codegen.add(c -> {
            var m = java.util.regex.Pattern.compile("NOTE = '<ssid_\\d+>'").matcher(shown(rig.cloud.calls("codegen").getLast()));
            assertTrue(m.find(), "the premise: the same code, sent with the network replaced now");
            line[0] = m.group();
            return ends(block(line[0], line[0] + "  # the other one")).answer(c);
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(3, rig.cloud.calls("codegen").size(), "the blocks, the other skill, the corrected blocks: no whole file");
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 3\nNOTE = 'MyHomeNet'  # the other one"), out.params().get("code"));
        assertEquals(copy.replace("NOTE = 'MyHomeNet'", line[0] + "  # the other one"), out.asWritten());
        // Mutation: keep what the request before sent the code as -> sent as two texts, it is known as neither, and
        // the whole file is asked for.
    }

    @Test
    @DisplayName("a syntax error that comes back in what the next blocks give is going round: the complete code is asked for")
    void aSyntaxErrorThatComesBack(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.syntax = code -> code.contains("KEEP = (") ? "line 5: '(' was never closed" : null;
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = (5")));
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = (5  # five")));   // other code, the same error on its line
        rig.cloud.codegen.add(finished(COPY.replace("KEEP = 3", "KEEP = 5")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(3, rig.cloud.calls("codegen").size());
        assertEquals(theOldWay(rig.cloud.calls("codegen").get(0)), rig.cloud.calls("codegen").get(2).messages());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.params().get("code"));
        // Mutation: tell going round by what came back alone, not by its error -> the same error from other blocks
        // is asked about again, and the third call is one more repair of blocks.
    }

    @Test
    @DisplayName("a reply that holds blocks and the code in a ```python fence is refused -- neither is applied -- and the model is asked for one of the two; a reply to the request for blocks that holds the complete code alone is read as that")
    void bothFormsAndTheWholeCodeAlone(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        String whole = "```python\n" + COPY.replace("KEEP = 3", "KEEP = 9") + "\n```\n";
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5") + "\nor all of it:\n\n" + whole));
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(2, rig.cloud.calls("codegen").size());
        assertEquals("Your reply holds edit blocks and also code in a ```python fence outside them: which of the two is "
                + "the change cannot be told. Return one of them only.\n\n" + SkillEdits.AGAIN,
                rig.cloud.calls("codegen").get(1).messages().getLast().content());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.params().get("code"), "neither the 9 of the fence nor nothing");

        // Most of the file changes: the model returns it whole, as the request allows.
        var rewritten = existing(tmp.resolve("whole"));
        var statuses = rewritten.statuses();
        rewritten.cloud.codegen.add(ends(whole));
        AgentLoop.Codegen all = rewritten.loop.generateSkillCode(fix(), task());
        assertNull(all.error(), all.error());
        assertEquals(1, rewritten.cloud.calls("codegen").size());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 9"), all.params().get("code"));
        assertEquals(COPY.replace("KEEP = 3", "KEEP = 9"), all.asWritten());
        assertTrue(texts(statuses).contains("Skill code regenerated whole (" + COPY.length() + " characters) · cloud"),
                texts(statuses).toString());
        // Mutation: take the blocks of a reply that holds both -> KEEP = 5 from half a reply, with the other half dropped.
    }

    @Test
    @DisplayName("the complete code in answer to the request for blocks is the reply the request allows: its syntax error is repaired as the complete code's always was, in the same exchange; and one nothing can be made of asks for the complete code the old way before the code is given up")
    void theWholeCodeInAnswer(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        rig.skills.syntax = code -> code.contains("def broken(:") ? "line 11: invalid syntax" : null;
        rig.cloud.codegen.add(finished(COPY + "\n\ndef broken(:\n    pass"));
        rig.cloud.codegen.add(finished(COPY.replace("KEEP = 3", "KEEP = 5")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(2, calls.size());
        List<LlmMessage> repair = calls.get(1).messages();
        assertEquals(calls.get(0).messages(), repair.subList(0, 2), "the same request, which asks for blocks");
        assertEquals("The code failed Python's syntax check at line 11: invalid syntax.\nLine 11, as you wrote it:\n"
                + "def broken(:\n\nFix the error. Return the COMPLETE corrected code in a ```python fence, and the "
                + "```requirements fence as asked.", repair.getLast().content());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), out.params().get("code"));

        // Code without the function every skill has: nothing a repair is asked for.
        var none = existing(tmp.resolve("none"));
        var statuses = none.statuses();
        none.cloud.codegen.add(ends("```python\nimport json\nprint(json.dumps(1))\n```"));
        none.cloud.codegen.add(finished(COPY.replace("KEEP = 3", "KEEP = 7")));
        AgentLoop.Codegen made = none.loop.generateSkillCode(fix(), task());
        assertNull(made.error(), made.error());
        assertEquals(theOldWay(none.cloud.calls("codegen").get(0)), none.cloud.calls("codegen").get(1).messages());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 7"), made.params().get("code"));
        assertTrue(texts(statuses).contains("Asking for the whole code as before · cloud"), texts(statuses).toString());
        // Mutation: take a reply without blocks for no answer to the request -> the complete code the request allows
        // is thrown away, and the whole file is written a second time.
    }

    @Test
    @DisplayName("a reply of blocks cut off at the maximum output is continued and joined as code is: its whole lines go back as the model's turn, the request to go on names no code fence, and the blocks of the joined reply are applied")
    void inParts(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        String reply = block("KEEP = 3", "KEEP = 5")
                + block("    names = params.get('names', [])", "    names = sorted(params.get('names', []))")
                + "\n```requirements\nrequests\n```\n";
        int cut = reply.indexOf("    names = sorted(") + 12;   // inside the line that replaces
        rig.cloud.codegen.add(cutOff(reply.substring(0, cut)));
        rig.cloud.codegen.add(exactly(rig, reply));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(2, calls.size());
        assertEquals(LlmMessage.assistant(ReplySeam.wholeLines(reply.substring(0, cut)).stripTrailing()),
                calls.get(1).messages().get(2), "the part in whole lines, the line it was cut in dropped");
        assertEquals(LlmMessage.user(SkillEdits.CONTINUE), calls.get(1).messages().get(3));
        assertFalse(SkillEdits.CONTINUE.contains("```python"), "a reply of blocks has no code fence to close");
        assertEquals(List.of("high", "low"), efforts(rig));
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5")
                .replace("    names = params.get('names', [])", "    names = sorted(params.get('names', []))"),
                out.params().get("code"));
        assertEquals("requests", out.params().get("requirements"));
        // Mutation: read only the last part for blocks -> block 1 is lost, and block 2 has no SEARCH line.
    }

    @Test
    @DisplayName("a reply of blocks in parts whose seam reads two ways: joined first, the lines to replace stand at two places; with the seam read otherwise they stand at one, and that reading is taken before any repair is asked for")
    void inPartsReadOtherwise(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        String code = COPY.replace("def run(params):", "def run(params):\n    total = 0\n    total += 1\n    total += 1");
        rig.skills.asWritten = name -> code;
        rig.skills.source = name -> code.replace("<ssid_1>", "ExampleHome5G").replace("<ssid_2>", "AP7");
        // Cut after a line's end, before a line equal to it; the model goes on with that line, and no line break first.
        rig.cloud.codegen.add(cutOff(SkillEdits.SEARCH + "\n    total += 1\n"));
        rig.cloud.codegen.add(ends("    total += 1\n" + SkillEdits.DIVIDER + "\n    total += 2\n" + SkillEdits.REPLACE + "\n"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(2, rig.cloud.calls("codegen").size(), "no repair: another reading of the seam gave blocks that apply");
        assertEquals(code.replace("    total += 1\n    total += 1", "    total += 2"), out.asWritten());
        // Mutation: read a reply in parts one way only -> "stands at 2 places", and a third call for what was written.
    }

    @Test
    @DisplayName("where the gateway did not say what it sent the code as, blocks have nothing to be applied to: none is, no repair is asked for, and the complete code is asked for the old way")
    void whatWasSentIsNotKnown(@TempDir Path tmp) throws Exception {
        var rig = existing(tmp);
        // Shorter than the gateway looks for: it is sent, filtered as any text, and nothing says as what.
        String tiny = "def run(p):pass";
        rig.skills.source = name -> tiny;
        rig.skills.asWritten = name -> null;
        rig.cloud.codegen.add(ends(block("def run(p):pass", "def run(p):return {}")));
        rig.cloud.codegen.add(finished("def run(p):\n    return {'output': '', 'success': True}"));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(2, calls.size(), "not asked for blocks again: the next could not be applied either");
        assertEquals(theOldWay(calls.get(0)), calls.get(1).messages());
        assertEquals("def run(p):\n    return {'output': '', 'success': True}", out.params().get("code"));
        // Mutation: apply the blocks to the code as the task holds it -> a text the model was never shown is changed.
    }

    @Test
    @DisplayName("the local model is asked as before -- the complete code, the request word for word -- and a reply of blocks from it is no code")
    void theLocalModelIsAskedForTheWholeCode(@TempDir Path tmp) throws Exception {
        var asked = new CopyOnWriteArrayList<List<LlmMessage>>();
        var replies = new ArrayList<>(List.of(block("KEEP = 3", "KEEP = 5"), "```python\n" + ON_DISK.replace("KEEP = 3", "KEEP = 5") + "\n```"));
        LlmProvider local = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                asked.add(List.copyOf(m));
                return Replies.of(replies.remove(0), 700, 300);
            }
            public boolean isAvailable() { return true; }
            public String name() { return "ollama"; }
        };
        var rig = new LoopRig(tmp, List.of(), 600, local);
        rig.cloud.available = false;
        rig.skills.source = name -> ON_DISK;
        rig.skills.asWritten = name -> COPY;
        var statuses = rig.statuses();

        AgentLoop.Codegen blocks = rig.loop.generateSkillCode(fix(), task());

        assertEquals("ERROR: the reply for 'router_backup' held no Python code, so nothing was created.", blocks.error(),
                "blocks are not read where they were not asked for");
        assertEquals(1, asked.size(), "and nothing is asked again");
        assertEquals("Fix this skill:\n\nName: router_backup | Params: {\"hosts\": {\"type\": \"string\"}}\n"
                + "Description: Backs up the router. Its network is ssid=\"ExampleHome5G\", the guest one ssid=\"AP7\".\n"
                + "\nBroken code:\n```python\n" + ON_DISK + "\n```\nReturn complete fixed code.\n"
                + "\nTask context: \"audit the routers\"\n", asked.get(0).get(1).content());
        String system = asked.get(0).get(0).content();
        assertTrue(system.contains(OUTPUT_BEFORE) && !system.contains("edit blocks"), system);
        assertTrue(system.endsWith("FIXING existing skill. Minimal targeted fix — preserve working parts.\n"
                + "CRITICAL: You are a local model. Keep code SIMPLE. Use only stdlib + one well-known library. Avoid "
                + "complex logic. Prefer straightforward imperative code over abstractions.\n"), system);

        AgentLoop.Codegen whole = rig.loop.generateSkillCode(fix(), task());
        assertNull(whole.error(), whole.error());
        assertEquals(ON_DISK.replace("KEEP = 3", "KEEP = 5"), whole.params().get("code"));
        assertTrue(texts(statuses).contains("Skill code regenerated whole (" + ON_DISK.length() + " characters) · local (degraded)"),
                texts(statuses).toString());
        assertEquals(0, rig.cloud.calls.size(), "the cloud was not asked");
        // Mutation: ask for blocks wherever the skill exists -> the local model's request asks for them too.
    }

    @Test
    @DisplayName("a new skill is asked for as before -- the request word for word -- and a reply of blocks to it is no code: there is nothing they could be applied to")
    void aNewSkillIsAskedForAsBefore(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of());
        var statuses = rig.statuses();
        rig.cloud.codegen.add(ends(block("KEEP = 3", "KEEP = 5")));
        rig.cloud.codegen.add(finished(module("")));

        AgentLoop.Codegen blocks = rig.loop.generateSkillCode(spec(), task());

        assertEquals("ERROR: the reply for 'openwrt_audit' held no Python code, so nothing was created.", blocks.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(1, calls.size(), "no repair, and no second way");
        assertEquals("Generate skill code:\n\nName: openwrt_audit | Params: {\"hosts\": {\"type\": \"string\"}}\n"
                + "Description: Audit every OpenWrt router.\n\nTask context: \"audit the routers\"\n", user(calls.get(0)));
        String system = calls.get(0).messages().get(0).content();
        assertTrue(system.contains(OUTPUT_BEFORE) && !system.contains("edit blocks"), system);
        assertTrue(system.endsWith("Clean, efficient code. Established libraries. No unnecessary boilerplate.\n"), system);

        assertNull(rig.loop.generateSkillCode(spec(), task()).error());
        assertTrue(texts(statuses).stream().noneMatch(t -> t.startsWith("Skill code ")),
                "nothing was changed or written again: " + texts(statuses));
        // Mutation: read every reply for blocks -> a new skill's reply that holds a marker line is refused as blocks.
    }

    @Test
    @DisplayName("what it saves: for one changed line of a 340-line skill the reply is its block -- under a fiftieth of what the whole file again would be -- and the file that results is the old one but for that line")
    void theReplyIsASmallPartOfTheFile(@TempDir Path tmp) throws Exception {
        String big = SkillCodeSeamCensusTest.skill("big.py").strip();
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.skills.source = name -> big;
        rig.skills.asWritten = name -> big;
        var statuses = rig.statuses();
        String reply = block("        cutoff = time.time() - keep * 86400", "        cutoff = time.time() - max(keep, 1) * 86400")
                + "\n```requirements\n```\n";
        rig.cloud.codegen.add(ends(reply));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        assertEquals(1, rig.cloud.calls("codegen").size());
        // What the reply was before: the whole file in its fence, and the requirements.
        String whole = "```python\n" + shown(rig.cloud.calls("codegen").get(0)) + "\n```\n\n```requirements\n```\n";
        assertTrue(reply.length() * 50 < whole.length(), "the reply: " + reply.length() + " characters; the complete "
                + "code's: " + whole.length());
        String changed = big.replace("cutoff = time.time() - keep * 86400", "cutoff = time.time() - max(keep, 1) * 86400");
        assertEquals(changed, out.params().get("code"), "every other character of the 340 lines as it was");
        assertEquals(changed, out.asWritten());
        assertEquals(340, big.split("\n").length, "the premise: the skill the figures are of");
        assertTrue(texts(statuses).contains(String.format(java.util.Locale.ROOT, "Skill code changed by 1 edit (43 of its "
                + "%,d characters replaced by 51) · cloud", big.length())), texts(statuses).toString());
        // Mutation: ask for the complete code wherever the skill exists, as before -> a block is no code, and the only
        // reply that is one is the whole file: 9,806 characters for the 43 that changed.
    }

    @Test
    @DisplayName("lines the filter changes on the way out are the skill's own still: code it reads as a network's name, a comment it reads as a secret -- which no reply could be put back from -- stay as they are in a skill changed by blocks elsewhere")
    void linesTheFilterChangedStayTheSkillsOwn(@TempDir Path tmp) throws Exception {
        String big = SkillCodeSeamCensusTest.skill("big.py").strip();
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.skills.source = name -> big;
        rig.skills.asWritten = name -> big;
        String[] shown = new String[1];
        rig.cloud.codegen.add(c -> {
            shown[0] = shown(rig.cloud.calls("codegen").get(0));
            return ends(block("        cutoff = time.time() - keep * 86400", "        cutoff = time.time() - max(keep, 1) * 86400")).answer(c);
        });

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertTrue(shown[0].contains("key='«secret removed»'") && shown[0].contains("        ssid = <ssid_"),
                "the premise: a comment went out as a removed secret, and a line of code as a placeholder: " + shown[0]);
        assertEquals(big.split("\n").length, shown[0].split("\n").length, "and every line in its place");
        assertNull(out.error(), out.error());
        assertEquals(1, rig.cloud.calls("codegen").size());
        String code = String.valueOf(out.params().get("code"));
        assertTrue(code.contains("    # One setting per line: key='value'\n")
                && code.contains("        ssid = self.settings.get(\"wireless.default_radio0.ssid\")\n"), code);
        assertEquals(big.replace("cutoff = time.time() - keep * 86400", "cutoff = time.time() - max(keep, 1) * 86400"), code);
        // Mutation: carry the change out on the code as it was sent -> it holds a removed secret, nothing is made of
        // it, and the whole file is asked for -- which, copied faithfully from what was sent, is refused too.
    }

    @Test
    @DisplayName("without a copy the change is carried out on what was sent, and what the filter took out cannot be put back: what the blocks give holds its marker, as the complete code would; that is asked for the old way, once, and made as before")
    void whatTheFilterTookOutCannotBePutBack(@TempDir Path tmp) throws Exception {
        String big = SkillCodeSeamCensusTest.skill("big.py").strip();
        var rig = new LoopRig(tmp, List.of());
        realSyntaxCheck(rig, tmp);
        rig.skills.source = name -> big;
        var statuses = rig.statuses();
        rig.cloud.codegen.add(ends(block("        cutoff = time.time() - keep * 86400", "        cutoff = time.time() - max(keep, 1) * 86400")));
        rig.cloud.codegen.add(finished(module("")));

        AgentLoop.Codegen out = rig.loop.generateSkillCode(fix(), task());

        assertNull(out.error(), out.error());
        List<Call> calls = rig.cloud.calls("codegen");
        assertEquals(2, calls.size(), "the blocks, then the complete code: no repair of blocks nothing was wrong with");
        assertTrue(shown(calls.get(0)).contains("key='«secret removed»'"), "the premise: the code went out without it");
        assertEquals(theOldWay(calls.get(0)), calls.get(1).messages());
        assertEquals(module("").strip(), out.params().get("code"));
        assertTrue(texts(statuses).contains("Edits not applied · writing the whole code · cloud"), texts(statuses).toString());
        // Mutation: give the code up as a complete reply's is -> "holds a removed secret, so nothing was created", where
        // the request of before could still be answered with code that holds none.
    }
}
