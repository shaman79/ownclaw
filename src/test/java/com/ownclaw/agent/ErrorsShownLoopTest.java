package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolParam;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ownclaw.agent.LoopRig.call;
import static com.ownclaw.agent.LoopRig.respond;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The real loop, a task holding a private file, a skill that fails: the next request to the cloud
 * carries the skill's error -- what it said went wrong -- and none of its output.
 */
class ErrorsShownLoopTest {

    private static final String ERROR = "tesseract is not installed or it's not in your PATH.";
    private static final String DATA = "Obědové menu;155 Kč;Jana Nováková";

    /** A skill that read the file, printed it, and failed. */
    static final Tool OCR = new Tool() {
        public String name() { return "ocr_image_to_text"; }
        public String description() { return "reads the text of an image"; }
        public Map<String, ToolParam> inputSchema() { return Map.of(); }
        public ToolResult execute(Map<String, Object> p, ToolExecutionContext c) {
            return ToolResult.failure("ERROR: " + ERROR + "\n[stdout: " + DATA + "]", ERROR);
        }
    };

    @Test
    @DisplayName("the next request shows the failed skill's error beside its descriptor, never its output")
    void theNextRequestShowsTheError(@TempDir Path tmp) throws Exception {
        // A private file needs the local model: a task holding one does not start without it.
        var local = LocalModeTest.localModel();
        for (int i = 0; i < 6; i++) local.think.add(respond("The skill failed."));
        var rig = new LoopRig(tmp, List.of(OCR), 600, local);
        String file = PastedImageTaskTest.attach(rig, "Položka;Cena\nObědové menu;155 Kč\n");
        rig.cloud.think.add(call("ocr_image_to_text", Map.of()));
        rig.cloud.think.add(respond("The OCR skill needs tesseract."));
        String session = rig.chat.createSession("u1", "Prices");

        rig.turn(session, "read the price list", List.of(file));

        var thinks = rig.cloud.calls("think");
        assertEquals(2, thinks.size());
        String second = thinks.get(1).messages().stream().map(LlmMessage::content).collect(Collectors.joining("\n"));
        assertTrue(second.contains("[its error, shown; its output is not]\n" + ERROR), second);
        assertFalse(second.contains("155"), "nothing of what it printed: " + second);
        assertFalse(second.contains("Jana"), second);
        // Mutations: pass result.output() as the error in AgentLoop -> the data goes; pass null -> no error.
    }
}
