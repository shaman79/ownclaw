package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.AssistantPartsTest.tool;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Which rule of the duplicate-skill gate answers a proposed name, and which existing skill it
 * names: the narrowing before the generalising, and the nearest skill whatever order the registry
 * holds them in.
 */
class CriticSkillCreateOrderTest {

    private static CriticAgent.Verdict verdict(String proposed, String... library) {
        List<Tool> tools = new ArrayList<>();
        for (String name : library) tools.add(tool(name, List.of(), p -> "ok"));
        var critic = new CriticAgent(new ToolRegistry(tools));
        return critic.evaluate(new AgentAction("skill_create", Map.of("name", proposed, "description", "x"), ""),
                new AgentContext("u1", "t1", "make it"));
    }

    @Test
    @DisplayName("a name that narrows one skill is blocked although it is also the general form of another")
    void narrowingIsAskedBeforeGeneralising() {
        var v = verdict("web_search_bikes", "web_search", "web_search_bikes_cz");
        assertFalse(v.allowed(), "it narrows web_search");
        assertEquals("web_search", v.instead());
        assertTrue(v.blockReason().contains("narrower version of the existing skill 'web_search'"), v.blockReason());
        // Mutation: the generalises pass first, as it was -> allowed, with a warning nobody reads.
    }

    @Test
    @DisplayName("the general form of an existing skill still passes: the narrow one must not block its own replacement")
    void aGeneralisationPasses() {
        var v = verdict("web_search", "web_search_bikes", "imap_list_mailboxes");
        assertTrue(v.allowed());
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("generalises web_search_bikes")), v.warnings().toString());
    }

    @Test
    @DisplayName("of several skills a name narrows, the block names the longest: the nearest one, whatever order they are held in")
    void theNearestNarrowedSkillIsNamed() {
        for (String[] library : List.of(
                new String[] {"imap", "imap_move", "imap_move_to_bin_by_sender"},
                new String[] {"imap_move_to_bin_by_sender", "imap_move", "imap"})) {
            var v = verdict("imap_move_to_bin_by_sender_yahoo", library);
            assertFalse(v.allowed());
            assertEquals("imap_move_to_bin_by_sender", v.instead(), String.join(",", library));
        }
        // Mutation: the first name found -> 'imap' or 'imap_move', by the order of a hash map.
    }

    @Test
    @DisplayName("of several near-duplicates by their words, the block names the one that shares most of them")
    void theNearestDuplicateIsNamed() {
        // 5 of 7 words with the first (0.71); every word with the second, in another order (1.0).
        var v = verdict("mail_move_to_bin_by_sender", "imap_move_to_bin_by_sender", "move_mail_to_bin_by_sender");
        assertFalse(v.allowed());
        assertEquals("move_mail_to_bin_by_sender", v.instead());
        assertTrue(v.blockReason().contains("looks like a duplicate of the existing skill 'move_mail_to_bin_by_sender'"));
        // Mutation: the first name at or over the threshold -> the imap skill, which shares less.
    }

    @Test
    @DisplayName("a name that shares too little with every skill is created: host families and capabilities that share a prefix word")
    void differentCapabilitiesPass() {
        assertTrue(verdict("router_ssh_run", "fileserver_ssh_run", "imap_list_mailboxes").allowed());
        assertTrue(verdict("imap_unread_summarizer", "imap_list_mailboxes").allowed());
    }
}
