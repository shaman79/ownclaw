package com.ownclaw.interfaces.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A task's page says what self-repair did about it ({@code TaskTraceService}'s selfRepair rows): a
 * repair it started, with a link to the repair's own page, and on that page what it repairs and
 * how it ended. Read from the page's source, whitespace collapsed, as the other page tests read it.
 */
class SelfRepairPageTest {

    static String page;

    @BeforeAll
    static void read() throws Exception {
        StillRunningPageTest.read();
        page = StillRunningPageTest.page;
    }

    @Test
    @DisplayName("the task page draws its self-repair rows, text only, each linking the other task")
    void drawn() {
        int detail = page.indexOf("function renderTaskDetail(");
        int drawn = page.indexOf("var repairs = buildTaskSelfRepair(t.selfRepair || []); if (repairs) view.appendChild(repairs);");
        assertTrue(detail >= 0 && drawn > detail && drawn < page.indexOf("function buildTaskSelfRepair("),
                "renderTaskDetail draws the rows");
        String repairs = StillRunningPageTest.function("buildTaskSelfRepair");
        assertTrue(repairs.contains("'Started a repair of ' + r.skill"), repairs);
        assertTrue(repairs.contains("r.state === 'repairing' ? 'This task repairs ' + r.skill"), repairs);
        assertTrue(repairs.contains("openTaskDetail(other, 'tasks')"), repairs);
        assertTrue(repairs.contains("if (isTaskId(other))"), "only a task id is linked: " + repairs);
        assertFalse(repairs.contains("innerHTML"), "the model's words are text, never markup: " + repairs);
    }
}
