package com.ownclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an agent run is allowed to claim about itself.
 * <p>
 * Every ending except one used to report {@code success = true}. The consequences were not
 * cosmetic: the event log recorded the run at "info", the chat status line said COMPLETED, and
 * the memory layer stored the episode as a worked example — so a task that hit the step cap, or
 * gave up after repeated reasoning failures, was recalled later as an example of how to do the
 * job. Each of those endings now has its own reason and reports failure.
 * <p>
 * The tests below are written reflectively rather than as a list of hand-written cases, because
 * the invariant worth protecting is about the whole set: it should stay true for endings nobody
 * has thought of yet. A future "partial success" factory that reports true, or a new
 * TerminationReason with no way to construct it, fails these without anyone remembering to come
 * back and add a case.
 */
class AgentOutcomeTest {

    /** The static factories on AgentResult that build a finished run. */
    private static Set<Method> factories() {
        Set<Method> found = new java.util.LinkedHashSet<>();
        for (Method m : AgentResult.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())
                    && m.getReturnType() == AgentResult.class) {
                found.add(m);
            }
        }
        assertFalse(found.isEmpty(), "no AgentResult factories found — has the class been renamed?");
        return found;
    }

    private static AgentResult build(Method factory) throws Exception {
        Object[] args = new Object[factory.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> t = factory.getParameterTypes()[i];
            if (t == String.class) args[i] = "text";
            else if (t == AgentTrajectory.class) args[i] = new AgentTrajectory();
            else if (t == long.class) args[i] = 1L;
            else fail("unhandled factory parameter type " + t + " on " + factory.getName());
        }
        return (AgentResult) factory.invoke(null, args);
    }

    @Test
    @DisplayName("completed() is the only ending that reports success")
    void onlyCompletedIsSuccess() throws Exception {
        Set<String> claimingSuccess = new TreeSet<>();
        for (Method f : factories()) {
            if (build(f).success()) claimingSuccess.add(f.getName());
        }
        assertEquals(Set.of("completed"), claimingSuccess,
                "Exactly one ending may report success. Anything else claiming it will be logged "
                        + "at info, shown as COMPLETED, and stored in memory as a worked example.");
    }

    @Test
    @DisplayName("every factory reports the reason its name promises")
    void reasonMatchesFactory() throws Exception {
        for (Method f : factories()) {
            AgentResult r = build(f);
            assertNotNull(r.terminationReason(), f.getName() + " left the reason null");
            String expected = f.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
            // needsInput -> NEEDS_INPUT, failureLimit -> FAILURE_LIMIT, maxSteps -> MAX_STEPS
            assertEquals(expected, r.terminationReason().name(),
                    f.getName() + "() reports " + r.terminationReason()
                            + " — a factory that sets a reason other than its own name is how an "
                            + "ending gets silently misfiled.");
        }
    }

    @Test
    @DisplayName("every termination reason can actually be constructed")
    void noUnreachableReason() throws Exception {
        Set<AgentResult.TerminationReason> reachable = EnumSet.noneOf(AgentResult.TerminationReason.class);
        for (Method f : factories()) reachable.add(build(f).terminationReason());

        Set<AgentResult.TerminationReason> missing = EnumSet.allOf(AgentResult.TerminationReason.class);
        missing.removeAll(reachable);
        // TIMEOUT is retained for stored results from older builds; nothing produces it now.
        missing.remove(AgentResult.TerminationReason.TIMEOUT);
        assertTrue(missing.isEmpty(),
                "No factory produces " + missing + ". A reason nothing can construct is either "
                        + "dead or a missing factory, and both mislead whoever reads the enum.");
    }

    @Test
    @DisplayName("asking the user is neither success nor failure")
    void needsInputIsItsOwnOutcome() {
        AgentResult asked = AgentResult.needsInput("Which account?", new AgentTrajectory(), 5L);
        assertFalse(asked.success(), "a question is not a finished task");
        assertTrue(asked.awaitingUser(), "callers need to tell 'waiting for you' from 'failed'");
        assertEquals("Which account?", asked.response(),
                "the question itself has to survive as the response, or the user never sees it");

        // And nothing else may claim to be waiting on the user.
        for (AgentResult other : new AgentResult[]{
                AgentResult.completed("done", new AgentTrajectory(), 1L),
                AgentResult.cancelled("stopped", new AgentTrajectory(), 1L),
                AgentResult.maxSteps("ran out", new AgentTrajectory(), 1L),
                AgentResult.stalled("stuck", new AgentTrajectory(), 1L),
                AgentResult.error("broke", new AgentTrajectory(), 1L),
                AgentResult.failureLimit("gave up", new AgentTrajectory(), 1L)}) {
            assertFalse(other.awaitingUser(),
                    other.terminationReason() + " must not render as a pending question");
        }
    }
}
