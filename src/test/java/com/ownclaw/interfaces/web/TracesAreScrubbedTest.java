package com.ownclaw.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.agent.AgentAction;
import com.ownclaw.agent.AgentLoop;
import com.ownclaw.agent.AgentObservation;
import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.AgentTrajectory;
import com.ownclaw.agent.tools.DynamicSkill;
import com.ownclaw.agent.tools.DynamicSkillRegistry;
import com.ownclaw.agent.tools.ToolExecutionContext;
import com.ownclaw.agent.tools.ToolResult;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.MigratedDatabase;
import com.ownclaw.core.TaskQueue;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.observability.OpsService;
import com.ownclaw.privacy.VaultSecrets;
import com.ownclaw.users.AuthService;
import com.ownclaw.users.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The traces a run is read back through -- the ops API's, the debug API's -- and a test run from
 * the Skills page: each showed every tool's output and parameters as they came, a password a
 * skill had printed among them.
 */
class TracesAreScrubbedTest {

    static final String SECRET = "Zq7-first-line\nZq7 second line";
    static final String MARK = "«vault:SSH_PRIVATE_KEY»";

    @AfterEach
    void forget() {
        VaultSecrets.clear();
    }

    private static void assertClean(Object where) {
        String text = String.valueOf(where);
        for (String line : SECRET.split("\n")) assertFalse(text.contains(line), "holds the secret: " + text);
    }

    /** A run of one step whose tool printed the secret, called with it, and said so on the page. */
    static AgentResult leakyRun(ChatStatusEmitter emitter, String userId) {
        if (emitter != null) emitter.emit(userId, StatusMessage.Type.DEBUG, "TOOL RESULT: " + SECRET);
        var trajectory = new AgentTrajectory();
        trajectory.record(new AgentAction("shell", Map.of("command", "ssh -i '" + SECRET + "' host"),
                        "use the key " + SECRET),
                new AgentObservation("shell", true, "the key is " + SECRET, Map.of(), 3));
        return AgentResult.completed("done", trajectory, 1).withTaskId("00000001");
    }

    @Test
    @DisplayName("the ops API's run shows each step's output, parameters and reasoning without a secret value")
    void opsRun(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name) VALUES ('u1', 'Owner')");
        VaultSecrets.set("u1", Map.of("SSH_PRIVATE_KEY", SECRET));
        var loop = new AgentLoop(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public AgentResult executeFull(String userId, String message, boolean unattended,
                                           String currentMessageId, List<String> attachmentIds,
                                           com.ownclaw.agent.TaskChat.Channel channel,
                                           com.ownclaw.core.Inbox inbox,
                                           com.ownclaw.conversation.ChatOptions chosen) {
                return leakyRun(null, userId);
            }
        };
        var auth = new AuthService(jdbc, new UserRepository(jdbc), new OwnClawConfig());
        var service = new OpsService(new OwnClawConfig(), jdbc, null, null,
                new TaskQueue(null, null, null, new OwnClawConfig(), null), auth, null, new ObjectMapper(), null);
        var queue = new TaskQueue(loop, new com.ownclaw.observability.EventLogService(jdbc),
                new ChatStatusEmitter(), new OwnClawConfig(), new com.ownclaw.core.TaskCancellationService());
        var ops = new OpsController(service, loop, auth, null, null, null, null, new ConversationService(jdbc),
                queue, System::currentTimeMillis);

        var refused = ops.runAgent(Map.of("message", "check (/cred set K hunter2-Xq9)", "userId", "u1"));
        assertEquals(400, refused.getStatusCode().value(), "a secret command is no task: " + refused.getBody());

        Map<String, Object> run = OpsControllerTest.body(ops.runAgent(Map.of("message", "check", "userId", "u1")));
        assertNotNull(run.get("steps"), String.valueOf(run));
        assertClean(run);
        @SuppressWarnings("unchecked")
        var step = ((List<Map<String, Object>>) run.get("steps")).getFirst();
        assertEquals("the key is " + MARK, step.get("output"));
        assertEquals(("the key is " + MARK).length(), step.get("outputLength"), "the length of what is shown");
    }

    @Test
    @DisplayName("the debug API's trace holds no secret value: not in the steps, not in the status lines it caught")
    void debugTrace(@TempDir Path tmp) throws Exception {
        var jdbc = MigratedDatabase.at(tmp.resolve("t.db"));
        jdbc.update("INSERT INTO users (id, display_name, password_hash) VALUES ('owner', 'owner', 'x')");
        VaultSecrets.set("owner", Map.of("SSH_PRIVATE_KEY", SECRET));
        var emitter = new ChatStatusEmitter();
        var loop = new AgentLoop(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public AgentResult executeFull(String userId, String message) {
                return leakyRun(emitter, userId);
            }
        };
        var debug = new DebugController(loop, null, null, new DebugSessionService(), emitter,
                jdbc, new AuthService(jdbc, null, new OwnClawConfig()), System::currentTimeMillis);

        assertEquals(400, debug.submitPrompt(Map.of("message", "/cred set K hunter2-Xq9"), "owner")
                .getStatusCode().value(), "a secret command is no prompt");
        Object trace = debug.submitPrompt(Map.of("message", "check"), "owner").getBody();
        assertTrue(String.valueOf(trace).contains("TOOL RESULT: " + MARK), "the status line was caught: " + trace);
        assertClean(trace);
        assertClean(debug.getOutput("00000001", "owner").getBody());
    }

    @Test
    @DisplayName("a test run from the Skills page shows what the skill printed, less the owner's secret values")
    void skillTestRun() {
        VaultSecrets.set("owner", Map.of("SSH_PRIVATE_KEY", SECRET));
        var skill = new DynamicSkill("ssh_uptime", "runs uptime", Map.of(), Path.of("."), false, false, 30,
                null, null, List.of(), null, List.of(), null, null, null) {
            @Override public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
                return ToolResult.success("logged in with " + SECRET);
            }
        };
        var registry = new DynamicSkillRegistry(null, null, null, null, null, null, null) {
            @Override public Optional<DynamicSkill> getDynamic(String name) { return Optional.of(skill); }
        };
        var auth = new AuthService(null, null, new OwnClawConfig()) {
            @Override public boolean isOwner(String userId) { return true; }
        };
        var controller = new SkillController(null, registry, null, auth);

        var body = controller.runSkill("ssh_uptime", Map.of(), "owner").getBody();
        assertClean(body);
        assertEquals("logged in with " + MARK, body.get("output"));

        var failing = new DynamicSkill("ssh_uptime", "runs uptime", Map.of(), Path.of("."), false, false, 30,
                null, null, List.of(), null, List.of(), null, null, null) {
            @Override public ToolResult execute(Map<String, Object> params, ToolExecutionContext context) {
                throw new IllegalStateException("could not log in with " + SECRET);
            }
        };
        var failingRegistry = new DynamicSkillRegistry(null, null, null, null, null, null, null) {
            @Override public Optional<DynamicSkill> getDynamic(String name) { return Optional.of(failing); }
        };
        var thrown = new SkillController(null, failingRegistry, null, auth).runSkill("ssh_uptime", Map.of(), "owner").getBody();
        assertClean(thrown);
        assertEquals("could not log in with " + MARK, thrown.get("output"));
    }
}
