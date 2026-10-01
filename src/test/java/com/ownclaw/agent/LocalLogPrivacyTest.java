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
import com.ownclaw.llm.MalformedToolCall;
import com.ownclaw.llm.OutputTruncated;
import com.ownclaw.llm.Replies;
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
            "application/pdf, 84211 bytes, no text read (not text, or not UTF-8)");

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
            assertTrue(log.contains("no tool call in the local LLM's text"), "the prose reply was seen: " + log);
            assertFalse(log.contains("48,213.07"), log);
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("a reply that is not valid JSON is logged by its length: a parser quotes the token")
    void unparseableJsonIsNotQuoted() {
        var appender = capture();
        try {
            var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
            var llm = new Scripted(call("read_statement", Map.of()), "{\"tool\": CZ6508000000192000145399}", done("done"));
            DelegationBehaviourTest.executor(llm, new Usage(), read).execute(plan("summarise the statement"), fileTask());
            String log = logged(appender);
            assertTrue(log.contains("no tool call in the local LLM's text"), "the bad reply was seen: " + log);
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
                    if (calls++ == 0) return Replies.of(call("read_statement", Map.of()), 1, 1);
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

    @Test
    @DisplayName("after a private read, a full context window is still said plainly: the code wrote that message")
    void aFullWindowIsSaidPlainly() {
        var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
        LlmProvider llm = new LlmProvider() {
            int calls;
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                if (calls++ == 0) return Replies.of(call("read_statement", Map.of()), 1, 1);
                throw new OutputTruncated("ollama", OutputTruncated.Limit.CONTEXT_WINDOW, 262_144,
                        Replies.of("", 250_000, 12_144, 0, 0, "length"));
            }
            public boolean isAvailable() { return true; }
            public String name() { return "full"; }
        };
        var ctx = fileTask();
        var outcome = new LocalExecutor(new LlmRouter(llm, null, null, null),
                new ToolRegistry(List.of(read)), new ChatStatusEmitter(), new Usage())
                .execute(plan("summarise the statement"), ctx);

        assertTrue(outcome.text().contains("Local LLM call failed: [ollama] the conversation is longer "
                + "than the model's 262,144-token context window"), outcome.text());
        assertFalse(outcome.ok(), "a failed delegation: the cloud takes the work back");
        assertEquals(2 + 262_144, ctx.localTokens(), "the cut-off reply was generated, and is counted");
    }

    @Test
    @DisplayName("a tool call that cannot be run is logged by its length: its message quotes the model's arguments")
    void aMalformedCallIsNotLogged() {
        var ping = new FakeTool("ping", false, List.of(), p -> ToolResult.success("pong"));
        LlmProvider llm = new LlmProvider() {
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                throw new MalformedToolCall("ollama", new LlmResponse("", List.of(),
                        "the model's arguments for tool 'ping' are not a JSON object (Unexpected end-of-input):\n"
                                + "{\"host\": \"nas.example.org", "stop", null, null, null, null, List.of()));
            }
            public boolean isAvailable() { return true; }
            public String name() { return "malformed"; }
        };
        var appender = capture();
        try {
            var outcome = new LocalExecutor(new LlmRouter(llm, null, null, null),
                    new ToolRegistry(List.of(ping)), new ChatStatusEmitter(), new Usage())
                    .execute(plan("ping the NAS"), DelegationBehaviourTest.task());
            assertTrue(outcome.text().contains("nas.example.org"), "the cloud is told what went wrong: " + outcome.text());
            String log = logged(appender);
            assertTrue(log.contains("a tool call that cannot be run ("), log);
            assertFalse(log.contains("nas.example.org"), "the model's arguments are not logged: " + log);
        } finally {
            release(appender);
        }
    }

    @Test
    @DisplayName("a tool call that cannot be run is counted, and after a private read its text stays out")
    void aMalformedCallIsCountedAndKeptOut() {
        var read = new FakeTool("read_statement", false, List.of(), p -> ToolResult.success("text: " + SECRET));
        LlmProvider llm = new LlmProvider() {
            int calls;
            public LlmResponse chat(List<LlmMessage> m, LlmRequestConfig c) {
                if (calls++ == 0) return Replies.of(call("read_statement", Map.of()), 1, 1);
                // The model's own arguments, quoting what it read: the message is its text.
                throw new MalformedToolCall("ollama", new LlmResponse("", List.of(),
                        "tool call 'send' has arguments that are not a JSON object: " + SECRET,
                        "stop", null, null, null, null,
                        List.of(new LlmResponse.Usage(null, 900, 40, 0, 0))));
            }
            public boolean isAvailable() { return true; }
            public String name() { return "malformed"; }
        };
        var appender = capture();
        try {
            var ctx = fileTask();
            var outcome = new LocalExecutor(new LlmRouter(llm, null, null, null),
                    new ToolRegistry(List.of(read)), new ChatStatusEmitter(), new Usage())
                    .execute(plan("summarise the statement"), ctx);

            assertFalse(outcome.text().contains(SECRET), outcome.text());
            assertTrue(outcome.text().contains("MalformedToolCall (its text is kept out"), outcome.text());
            assertFalse(logged(appender).contains(SECRET), "nor in the log");
            assertEquals(2 + 940, ctx.localTokens(), "the reply was generated, and is counted");
        } finally {
            release(appender);
        }
    }
}
