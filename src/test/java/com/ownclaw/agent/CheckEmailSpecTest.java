package com.ownclaw.agent;

import com.ownclaw.agent.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The deterministic check_email spec asks for every email, whole: a cut written into the spec is baked into the skill. */
class CheckEmailSpecTest {

    @Test
    @DisplayName("no body cut and no default count in the check_email spec")
    void theSpecCutsNothing() {
        var hint = new CapabilityResolver(new ToolRegistry(List.of()), null).resolve("check my email please");
        assertNotNull(hint);
        assertEquals("check_email", hint.suggestedName());
        assertFalse(hint.description().matches("(?s).*\\b2000\\b.*"), hint.description());
        assertTrue(hint.description().contains("whole plain text body"), hint.description());
        assertFalse(hint.parametersJson().contains("default: 20"), hint.parametersJson());
        assertTrue(hint.parametersJson().contains("default: every match"), hint.parametersJson());
    }
}
