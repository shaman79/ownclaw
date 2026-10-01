package com.ownclaw.agent;

import com.ownclaw.core.Inbox;
import com.ownclaw.core.UserMessage;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the owner sends a task while it works, through the real loop, engine and gateway: read
 * before the next step, in its place in the prompt of every provider, the prefix the provider
 * cached left as it was; recorded in his chat as read; and his own words to the canary.
 */
class MessagesWhileWorkingTest {

    static final String STEER = "Use the backup link instead.";

    /** A message the owner sent in the chat, saved as {@code row}. */
    static UserMessage owner(String row, String text) {
        return new UserMessage("u1", "chat", row, text, List.of(), TaskChat.Channel.WEB, r -> { });
    }

    /** A rig whose first step sends the task a message while it runs, as the chat would. */
    record Run(LoopRig rig, Inbox inbox, String chat, String asked, String steering) {}

    static Run run(Path tmp, String provider) throws Exception {
        var inbox = new Inbox(TaskChat.Channel.WEB);
        String[] steering = new String[1];
        var rig = new LoopRig(tmp, List.of(
                AssistantPartsTest.tool("router_status", List.of(), p -> {
                    inbox.offer(owner(steering[0], STEER), 2);
                    return "main link down";
                }),
                AssistantPartsTest.tool("backup_link", List.of(), p -> "backup link up")));
        rig.cloud.name = provider;
        String chat = rig.chat.createSession("u1", "Network");
        String asked = rig.chat.saveMessage("u1", chat, "user", "Check the uplink.");
        // Saved as the chat saves it when it is sent, during step 1: after the task's own row.
        steering[0] = rig.chat.saveMessage("u1", chat, "user", STEER);
        rig.cloud.think.add(call("router_status", Map.of()));
        rig.cloud.think.add(call("backup_link", Map.of()));
        rig.cloud.think.add(respond("The backup link is up."));
        return new Run(rig, inbox, chat, asked, steering[0]);
    }

    static String lastUser(LoopRig.Call call) {
        return LoopRig.userParts(call).getLast();
    }

    @Test
    @DisplayName("a message sent during a step is read before the next: after that step's result, the cached prefix unchanged; the chat says it was read")
    void readAtTheNextStepInItsPlace(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, "anthropic");
        var statuses = run.rig().statuses();

        AgentResult r = run.rig().loop.executeFull("u1", "Check the uplink.", false, run.asked(), List.of(),
                TaskChat.Channel.WEB, run.inbox());

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        var calls = run.rig().cloud.calls("think");
        assertEquals(3, calls.size());
        String told = AgentLoop.FROM_THE_OWNER + STEER;
        assertFalse(String.join("\n", LoopRig.userParts(calls.get(0))).contains(STEER),
                "not yet sent at step 1, and not read as part of the chat before it");
        String second = lastUser(calls.get(1));
        assertTrue(second.contains(told) && second.indexOf("main link down") < second.indexOf(told),
                "in its place, after the result of the step it came during: " + second);

        // Append-only: every message of step 2's request but the last is step 3's, and step 2's
        // last begins with the same message of step 3 -- the per-step block is what it loses.
        List<LlmMessage> two = calls.get(1).messages(), three = calls.get(2).messages();
        for (int i = 0; i < two.size() - 1; i++) {
            assertEquals(two.get(i).content(), three.get(i).content(), "message " + i + " changed");
        }
        assertTrue(two.getLast().content().startsWith(three.get(two.size() - 1).content()),
                "the message joined the turn it was read in, and stays there");
        assertTrue(three.get(two.size() - 1).content().endsWith(told));

        assertEquals(List.of("router_status", AgentLoop.FROM_THE_OWNER_STEP, "backup_link"),
                r.trajectory().turns().stream().map(t -> t.action().tool()).toList());
        assertEquals(2, r.totalSteps(), "his message is no step of the task's");
        var done = statuses.stream().filter(s -> s.type() == StatusMessage.Type.COMPLETED).toList().getLast();
        assertTrue(done.text().startsWith("2 steps · "), done.text());
        assertEquals(2, done.data().get("totalSteps"), "the count Telegram shows");
        assertEquals(2, done.data().get("successCount"));
        // Mutation: count every turn -> "3 steps", one of them his message.

        List<String> progress = run.rig().jdbc.queryForList(
                "SELECT content FROM conversations WHERE session_id = ? AND role = 'progress' ORDER BY rowid",
                String.class, run.chat());
        assertTrue(progress.get(1).startsWith("☁️ Step 2 · your message · ")
                        && progress.get(1).endsWith("\n\nRead your message: part of the task from this step on."),
                "the chat says the task read it, before the step that follows it: " + progress);
        assertTrue(progress.get(2).startsWith("☁️ Step 2 · backup_link"), progress.toString());
        assertTrue(statuses.stream().anyMatch(s -> s.type() == StatusMessage.Type.PROGRESS_MESSAGE
                        && List.of(run.steering()).equals(s.data().get("read"))),
                "its live frame names the row, for the page to mark it read");
        assertEquals(List.of(), run.inbox().drain(), "read once");
        // Mutation: drop readMessages -> step 2's prompt lacks the message, and the task answers
        // without it.
    }

    @Test
    @DisplayName("a provider without turn-by-turn replay shows it too, as a step of its own in the history")
    void theHistoryShowsItInItsPlace(@TempDir Path tmp) throws Exception {
        Run run = run(tmp, "openai");
        AgentResult r = run.rig().loop.executeFull("u1", "Check the uplink.", false, run.asked(), List.of(),
                TaskChat.Channel.WEB, run.inbox());

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        String history = lastUser(run.rig().cloud.calls("think").get(1));
        String told = "[Step 2] " + AgentLoop.FROM_THE_OWNER + STEER;
        assertTrue(history.contains(told) && history.indexOf("main link down") < history.indexOf(told), history);
        assertTrue(lastUser(run.rig().cloud.calls("think").get(2)).contains(told + "\n\n[Step 3] "),
                "and before the step after it");
    }

    @Test
    @DisplayName("a message quoting a private result is the owner's own words: the request carrying it is sent, the result still is not")
    void quotingAPrivateResultIsHisToDo(@TempDir Path tmp) throws Exception {
        // Mail is a personal source, so what the skill reads stays private (a router's config no
        // longer does: the privacy filter sends it, without secrets and identifiers).
        String mail = "From the landlord: the heating in the flat is serviced on Thursday morning, "
                + "please leave the boiler room unlocked.";
        var inbox = new Inbox(TaskChat.Channel.WEB);
        String quote = "About \"the heating in the flat is serviced on Thursday morning\": I will be home.";
        var rig = new LoopRig(tmp, List.of(AssistantPartsTest.tool("mail_read", List.of("IMAP_PASS"), p -> {
            inbox.offer(owner("r2", quote), 2);
            return mail;
        })));
        rig.cloud.think.add(call("mail_read", Map.of()));
        rig.cloud.think.add(respond("Noted."));

        AgentResult r = rig.loop.executeFull("u1", "Read my mail.", false, null, List.of(), null, inbox);

        assertEquals(AgentResult.TerminationReason.COMPLETED, r.terminationReason(), r.response());
        String second = lastUser(rig.cloud.calls("think").get(1));
        assertTrue(second.contains(AgentLoop.FROM_THE_OWNER + quote), second);
        assertFalse(second.contains("please leave the boiler room unlocked"), "the private result itself stays here");
        // Mutation: leave his messages out of the excuses -> the canary refuses step 2's request,
        // and the task ends PRIVACY_BLOCKED over words he typed.
    }
}
