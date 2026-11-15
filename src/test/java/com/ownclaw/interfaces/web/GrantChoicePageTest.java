package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The page's side of a grant offered to click: the command a task's ending writes for a skill
 * that waits for a credential comes with the answer as one of its choices, and the page draws
 * it as a button under that answer. There is no JS engine in the suite, so the lines that decide
 * are pinned as text, whitespace collapsed.
 */
class GrantChoicePageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        try (var in = GrantChoicePageTest.class.getResourceAsStream("/static/index.html")) {
            assertNotNull(in, "static/index.html is not on the classpath");
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("a finished answer's choices are drawn under it when it arrives, as a question's are: the command is a button without a reload")
    void anAnswersChoicesAreDrawnWhenItArrives() {
        String drawn = page.substring(page.indexOf("var drawnMsg = addMsg(type, content, data.taskId, null, data.timestamp);"));
        drawn = drawn.substring(0, drawn.indexOf("if (type === 'user' && data.clientId) sentBubbles[data.clientId] = drawnMsg;"));
        assertTrue(drawn.contains("addChoices(drawnMsg, data.choices);"),
                "an answer that arrives is drawn without the choices it came with: " + drawn);
        // A question's, and a saved row's after a reload, as before.
        assertTrue(page.contains("addChoices(addMsg('input_request', content, data.taskId, null, data.timestamp), data.choices);"));
        assertTrue(page.contains("if (offered.length && (type === 'input_request' || type === 'response')) addChoices(drawn, offered);"));
        // Mutation: the answer drawn alone, as before -> the command is text until the page is reloaded.
    }

    @Test
    @DisplayName("a saved row whose only offers are commands is drawn as an answer, and a command's button is not done by the owner's next message")
    void commandsAreNoAnswers() {
        // Only an offer that is no command makes a saved row a question.
        assertTrue(page.contains("var asked = offered.some(function(c) { return String(c).trim().charAt(0) !== '/'; });"));
        // Commands are a group of their own, which the owner's next message leaves as it is.
        assertTrue(page.contains("(String(choice).trim().charAt(0) === '/' ? commands : answers).push(choice);"));
        assertTrue(page.contains("var group = node('div', offered === commands ? 'msg-choices commands' : 'msg-choices');"));
        assertTrue(page.contains("messagesEl.querySelectorAll('.msg-choices:not(.retired):not(.commands)')"));
        // Mutation: settleChoices over every group -> the granting command is greyed out by the next
        // message although the skill still waits (the browser check's step 9 fails).
    }

    @Test
    @DisplayName("a picked choice is sent as it stands, as a typed message is: the server reads the command in it, and a command retires no buttons")
    void aPickedChoiceIsSentAsTyped() {
        assertTrue(page.contains("if (!group.classList.contains('retired')) send(false, choice);"), "a click sends the choice");
        assertTrue(page.contains("var picked = typeof choice === 'string'; var text = picked ? choice : inputEl.value.trim();"),
                "its text, not the box's");
        assertTrue(page.contains("var payload = { message: text, clientId: clientId,"), "as the message");
        assertTrue(page.contains("if (said.trim().charAt(0) === '/') continue;"),
                "a command answers no question: the buttons stay until the owner's next message");
    }
}
