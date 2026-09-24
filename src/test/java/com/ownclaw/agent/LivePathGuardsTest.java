package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two guards that sit on paths no unit test can reach — deep inside the delegation loop and
 * inside the cloud step loop, both of which need a live model to enter.
 * <p>
 * Read from the source, which is crude and says so. It is here because the alternative was
 * nothing, and nothing is how both of these came to be wrong in the first place: one gate went
 * onto two call sites that cannot be reached with an attachment while the reachable one kept
 * the bare check, and the reference guard was written for the delegation and never added to the
 * path the cloud itself calls tools on. Neither mistake is visible in a passing suite.
 */
class LivePathGuardsTest {

    private static String read(String className) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("src/main/java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "src/main/java not found above " + Path.of("").toAbsolutePath());
        Path f = root.resolve("src/main/java").resolve(className.replace('.', '/') + ".java");
        assertTrue(Files.exists(f), f + " does not exist");
        return Files.readString(f);
    }

    @Test
    @DisplayName("every labelFor call that looks at attachments is gated on unattended")
    void theAttachmentGateIsOnEveryCallSite() throws IOException {
        int sites = 0;
        for (String cls : new String[]{"com.ownclaw.agent.AgentLoop",
                                       "com.ownclaw.agent.LocalExecutor"}) {
            String s = read(cls);
            int at = 0;
            while ((at = s.indexOf("Artifact.labelFor(", at)) >= 0) {
                int end = s.indexOf(");", at);
                assertTrue(end > at, cls + ": labelFor call is not terminated");
                String call = s.substring(at, end);
                at = end;
                if (!call.contains("attachmentIds()")) continue;
                sites++;
                assertTrue(call.contains("isUnattended()"),
                        cls + ": this labelFor reads attachmentIds() without isUnattended(). "
                                + "Files arrive on attended chat, so ungated it marks every "
                                + "result of an attended task PRIVATE and the person asking "
                                + "gets a descriptor instead of their answer:\n" + call);
            }
        }
        assertEquals(2, sites,
                "both call sites are expected to weigh attachments; if one was moved or "
                        + "deleted this test has stopped checking what it claims to check");
    }

    @Test
    @DisplayName("executeTool refuses an unresolved reference before the tool runs")
    void unresolvedReferencesAreRefusedOnTheAttendedPath() throws IOException {
        String s = read("com.ownclaw.agent.AgentLoop");
        int start = s.indexOf("private AgentObservation executeTool(");
        assertTrue(start > 0, "executeTool was renamed; this test no longer guards anything");
        int end = s.indexOf("\n    private ", start + 10);
        String body = end > start ? s.substring(start, end) : s.substring(start);

        int substituted = body.indexOf("substituteRefs(");
        int guard = body.indexOf("unresolvedRef(");
        int ran = body.indexOf("tool.execute(");
        assertTrue(substituted > 0, "executeTool no longer substitutes references");
        assertTrue(ran > 0, "executeTool no longer runs the tool");
        assertTrue(guard > substituted && guard < ran,
                "unresolvedRef has to be checked after substitution and BEFORE the tool runs. "
                        + "An unresolvable $9 otherwise reaches the skill as those two literal "
                        + "characters — as a mail body, sent, and recorded as a success");
    }
}
