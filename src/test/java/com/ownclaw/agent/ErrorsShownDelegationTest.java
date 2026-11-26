package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.call;
import static com.ownclaw.agent.DelegationBehaviourTest.done;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A delegation on a task holding a private file, whose local model calls a skill that fails: the
 * result it records shows the cloud the skill's error, as the direct path does -- never its output.
 */
class ErrorsShownDelegationTest {

    private static final String ERROR = "tesseract is not installed or it's not in your PATH.";

    @Test
    @DisplayName("the delegation's failed call carries its error to the cloud's descriptor, not its output")
    void theDelegationShowsTheError() {
        var ocr = new DelegationBehaviourTest.FakeTool("ocr_image_to_text", false, List.of(),
                p -> ToolResult.failure("ERROR: " + ERROR + "\n[stdout: Obědové menu;155 Kč]", ERROR));
        var llm = new DelegationBehaviourTest.Scripted(call("ocr_image_to_text", Map.of()), done("it failed"));
        var ctx = DelegationBehaviourTest.task();
        ctx.addFile("f1", "Položka;Cena\nObědové menu;155 Kč\n", List.of("uploaded file", "text/csv, 40 bytes"));

        DelegationBehaviourTest.executor(llm, new DelegationBehaviourTest.Usage(), ocr)
                .execute(DelegationBehaviourTest.plan("read the price list"), ctx, DelegationBehaviourTest.UNCOUNTED);

        var failed = ctx.artifacts().stream().filter(a -> a.tool().equals("ocr_image_to_text")).findFirst().orElseThrow();
        assertTrue(failed.isPrivate());
        assertEquals(ERROR, failed.shownError(), String.valueOf(failed.why()));
        assertTrue(failed.describe().contains(ERROR), failed.describe());
        assertFalse(failed.describe().contains("155"), failed.describe());
        // Mutation: pass result.output() as the error in LocalExecutor -> the output is the shown error.
    }
}
