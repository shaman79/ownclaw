package com.ownclaw.agent;

import com.ownclaw.sandbox.RealBubblewrap;
import com.ownclaw.sandbox.SkillJail;
import org.junit.jupiter.api.AfterEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SkillLifecycleTest} again, with every syntax check, load check and run inside the real
 * bubblewrap jail, in required mode: accepting, rejecting, restoring and quarantining must behave
 * exactly as they do unjailed. Skipped where bubblewrap cannot run.
 */
class SkillLifecycleJailedTest extends SkillLifecycleTest {

    private SkillJail jail;

    @Override
    SkillJail jail(Path root, Path generated) throws IOException {
        RealBubblewrap.assumeWorks();
        Path db = Files.createDirectories(root.resolve("data")).resolve("ownclaw.db");
        Files.writeString(db, "canary-db");
        jail = RealBubblewrap.requiredJail(new SkillJail.Layout(root, db, generated, null));
        return jail;
    }

    @AfterEach
    void everyLaunchWasJailed() {
        if (jail == null) return; // skipped before the jail existed
        var s = jail.status();
        assertTrue(s.isolatedRuns() > 0, "nothing ran inside the jail");
        assertEquals(0, s.directRuns(), "a launch ran beside the jail");
        assertEquals(0, s.refusedRuns(), "a launch was refused");
    }
}
