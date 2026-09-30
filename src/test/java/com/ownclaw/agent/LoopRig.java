package com.ownclaw.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.memory.SqliteAgentMemory;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolRegistry;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.CloudGateway;
import com.ownclaw.llm.LlmMessage;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.llm.LlmRequestConfig;
import com.ownclaw.llm.LlmResponse;
import com.ownclaw.llm.Replies;
import com.ownclaw.llm.ToolCall;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.observability.EventEgressLedger;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.users.CredentialVault;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The real loop, the real thinking engine and the real gateway, over a real database, with a
 * scripted model where the cloud provider stands. What the loop does with a task -- how it ends,
 * what it records, what the next turn is given -- can then be driven end to end.
 */
final class LoopRig {

    /** One call the model was asked to answer. */
    record Call(String purpose, List<LlmMessage> messages, LlmRequestConfig config) {}

    /** How the scripted model answers one call. */
    @FunctionalInterface
    interface Reply {
        LlmResponse answer(LlmRequestConfig config) throws Exception;
    }

    /** A cloud model that answers from two scripts: think calls and code-writing calls. */
    static final class Cloud implements LlmProvider {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final Deque<Reply> think = new ConcurrentLinkedDeque<>();
        final Deque<Reply> codegen = new ConcurrentLinkedDeque<>();
        volatile boolean available = true;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestConfig config) {
            String purpose = config.egress() == null ? null : config.egress().purpose();
            calls.add(new Call(purpose, List.copyOf(messages), config));
            Reply next = ("codegen".equals(purpose) ? codegen : think).poll();
            if (next == null) throw new AssertionError("the script has no reply for a " + purpose + " call");
            try {
                return next.answer(config);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        List<Call> calls(String purpose) {
            return calls.stream().filter(c -> purpose.equals(c.purpose())).toList();
        }

        @Override public boolean isAvailable() { return available; }
        @Override public boolean supportsTools() { return true; }
        @Override public String name() { return "anthropic"; }
        @Override public String model() { return "claude-opus-5"; }
    }

    /** The model calls a tool. */
    static Reply call(String tool, Map<String, Object> args) {
        return c -> Replies.of("", 1_000, 100, 0, 0, "tool_use",
                List.of(new ToolCall("c-" + tool, tool, args)));
    }

    /** The model answers. */
    static Reply respond(String message) {
        return call(AgentAction.RESPOND, Map.of("message", message));
    }

    /** The reply streams in for {@code ms}, an event every 50 ms, then {@code then} arrives. */
    static Reply streaming(long ms, Reply then) {
        return c -> {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(50);
                c.progress().onProgress();
            }
            return then.answer(c);
        };
    }

    /** Nothing arrives for {@code ms}, then {@code then} does. */
    static Reply silent(long ms, Reply then) {
        return c -> {
            Thread.sleep(ms);
            return then.answer(c);
        };
    }

    /** A skill manager with no skills on disk, whose syntax check, files and write are the test's. */
    static final class Skills extends SkillManager {
        volatile Function<String, String> source = name -> null;
        volatile Function<String, String> files = name -> "ERROR: '" + name + "' is not a skill or does not exist.";
        volatile Function<String, String> syntax = code -> null;
        volatile Function<Map<String, Object>, String> create = p -> "Skill '" + p.get("name") + "' created.";

        Skills(SkillCuratorService curator) { super(null, null, null, null, null, curator); }

        @Override public String readSkillCode(String name) { return source.apply(name); }
        @Override public String readSkill(String name) { return files.apply(name); }
        @Override String checkPythonSyntax(String code) { return syntax.apply(code); }
        @Override public String createSkill(Map<String, Object> params) { return create.apply(params); }
    }

    final JdbcTemplate jdbc;
    final OwnClawConfig config;
    final Cloud cloud = new Cloud();
    final Skills skills;
    final TaskCancellationService cancellation = new TaskCancellationService();
    final ConversationService chat;
    final FileStorageService files;
    final EventLogService events;
    final CredentialVault vault;
    final ChatStatusEmitter emitter = new ChatStatusEmitter();
    final DebugSessionService debug = new DebugSessionService();
    final AgentLoop loop;

    LoopRig(Path dir, List<Tool> tools) throws Exception {
        this(dir, tools, 600);
    }

    LoopRig(Path dir, List<Tool> tools, int stallTimeoutSec) throws Exception {
        this(dir, tools, stallTimeoutSec, new StopWithoutLocalModelTest.Down());
    }

    /** @param local the local model: the code writer when the cloud is not available */
    LoopRig(Path dir, List<Tool> tools, int stallTimeoutSec, LlmProvider local) throws Exception {
        this(dir, tools, stallTimeoutSec, local,
                (registry, config, router) -> new ThinkingEngine(registry, config, router));
    }

    /** Makes the thinking engine: the real one, unless a test stands in for it. */
    @FunctionalInterface
    interface Engine {
        ThinkingEngine make(ToolRegistry registry, OwnClawConfig config, LlmRouter router);
    }

    LoopRig(Path dir, List<Tool> tools, int stallTimeoutSec, LlmProvider local, Engine engine) throws Exception {
        Files.createDirectories(dir);
        jdbc = MigratedDatabase.at(dir.resolve("t.db"));
        config = new OwnClawConfig();
        config.getDatabase().setPath(dir.resolve("t.db").toString());
        config.getMentor().setProvider("anthropic");
        config.getTasks().setStallTimeout(stallTimeoutSec);
        Files.createDirectories(dir.resolve("uploads"));
        events = new EventLogService(jdbc);
        files = new FileStorageService(jdbc, config);
        chat = new ConversationService(jdbc);
        var gateway = new CloudGateway(cloud, cloud, config, new EventEgressLedger(events, new ObjectMapper()), null);
        var router = new LlmRouter(local, gateway, config, null);
        var registry = new ToolRegistry(tools);
        vault = new CredentialVault(jdbc);
        vault.init();
        var curator = new SkillCuratorService(jdbc, router, registry,
                new DynamicSkillRegistry(null, null, null, null, null, null, null));
        skills = new Skills(curator);
        loop = new AgentLoop(engine.make(registry, config, router),
                new CriticAgent(registry), registry, emitter, config, router, new SqliteAgentMemory(jdbc),
                curator, skills, debug,
                cancellation, vault, chat, new LongRunningTaskManager(jdbc, emitter, events, config), null,
                new TokenBudgetTracker(jdbc, config, emitter), events, null, null, files);
    }

    /** A chat turn, saved the way the web chat saves one; the session is created on first use. */
    AgentResult turn(String session, String text) {
        String row = chat.saveMessage("u1", session, "user", text);
        AgentResult r = loop.executeFull("u1", text, false, row, List.of());
        chat.saveMessage("u1", session, "assistant", r.response(), List.of(), r.taskId(), r.ownerText());
        return r;
    }

    /** Every status the task emits for u1, in order, from now on. */
    List<ChatStatusEmitter.StatusMessage> statuses() {
        var seen = new CopyOnWriteArrayList<ChatStatusEmitter.StatusMessage>();
        emitter.subscribe("u1", seen, seen::add);
        return seen;
    }

    /** Ticks the stall watchdog every 50 ms until closed, as the scheduler does every 30 s. */
    AutoCloseable watchdog() {
        ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor();
        ticks.scheduleAtFixedRate(loop::cancelStalledTasks, 50, 50, TimeUnit.MILLISECONDS);
        return ticks::shutdownNow;
    }

    /** Every user message the model was sent, in order. */
    static List<String> userParts(Call call) {
        var out = new ArrayList<String>();
        for (LlmMessage m : call.messages()) if (m.role() == LlmMessage.Role.USER) out.add(m.content());
        return out;
    }
}
