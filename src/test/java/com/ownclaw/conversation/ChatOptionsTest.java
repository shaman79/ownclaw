package com.ownclaw.conversation;

import com.ownclaw.config.OwnClawConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a chat has chosen of the owner's defaults, on the real schema: none for a new chat, kept
 * once a message chose it, its user's alone; and what is no choice.
 */
class ChatOptionsTest {

    @Test
    @DisplayName("a chat chooses nothing until a message does; what it chose is kept, and its user's alone")
    void keptPerChatPerUser(@TempDir Path tmp) throws Exception {
        var conversations = new ConversationService(MigratedDatabase.at(tmp.resolve("t.db")));
        String mine = conversations.createSession("u1", "Network");
        String other = conversations.createSession("u1", "Mail");
        String theirs = conversations.createSession("u2", "Theirs");
        assertEquals(ChatOptions.NONE, conversations.chatOptions("u1", mine), "a new chat follows the defaults");

        conversations.setChatOptions("u1", mine, new ChatOptions("free", "low"));
        assertEquals(new ChatOptions("free", "low"), conversations.chatOptions("u1", mine));
        assertEquals(ChatOptions.NONE, conversations.chatOptions("u1", other), "the chat's alone");

        conversations.setChatOptions("u1", mine, new ChatOptions(null, "medium"));
        assertEquals(new ChatOptions(null, "medium"), conversations.chatOptions("u1", mine),
                "back to the default time vs cost, its own effort");

        conversations.setChatOptions("u1", theirs, new ChatOptions("fast", "high"));
        assertEquals(ChatOptions.NONE, conversations.chatOptions("u2", theirs), "another user's chat is not his to set");
        conversations.setChatOptions("u2", theirs, new ChatOptions("cheaper", null));
        assertEquals(ChatOptions.NONE, conversations.chatOptions("u1", theirs), "nor to read");
        assertEquals(new ChatOptions("cheaper", null), conversations.chatOptions("u2", theirs));
        // Mutation: drop "AND user_id = ?" from either statement -> another user's chat is set, or read.
    }

    @Test
    @DisplayName("a value the owner was never offered is no choice: the default holds")
    void onlyWhatIsOffered() {
        assertEquals(ChatOptions.NONE, new ChatOptions("turbo", "max"));
        assertEquals(ChatOptions.NONE, new ChatOptions("", "HIGH"));
        assertEquals(new ChatOptions("cheaper", null), new ChatOptions("cheaper", "extreme"));
    }

    @Test
    @DisplayName("the defaults are the stops the owner set, local only the last whatever prefer-cost was left at; a choice is kept over them, each on its own")
    void theDefaultsAndAChoiceOverThem() {
        var mentor = new OwnClawConfig().getMentor();
        assertEquals(new ChatOptions("cheaper", "high"), ChatOptions.defaultsOf(mentor), "as configured out of the box");
        mentor.setPreferCost(false);
        mentor.setThinkingEffort("low");
        assertEquals(new ChatOptions("fast", "low"), ChatOptions.defaultsOf(mentor));
        mentor.setLocalOnly(true);
        assertEquals(new ChatOptions("free", "low"), ChatOptions.defaultsOf(mentor));

        var defaults = new ChatOptions("cheaper", "high");
        assertEquals(defaults, ChatOptions.NONE.or(defaults));
        assertEquals(new ChatOptions("free", "high"), new ChatOptions("free", null).or(defaults));
        assertEquals(new ChatOptions("cheaper", "low"), new ChatOptions(null, "low").or(defaults));
    }
}
