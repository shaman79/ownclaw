package com.ownclaw.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ownclaw.agent.DelegationBehaviourTest.FakeTool;
import com.ownclaw.agent.DelegationBehaviourTest.Scripted;
import com.ownclaw.agent.DelegationBehaviourTest.Usage;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.llm.LlmException;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.observability.ChatStatusEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.ownclaw.agent.DelegationBehaviourTest.call;
import static com.ownclaw.agent.DelegationBehaviourTest.done;
import static com.ownclaw.agent.DelegationBehaviourTest.plan;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The log is read back through the ops API, so it must not hold what the cloud may not see:
 * the local model's text on a file task, or an error that quotes it after a private read.
 */
class LocalLogPrivacyTest {

    static final String SECRET = "account 123456789/0100 closing balance 48,213.07 CZK";
    static final List<String> PDF = List.of("uploaded file",
            "application/pdf, 84211 bytes, no text read (not text, over 100 KB, or not UTF-8)");

    private static ListAppender<ILoggingEvent> capture() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(LocalExecutor.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(LocalExecutor.class)).detachAppender(appender);
    }

    private static String logged(ListAppender<ILoggingEvent> appender) {
        var sb = new StringBuilder();
        for (var e : appender.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static AgentContext fileTask() {
        var ctx = new AgentContext("u1", "t1", "summarise this statement");
        ctx.addFile("f1", "", PDF);
        return ctx;
    }

    @Test
    @DisplayName("a prose reply from the local model is logged by its length, not its text")
    void proseIsNotLogged() {
        var appender = capture();
        try {
            var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
            var llm = new Scripted(call("read_statement", Map.of()), "Your " + SECRET + ".", done("Your " + SECRET));
            DelegationBehaviourTest.executor(llm, new Usage(), read).execute(plan("summarise the statement"), fileTask());
            String log = logged(appender);
            assertTrue(log.contains("no JSON found"), "the prose reply was seen: " + log);
            assertFalse(log.contains("48,213.07"), log);
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("a reply that is not valid JSON is logged by type and length: a parser quotes the token")
    void unparseableJsonIsNotQuoted() {
        var appender = capture();
        try {
            var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
            var llm = new Scripted(call("read_statement", Map.of()), "{\"tool\": CZ6508000000192000145399}", done("done"));
            DelegationBehaviourTest.executor(llm, new Usage(), read).execute(plan("summarise the statement"), fileTask());
            String log = logged(appender);
            assertTrue(log.contains("failed to parse local LLM JSON"), "the bad reply was seen: " + log);
            assertFalse(log.contains("CZ6508000000192000145399"), log);
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("after a private read, a failed local call's text reaches neither the cloud nor the log")
    void aFailureAfterAPrivateReadIsKeptOut() {
        var appender = capture();
        try {
            var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
            LlmProvider llm = new LlmProvider() {
                int calls;
                public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                    if (calls++ == 0) return new LlmResponse(call("read_statement", Map.of()), 1, 1);
                    throw new LlmException("ollama", "HTTP 500: error parsing tool call: raw='" + SECRET + "'", 500, null);
                }
                public boolean isAvailable() { return true; }
                public String name() { return "failing"; }
            };
            var executor = new LocalExecutor(new LlmRouter(llm, null, null, null),
                    new ToolRegistry(List.of(read)), new ChatStatusEmitter(), new Usage());
            var outcome = executor.execute(plan("summarise the statement"), fileTask());
            assertFalse(outcome.text().contains("48,213.07"), outcome.text());
            assertTrue(outcome.text().contains("Local LLM call failed"), outcome.text());
            assertFalse(logged(appender).contains("48,213.07"), logged(appender));
        } finally {
            release(appender);
        }
    }
}
