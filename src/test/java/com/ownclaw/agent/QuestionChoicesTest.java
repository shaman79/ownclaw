package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.conversation.ChatOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static com.ownclaw.agent.LoopRig.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A question's answers to pick from: ask_user's choices, which the web chat draws as buttons under
 * the question and Telegram as keys, each sent as the owner's reply when picked. They travel with
 * the question from the call to the saved row the page draws again after a reload, written as the
 * question is -- no vault value, no handle -- and none is offered that the chats would read as a
 * command rather than an answer.
 */
class QuestionChoicesTest {

    static final Tool NOOP = tool("noop", List.of(), p -> "nothing to report");

    private static List<String> choicesOf(Object choices) {
        var params = new java.util.HashMap<String, Object>();
        params.put("message", "Which?");
        params.put("choices", choices);
        return new AgentAction(AgentAction.ASK_USER, params, "").choices();
    }

    @Test
    @DisplayName("choices are read as the model wrote them: a list, a JSON array or one per line; each once, blanks dropped")
    void choicesAreReadAsWritten() {
        assertEquals(List.of("Delete them", "Keep them"), choicesOf(List.of(" Delete them ", "Keep them", "", "Delete them")));
        assertEquals(List.of("Delete them", "Keep them"), choicesOf("[\"Delete them\", \"Keep them\"]"),
                "the text protocol's JSON array");
        assertEquals(List.of("Delete them", "Keep them"), choicesOf("Delete them\n\nKeep them\n"), "one per line");
        assertEquals(List.of("[not json"), choicesOf("[not json"), "a broken array is a line");
        assertEquals(List.of("3", "true"), choicesOf(List.of(3, true, Map.of("label", "x"))),
                "a number or a yes/no is its text; a structure is no answer");
        assertEquals(List.of(), choicesOf(null));
        assertEquals(List.of(), choicesOf(Map.of("a", "b")));
        assertEquals(List.of(), new AgentAction(AgentAction.RESPOND,
                Map.of("message", "done", "choices", List.of("a")), "").choices(), "an answer offers none");
    }

    @Test
    @DisplayName("a choice the chats would read as a slash command, or that holds a secret command, is not offered")
    void noChoiceIsACommand() {
        assertEquals(List.of("Clean /tmp"), choicesOf(List.of("/cancel", " /new", "/var/log", "Clean /tmp",
                "Store it: /cred set WIFI_PASS", "store this: /cred set KEY VALUE")));
        // Mutation: drop the misplacedSecret test -> "Store it: /cred set WIFI_PASS" is a button the
        // chat refuses, and the buttons come back after a reload as if unanswered.
    }

    @Test
    @DisplayName("a question's choices reach the saved row the page draws: no vault value, none naming a result, each once")
    void theChoicesReachTheSavedRow(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");   // the vault's salt lives on it
        rig.vault.storeCredential("u1", "ROUTER_PASS", "hunter2-router-admin");
        rig.cloud.think.add(call("noop", Map.of()));
        rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which router first?",
                "choices", List.of("The main one", "Log in with hunter2-router-admin", "Use {{1}}",
                        "Log in with «vault:ROUTER_PASS»"))));
        String session = rig.chat.createSession("u1", "Network");
        AgentResult r = rig.turn(session, "fix the routers", ChatOptions.NONE);

        assertTrue(r.awaitingUser(), r.response());
        assertEquals(List.of("The main one", "Log in with «vault:ROUTER_PASS»"), r.choices(),
                "a handle would name another result in the task the answer starts");
        assertEquals(r.choices(), r.withTaskId("0badc0de").withOwnerText("x").choices(), "kept by every copy of it");

        var rows = rig.chat.getSessionMessages("u1", session, null, null).messages();
        var asked = rows.getLast();
        assertEquals("assistant", asked.get("role"));
        assertTrue(asked.get("choices") instanceof com.fasterxml.jackson.databind.JsonNode list && list.isArray(),
                "a list the page reads, not its JSON text: " + asked.get("choices"));
        assertEquals("[\"The main one\",\"Log in with «vault:ROUTER_PASS»\"]",
                String.valueOf(asked.get("choices")), "drawn again after a reload");
        assertEquals(r.taskId(), asked.get("task_id"), "and still linked to its task");
        assertNull(rows.getFirst().get("choices"), "the owner's message offers none");
        // Mutation: drop withChoices in the loop -> no buttons; save without them -> none after a reload.
    }

    @Test
    @DisplayName("a question offering none, and an answer, are saved as before: the task id alone")
    void noChoicesIsTheRowAsBefore(@TempDir Path tmp) throws Exception {
        var rig = new LoopRig(tmp, List.of(NOOP));
        rig.cloud.think.add(call(AgentAction.ASK_USER, Map.of("message", "Which router first?")));
        String session = rig.chat.createSession("u1", "Network");
        AgentResult r = rig.turn(session, "fix the routers", ChatOptions.NONE);

        assertTrue(r.awaitingUser(), r.response());
        assertEquals(List.of(), r.choices());
        assertEquals("{\"taskId\":\"" + r.taskId() + "\"}", rig.jdbc.queryForObject(
                "SELECT metadata FROM conversations WHERE role = 'assistant'", String.class));
    }
}
