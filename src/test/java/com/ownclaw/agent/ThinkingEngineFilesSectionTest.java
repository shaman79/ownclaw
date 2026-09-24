package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the cloud is told about the files sent with a message: that they exist, by handle, type
 * and size, and what that means for the task -- never their names or their contents.
 */
class ThinkingEngineFilesSectionTest {

    @Test
    @DisplayName("the files section names each file by handle, type and size, and never by name")
    void filesSectionNamesNoFile(@TempDir Path tmp) throws Exception {
        var h = AttachmentRegistrationTest.Harness.in(tmp);
        String pdf = h.upload("u1", "vypis_123456789.pdf", "application/pdf", "%PDF-1.7 binary");
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        AgentLoop.registerAttachments(ctx, List.of(pdf), h.files(), h.events());

        String section = ThinkingEngine.filesSection(ctx);
        assertTrue(section.contains("- {{1}}: application/pdf, 15 bytes, not text or too large"), section);
        assertTrue(section.contains("_attached_files"), "where a skill finds the file");
        assertTrue(section.contains("delegate"), "and who can read what it returns");
        assertFalse(section.contains("vypis") || section.contains("123456789"), section);

        // And it is what the model is actually sent, on both renderers.
        var registry = new ToolRegistry(List.of());
        var engine = new ThinkingEngine(registry, new ToolSelector(registry), new OwnClawConfig(), null);
        for (String provider : List.of("anthropic", "openai")) {
            String all = engine.buildMessages(ctx, provider, new ThinkingEngine.StepMode(true, false))
                    .stream().map(LlmMessage::content).reduce("", String::concat);
            assertTrue(all.contains("## Files sent with this message\n- {{1}}"), provider + " omits the files");
            assertFalse(all.contains("vypis") || all.contains("123456789"), provider + " names the file");
        }
    }

    @Test
    @DisplayName("a message with no file has no files section")
    void noFileNoSection() {
        var ctx = new AgentContext("u1", "t1", "what's the weather");
        assertEquals("", ThinkingEngine.filesSection(ctx));
    }
}
