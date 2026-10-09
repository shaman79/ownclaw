package com.ownclaw.agent;

import com.ownclaw.agent.memory.AgentMemory;
import com.ownclaw.agent.tools.*;
import com.ownclaw.config.OwnClawConfig;
import com.ownclaw.conversation.ChatOptions;
import com.ownclaw.conversation.ConversationService;
import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.core.LongRunningTaskManager;
import com.ownclaw.core.ScheduledTaskService;
import com.ownclaw.core.TaskCancellationService;
import com.ownclaw.core.TokenBudgetTracker;
import com.ownclaw.llm.ModelPricing;
import com.ownclaw.llm.LlmProvider;
import com.ownclaw.observability.ChatStatusEmitter;
import com.ownclaw.observability.ChatStatusEmitter.StatusMessage;
import com.ownclaw.observability.DebugSessionService;
import com.ownclaw.observability.EventLogService;
import com.ownclaw.observability.TaskTraceService;
import com.ownclaw.privacy.Redactor;
import com.ownclaw.sandbox.SandboxManager;
import com.ownclaw.users.CredentialVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ownclaw.llm.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * The AgentLoop is the central execution engine.
 *
 * It implements the reactive Think → Critique → Act → Observe loop:
 *   1. ThinkingEngine decides the next action (LLM call)
 *   2. CriticAgent validates the action (fast, rule-based)
 *   3. Tool is executed (sandbox, HTTP, etc.)
 *   4. Observation is recorded in the trajectory
 *   5. Repeat until the agent answers or asks, is stopped, or steps in a row produce nothing
 *      to run -- however many steps the work takes
 *
 * The loop is the central execution engine that replaced the old plan-first approach.
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ThinkingEngine thinkingEngine;
    private final CriticAgent criticAgent;
    private final ToolRegistry toolRegistry;
    private final ChatStatusEmitter statusEmitter;
    private final OwnClawConfig config;
    private final LlmRouter llmRouter;
    private final AgentMemory memory;
    private final SkillCuratorService curatorService;
    private final SkillManager skillManager;
    private final DebugSessionService debugService;
    private final TaskCancellationService cancellationService;
    private final CredentialVault credentialVault;
    private final ConversationService conversationService;
    private final LongRunningTaskManager longRunningTaskManager;
    private final CapabilityResolver capabilityResolver;
    private final TokenBudgetTracker budgetTracker;
    /** For serialising step details into events.details. Jackson's mapper is thread-safe once built. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final EventLogService eventLog;
    /** A task's rows in events, parsed as the owner's task page parses them: its record. */
    private final TaskTraceService taskTraces;
    private final ScheduledTaskService scheduledTaskService;
    private final LocalExecutor localExecutor;
    private final FileStorageService fileStorage;
    /** The gateway's filter, asked what it takes out of each step's result (TaskChat#filtered). */
    private final Redactor redactor;
    /** The local model as every task shares it: what a task waits on goes first. */
    private final LocalLane lane = new LocalLane();

    public AgentLoop(
            ThinkingEngine thinkingEngine,
            CriticAgent criticAgent,
            ToolRegistry toolRegistry,
            ChatStatusEmitter statusEmitter,
            OwnClawConfig config,
            LlmRouter llmRouter,
            AgentMemory memory,
            SkillCuratorService curatorService,
            SkillManager skillManager,
            DebugSessionService debugService,
            TaskCancellationService cancellationService,
            CredentialVault credentialVault,
            ConversationService conversationService,
            LongRunningTaskManager longRunningTaskManager,
            CapabilityResolver capabilityResolver,
            TokenBudgetTracker budgetTracker,
            EventLogService eventLog,
            @Lazy ScheduledTaskService scheduledTaskService,
            LocalExecutor localExecutor,
            FileStorageService fileStorage,
            Redactor redactor
    ) {
        this.thinkingEngine = thinkingEngine;
        this.criticAgent = criticAgent;
        this.toolRegistry = toolRegistry;
        this.statusEmitter = statusEmitter;
        this.config = config;
        this.llmRouter = llmRouter;
        this.memory = memory;
        this.curatorService = curatorService;
        this.skillManager = skillManager;
        this.debugService = debugService;
        this.cancellationService = cancellationService;
        this.credentialVault = credentialVault;
        this.conversationService = conversationService;
        this.longRunningTaskManager = longRunningTaskManager;
        this.capabilityResolver = capabilityResolver;
        this.budgetTracker = budgetTracker;
        this.eventLog = eventLog;
        this.taskTraces = new TaskTraceService(eventLog);
        this.scheduledTaskService = scheduledTaskService;
        this.localExecutor = localExecutor;
        this.fileStorage = fileStorage;
        this.redactor = redactor;
        // A Stop ends the model call a stopped task is waiting on at once: see interruptStopped.
        if (cancellationService != null) cancellationService.onRequest(this::interruptStopped);
    }

    /**
     * Execute the agent loop and return the full AgentResult (with trajectory).
     * Used by the debug API for full execution trace visibility.
     *
     * @param userId  the user who submitted the task
     * @param message the user's message
     * @return the full AgentResult including trajectory, steps, and timing
     */
    public AgentResult executeFull(String userId, String message) {
        return executeFull(userId, message, false);
    }

    public AgentResult executeFull(String userId, String message, boolean unattended) {
        return executeFull(userId, message, unattended, null, List.of(), null);
    }

    /**
     * A task on the owner's defaults ({@link TaskOptions}).
     *
     * @param currentMessageId the chat row this task answers, or null for a scheduled or
     *                         background run: its chat is the one that row was saved in, and a
     *                         run without one has no chat
     * @param attachmentIds    the files sent with this turn; each one this user owns is
     *                         registered PRIVATE, and every result of the task is PRIVATE with it
     * @param channel          where that row came from, which decides where the task's progress
     *                         messages are shown ({@link TaskChat}); null when it has no chat
     */
    public AgentResult executeFull(String userId, String message, boolean unattended,
                                   String currentMessageId, List<String> attachmentIds,
                                   TaskChat.Channel channel) {
        return executeFull(userId, message, unattended, currentMessageId, attachmentIds, channel, null,
                ChatOptions.NONE);
    }

    /**
     * @param inbox  what the owner sends in the task's chat while it runs, read before each step
     *               ({@link #readMessages}); null for a task nobody can write to
     * @param chosen what its message was sent with, or its chat has chosen, of the owner's defaults
     *               ({@link com.ownclaw.core.UserMessage#options}): the task runs on these, and on
     *               the defaults where they choose nothing
     */
    public AgentResult executeFull(String userId, String message, boolean unattended,
                                   String currentMessageId, List<String> attachmentIds,
                                   TaskChat.Channel channel, com.ownclaw.core.Inbox inbox,
                                   ChatOptions chosen) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        java.util.function.Supplier<AgentResult> task = () -> execute(taskId, userId, message, unattended,
                currentMessageId, attachmentIds, channel, inbox, chosen);
        // Unattended work is waited on in no chat on screen: every status it emits, its ending
        // too, is marked so (ChatStatusEmitter#BACKGROUND), and a page shows it apart.
        return unattended ? statusEmitter.inBackground(taskId, task) : task.get();
    }

    /** {@link #executeFull}, once the task has its id. */
    private AgentResult execute(String taskId, String userId, String message, boolean unattended,
                                String currentMessageId, List<String> attachmentIds,
                                TaskChat.Channel channel, com.ownclaw.core.Inbox inbox,
                                ChatOptions chosen) {
        AgentContext context = new AgentContext(userId, taskId, message);
        context.setUnattended(unattended);
        // Once, here: a default changed while the task runs is the next task's.
        context.setOptions(TaskOptions.of(chosen, config.getMentor()));
        context.setInbox(inbox);
        context.setPersonalSources(config.getPrivacy().getPersonalSources());

        // The chat this task came from, whole, with the record of each finished task in it.
        loadConversationContext(context, userId, currentMessageId, conversationService, fileStorage,
                id -> TaskRecord.forLaterTask(id, traceOf(userId, id)));
        // And where it says what it is doing: that same chat. Unattended work has none -- nobody
        // waits for it, and its report is delivered when it is done.
        if (!unattended && currentMessageId != null && channel != null) {
            try {
                String session = conversationService.sessionOf(userId, currentMessageId);
                if (session != null) {
                    context.setChat(new TaskChat(context, session, channel, conversationService,
                            statusEmitter, llmRouter.local(), lane, accountOf(context)));
                }
            } catch (RuntimeException e) {
                log.warn("Task {}: its chat could not be found, so it posts no progress: {}",
                        taskId, e.getMessage());
            }
        }
        registerAttachments(context, attachmentIds, fileStorage, eventLog);
        AgentResult stopped = stopWithoutLocalModel(context, () -> {
            LlmProvider local = llmRouter.local();
            return local != null && local.isAvailable();
        });
        if (stopped != null) {
            // Before any cloud call and with no episode: nothing was done, so there is nothing
            // to remember, and the owner is told why in the ending itself.
            return end(context, stopped, false);
        }

        // Past episodes are not put into the prompt: the agent asks for them when it needs them
        // (memory_manage action=recall). The facts the owner asked it to keep always go in -- or,
        // when they cannot be read, that they could not: run without them and not told, a task
        // acts as if the owner had asked for nothing.
        try {
            List<AgentMemory.MemoryEntry> facts = memory.getFacts(userId);
            if (!facts.isEmpty()) {
                String factContext = facts.stream()
                        .map(AgentMemory.MemoryEntry::content)
                        .collect(Collectors.joining("\n"));
                context.setUserPreferences(factContext);
            }
        } catch (Exception e) {
            log.warn("Could not read the facts of user {}: {}", userId, e.getMessage());
            context.setUserPreferences("(The facts the user asked you to keep could not be read ("
                    + e.getMessage() + "), so none are here. Do not take that to mean there are none.)");
        }

        // Load credential keys so the LLM knows what's in the vault without calling credential_manage list
        try {
            List<String> keys = credentialVault.listCredentialKeys(userId);
            context.setCredentialKeys(keys);
            // The secret ones, decrypted once. The gateway removes them from every cloud call;
            // decrypting per call would run PBKDF2 on every step. Which they are is the filter's
            // own rule for a secret's name (Redactor.isSecretName): WIFI_PSK, WG_PRIVATE_KEY,
            // ROUTER_PIN, SMTP_PASS. Keys that are not secrets -- SMTP_HOST, SMTP_USER -- are not
            // decrypted and not removed: a prompt can still say which server the mail goes
            // through, and an address in one is an identifier, which the filter writes as a
            // placeholder.
            List<String> secretKeys = keys.stream().filter(Redactor::isSecretName).toList();
            if (!secretKeys.isEmpty()) {
                context.setSecretValues(credentialVault.getCredentials(userId, secretKeys));
            }
        } catch (Exception e) {
            log.debug("Failed to load credential keys for user {}: {}", userId, e.getMessage());
        }

        // The skills every task's model is offered from the start, the cloud's and the local one's.
        try {
            context.setUsualTools(curatorService.mostUsedTools(userId).stream()
                    .filter(name -> toolRegistry.find(name).isPresent()).limit(USUAL_TOOLS).toList());
        } catch (Exception e) {
            log.warn("Could not read which skills user {} uses most: {}", userId, e.getMessage());
        }

        // A skill's own source is not a disclosure of what that skill returned: a Python
        // traceback quotes the line that threw, so without this the failure of a skill whose
        // result is private -- a mail reader's -- made its own repair prompt unsendable. Outside
        // the vault's try, because it has nothing to do with credentials and a vault error must
        // not silently leave it unwired.
        context.setSkillSource(skillManager::readSkillCode);

        // Deterministic capability gap detection — if the task requires a known
        // capability (network scanning, media processing, etc.) and no existing
        // skill covers it, inject a specific hint so the LLM doesn't need to
        // figure out that it should use skill_create + system_packages.
        try {
            CapabilityResolver.CapabilityHint hint = capabilityResolver.resolve(message);
            if (hint != null) {
                context.setCapabilityHint(hint);
                log.info("Capability gap detected for task {}: {} → suggesting skill '{}'",
                        taskId, hint.category(), hint.suggestedName());
            }
        } catch (Exception e) {
            log.debug("Capability resolution failed (non-fatal): {}", e.getMessage());
        }

        return run(context);
    }

    /**
     * Run a task whose context is ready, and end it: a stop and an exception each become its
     * ending here, with the task's record and its episode. {@link #executeFull} prepares the
     * context and calls this.
     */
    AgentResult run(AgentContext context) {
        String userId = context.userId();
        String taskId = context.taskId();
        // Clear any stale cancel flag from a previous task
        cancellationService.clear(userId, taskId);

        // Point the context at the authoritative cancel source. Without this, every
        // context.isCancelled() check inside a step — LocalExecutor's per-step poll, the
        // supplier handed to every tool, the progress hook of every model call — misses a Stop,
        // which could then only take effect between steps. A step here can be a 60-133 s local
        // call.
        context.setExternalCancel(
                () -> cancellationService.isCancelled(userId, taskId, context.startTimeMs()));
        // A cloud call that ends without a reply -- stopped part-way, or cut by a timeout, a
        // dropped connection or an error event -- was billed for what it had read, and no reply
        // carries those tokens: its hook is told of them, and they are counted here. Only a cloud
        // provider reports them (LlmProgress#billed).
        context.setBilledWithoutReply(usage -> account(context, false, llmRouter.cloud(),
                LlmResponse.billedFor(List.of(usage))));
        // Each model call of the task is shown in its chat while it runs (showLive), and a page
        // that connects meanwhile is told at once what the task is doing.
        context.setCallWatch(call -> watch(context, call));

        AgentResult result;
        inFlight.put(taskId, context);
        statusEmitter.running(userId, taskId, () -> liveStatus(context));
        try {
            result = runLoop(context);
        } catch (TaskCancellationService.TaskCancelledException stop) {
            // A model call made for the task heard the stop on its progress hook and ended.
            result = stopped(context);
        } catch (RuntimeException e) {
            // Here, with the context, not in the queue without it: escaping, it took the task's
            // record with it -- the owner read "Internal error: ..." with no step listed, and no
            // task_completed row or episode was written.
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            result = AgentResult.error(internalError(e, context), context.trajectory(), context.elapsedMs());
        } finally {
            inFlight.remove(taskId);
            statusEmitter.ended(taskId);
            context.chat().close();
        }
        return end(context, result, true);
    }

    /**
     * Every way a task ends goes through here once: the ending written around the result
     * (TaskEnding), the outcome emitted and recorded, and -- for a task that ran -- its episode.
     */
    private AgentResult end(AgentContext context, AgentResult result, boolean remember) {
        // From here the counters are what the ending says and records (AgentContext#closeCounters).
        context.closeCounters();
        AgentResult ended = TaskEnding.apply(result, context, traceOf(context.userId(), context.taskId()))
                .withTaskId(context.taskId());
        emitResult(context, ended);
        if (remember) storeEpisode(context, ended);
        return ended;
    }

    /**
     * A task's record as the task page parses it, or an empty one when its rows cannot be read:
     * the ending and the next turn read it, and neither may break for want of it.
     */
    private Map<String, Object> traceOf(String userId, String taskId) {
        try {
            return taskTraces.trace(userId, taskId).orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Could not read the record of task {}: {}", taskId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Why an exception ended the task: its type, and its message unless it holds a private
     * result's text the gateway would not send ({@link AgentContext#firstLeakIn}) -- the ending is
     * read by later prompts, and the log has it.
     */
    static String internalError(RuntimeException e, AgentContext context) {
        String message = e.getMessage();
        boolean quotesPrivate = context.firstLeakIn(message) != null;
        return "an internal error: " + e.getClass().getSimpleName()
                + (message == null ? "" : quotesPrivate ? " (" + QUOTES_PRIVATE + ")" : ": " + message);
    }

    /**
     * What an ending says in place of a failure's message that holds a private result's text the
     * gateway would not send ({@link AgentContext#firstLeakIn}): the ending is read by later
     * prompts, where no canary knows this task's results. The log has the message.
     */
    static final String QUOTES_PRIVATE = "its message quotes a private result; it is in the log";

    /** A failure's message as an ending may quote it: whole, or {@link #QUOTES_PRIVATE}. */
    static String quotable(String message, AgentContext context) {
        return context.firstLeakIn(message) == null ? message : QUOTES_PRIVATE;
    }

    /**
     * The result of a task that was stopped: STALLED with the watchdog's facts, or CANCELLED
     * with who asked -- the owner's Stop or /cancel, or the ops API.
     */
    private AgentResult stopped(AgentContext context) {
        String stall = context.stalled();
        String why = stall != null ? "the stall watchdog stopped it: " + stall
                : cancellationService.why(context.userId(), context.taskId(), context.startTimeMs());
        if (longRunningTaskManager.isActive(context.taskId())) {
            longRunningTaskManager.cancel(context.taskId(), why);
        }
        if (stall != null) {
            log.info("Task {} stopped by the stall watchdog: {}", context.taskId(), stall);
            return AgentResult.stalled(stall, context.trajectory(), context.elapsedMs());
        }
        log.info("Task {} stopped on request: {}", context.taskId(), why);
        return AgentResult.cancelled(why, context.trajectory(), context.elapsedMs());
    }

    /**
     * The files sent with this turn, each checked to be this user's, become the task's files:
     * PRIVATE artifacts, and the list every skill is handed as {@code _attached_files}.
     * <p>
     * That list is also what {@link AgentContext#decide} reads to make every result of the task
     * PRIVATE, so what a skill is given and what is labelled are one list. An id that is missing
     * or another user's file is skipped, and so never handed to a skill: the ids come from the
     * browser, and before this check any id sent was registered and handed on, whoever owned
     * the file. A text upload's bytes go into the canary's index; a file that is not text has
     * none, and the label is its guard. Static, so a test can run it against a database.
     */
    static void registerAttachments(AgentContext context, List<String> attachmentIds,
                                    FileStorageService fileStorage, EventLogService eventLog) {
        List<String> ids = attachmentIds == null ? List.of() : attachmentIds;
        for (String id : ids) {
            try {
                Map<String, Object> info = fileStorage.getFileInfo(id);
                if (info == null || !context.userId().equals(info.get("user_id"))) {
                    log.warn("Task {}: attachment {} is not a file of this user; not registered.",
                            context.taskId(), id);
                    continue;
                }
                String ct = String.valueOf(info.get("content_type"));
                Object size = info.get("size_bytes");
                String text = fileStorage.isTextContent(ct) ? fileStorage.readAsText(id) : null;
                // The type and the size, never the name: a statement's file name carries its
                // account number, and the why is part of every descriptor the cloud reads.
                var why = List.of("uploaded file",
                        ct + ", " + size + " bytes" + (text == null ? ", no text read (not text, or not UTF-8)" : ""));
                Artifact a = context.addFile(id, text, why);
                // A row per file, metadata only. No step ever names an attachment, so without
                // this nothing recorded that a task had one -- the task page could not show the
                // file, its label, or whether it was withheld.
                eventLog.log(context.userId(), context.taskId(), "attachment", "info",
                        "attachment " + a.label(), JSON.writeValueAsString(attachmentDetails(a)), 0);
            } catch (Exception e) {
                log.debug("Could not register attachment {}: {}", id, e.getMessage());
            }
        }
        // Recorded by no step, so no step may report them. Without this the mark was still 0
        // when step 1 persisted, and the first step that recorded nothing of its own -- a tool
        // not found, a critic block -- claimed the attachment, and the ops page named it as the
        // tool that step had run.
        context.claimAllArtifacts();
    }

    /**
     * The chat a task came from: the conversation of the session its own message was saved in
     * -- every question before it and every answer so far ({@link ConversationService#contextOf}),
     * oldest first, each whole -- and, under the answer of each earlier task there that finished,
     * the record of what that task did ({@code recordOf}; see {@link TaskRecord}). A task that
     * ended otherwise answered with its ending, which already carries the record.
     * <p>
     * All of it, because a message left out is one the next task cannot know was said: the
     * window this replaced showed at most fifteen, and older ones only as the local model's
     * 200-word summary of their first 1,500 characters each. A chat longer than the model's
     * context window ends its task with the provider's plain message that it is.
     * <p>
     * Not for unattended work. A scheduled task or /bg is a self-contained instruction, and
     * loading whatever chat happened to be open sent all of it to the cloud on every call of
     * every such run: on 2026-09-24 that was 65 KB of an 83 KB request, most of what the run
     * cost, and the morning digest email ended with a reminder about an unrelated server task
     * it could only have known from the chat. Nor for a task with no message row -- it came from
     * no chat. Static, so a test can run it against a database.
     * <p>
     * Each message as it was written. A message that is nothing but a handle is written in words
     * ({@link TaskRecord#inWords}): this task numbers its own results from 1, and as a call's
     * whole value that message would resolve to one of them. A handle inside other text is left
     * alone: a template's {{1}} is the owner's text -- rewritten, the template he was working on
     * came back with "result 1" in it from the second turn on. (Copied into a tool's argument,
     * it is filled in when the task has a result 1: References#resolveInText.) The records under the answers name results in words.
     *
     * @param recordOf a task id to the record shown under that task's answer, or null for none
     */
    static void loadConversationContext(AgentContext context, String userId, String currentMessageId,
                                        ConversationService conversationService,
                                        FileStorageService fileStorage,
                                        java.util.function.Function<String, String> recordOf) {
        if (context.isUnattended() || currentMessageId == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : conversationService.contextOf(userId, currentMessageId)) {
                String role = (String) row.get("role");
                String content = (String) row.get("content");
                sb.append(role.toUpperCase()).append(": ")
                  .append(ArtifactRef.parse(content) != null ? TaskRecord.inWords(content) : content).append("\n");
                String record = row.get("task_id") == null ? null : recordOf.apply(String.valueOf(row.get("task_id")));
                if (record != null) sb.append(record).append("\n");

                // Include file attachment info for messages that have them
                for (var att : fileStorage.getMessageAttachmentDetails((String) row.get("id"))) {
                    String ct = (String) att.get("content_type");
                    // Never inlined, and not named. A file is PRIVATE: inlined, its bytes
                    // went to the cloud on every later task of the session; and its name can
                    // carry what it holds -- a statement's account number. Skills are handed
                    // it only on the turn it was sent, so this task cannot read it.
                    sb.append("[A file was attached here (").append(ct).append(", ")
                      .append(att.get("size_bytes")).append(" bytes). It is private and not ")
                      .append("available to this task; the user can attach it again.]\n");
                }
            }

            String conversationContext = sb.toString().strip();
            if (!conversationContext.isEmpty()) {
                context.setConversationSummary("### The conversation so far\n" + conversationContext);
                log.debug("Loaded conversation context for user {}: {} chars",
                        userId, conversationContext.length());
            }
        } catch (Exception e) {
            log.warn("Failed to load conversation context for user {}: {}", userId, e.getMessage());
            // Non-fatal — the agent can still process the message without history
        }
    }

    /** Above the local model's answer on the owner's screen: who wrote it, and who never saw it. */
    static final String PRIVATE_HEADER = "**Private — written by your local model, not seen by the cloud:**\n\n";

    /** Above an answer of the local model on a file task that the cloud read but did not give the owner. */
    static final String LOCAL_HEADER = "**Written by your local model:**\n\n";

    /** Above any other private result the cloud gives the owner: a skill's output, a file. */
    static final String PRIVATE_RESULT_HEADER = "**Private — not seen by the cloud:**\n\n";

    /**
     * What history, memory, search, the scheduler's records and every later prompt get in place
     * of a private answer; the web chat and Telegram show the answer itself. It names no channel,
     * so it stays true, and holds no handle, so nothing that stores or forwards it can ever
     * resolve it back to the text.
     */
    static final String PRIVATE_NOTE =
            "[Private answer: sent to you only, never to the cloud model.]";

    /** Why a task holding a file stops when the local model is not answering. */
    static final String LOCAL_DOWN_FOR_FILES = "files you send are read only by your local model, "
            + "and it is not answering; nothing was sent to the cloud. Send the file again when "
            + "the local model is back";

    /**
     * An answer as the cloud wrote it, and what it becomes.
     *
     * @param response  the cloud-safe text: history, memory, search and the scheduler read it
     * @param ownerText what the owner is shown instead -- web chat and Telegram -- or null when
     *                  it is the same
     * @param refusal   why it cannot be delivered, in words the cloud can act on; null when it can
     */
    record Answer(String response, String ownerText, String refusal) {}

    /**
     * Turn what the cloud wrote as its answer into what is delivered.
     * <p>
     * Through the same resolver as every tool argument, so the rules are the same ones: a
     * reference counts only as the whole message, and one that cannot resolve is refused rather
     * than sent as literal text. A whole PRIVATE handle is how the cloud gives the owner an
     * answer it was never shown -- the local model's, written after it read a file or another
     * private result -- so its text is filled in here, on this machine, and kept apart as the
     * owner's text; the response is a note that it exists. An answer or a question with nothing
     * in it is refused, written or placed: a {@code respond("")} delivered an empty bubble -- on
     * Telegram an error notice -- and the text the model had written beside the call was lost; a
     * PDF's {{1}} is empty in the same way.
     * <p>
     * On a task holding a file, the local model's answer reaches the owner even when the cloud
     * does not place it: "Done, see above" is a likely reply from a model that never saw a
     * private answer, or that paraphrased a released one, relying on the cloud to remember is an
     * instruction, and this is the one answer the task exists for.
     * <p>
     * Every result an answer shows in full is marked on the context ({@link AgentContext#markShown}),
     * so the ending of a task that asked a question does not show it a second time.
     */
    static Answer answerFor(String written, AgentContext ctx) {
        String text = written == null ? "" : written;
        if (text.isBlank()) {
            return new Answer(null, null, "the message is empty. Put the whole of it in the "
                    + "message argument.");
        }
        References.Resolved r = References.resolve(Map.of("message", text), ctx.artifacts());
        if (!r.ok()) return new Answer(null, null, r.reason());

        String response = text;
        String ownerText = null;
        Artifact placed = r.used().isEmpty() ? null : r.used().get(0);
        if (placed != null) {
            String value = String.valueOf(r.params().get("message"));
            if (value.isBlank()) {
                return new Answer(null, null, ArtifactRef.parse(text) + " has no text to show"
                        + (ctx.files().contains(placed)
                                ? " — a file that is not text has none. Delegate to read it, then "
                                        + "give the user the delegation's answer."
                                : "."));
            }
            if (placed.isPrivate()) {
                response = PRIVATE_NOTE;
                // Named by what it is: only a local answer was written by the local model.
                ownerText = ("local_answer".equals(placed.tool()) ? PRIVATE_HEADER : PRIVATE_RESULT_HEADER) + value;
            } else {
                // The cloud was shown this text already; nothing here is new to it.
                response = value;
            }
            // Only the whole result: a field of it ({{2.body_text}}) shows part of it.
            if (ArtifactRef.parse(text).field() == null) ctx.markShown(placed);
        }

        return withLocalAnswers(new Answer(response, ownerText, null), placed, ctx);
    }

    /**
     * On a task holding a file, every local answer the owner has not been given goes beneath the
     * text, oldest first: two delegations can be two halves of the answer, and the cloud cannot
     * choose between them -- it saw neither of the private ones, and may have answered from a
     * released one in words of its own. The one it placed is not repeated, and neither is a
     * released one it copied out whole. A private answer goes to the owner only; a released
     * one, which the cloud read in the delegation's report, goes in the response too.
     */
    static Answer withLocalAnswers(Answer a, Artifact placed, AgentContext ctx) {
        if (ctx.files().isEmpty()) return a;
        var owed = new StringBuilder();       // every answer owed, for the owner
        var released = new StringBuilder();   // of those, the ones the cloud read
        boolean anyPrivate = false;
        for (Artifact x : ctx.artifacts()) {
            if (!"local_answer".equals(x.tool()) || placed != null && placed.n() == x.n()) continue;
            if (x.isPrivate()) {
                owed.append("\n\n").append(PRIVATE_HEADER).append(x.output());
                anyPrivate = true;
            } else if (!a.response().contains(x.output().strip())) {
                String part = "\n\n" + LOCAL_HEADER + x.output();
                owed.append(part);
                released.append(part);
            }
            ctx.markShown(x);
        }
        if (owed.length() == 0) return a;
        String response = a.response() + released;
        if (anyPrivate && !response.contains(PRIVATE_NOTE)) response = response + "\n\n" + PRIVATE_NOTE;
        String ownerText = a.ownerText() == null && !anyPrivate ? null
                : (a.ownerText() != null ? a.ownerText() : a.response()) + owed;
        return new Answer(response, ownerText, a.refusal());
    }

    /**
     * The result that ends a task holding a file when the local model is not answering, or null
     * to go on.
     * <p>
     * Only the local model may read a file, so without it the task could only send the cloud
     * descriptions of results it cannot use, and the owner would wait for an answer that cannot
     * come. Probed only when there is a file: every other task goes on as before, without the
     * cost of the probe.
     */
    static AgentResult stopWithoutLocalModel(AgentContext ctx,
                                             java.util.function.BooleanSupplier localAvailable) {
        if (ctx.files().isEmpty() || localAvailable.getAsBoolean()) return null;
        log.warn("Task {}: a file was sent and the local model is not answering; stopping "
                + "before any cloud call.", ctx.taskId());
        return AgentResult.error(LOCAL_DOWN_FOR_FILES, ctx.trajectory(), ctx.elapsedMs());
    }

    /**
     * How many steps in a row may produce nothing to run before the task, or a delegation, ends:
     * a model that has not managed to act that many times running is not making progress. Not
     * a limit on the work: a step that runs anything starts the count again.
     */
    static final int NOTHING_TO_RUN_IN_A_ROW = 3;

    /**
     * The steps of the task, as many as the work takes. It ends when the model answers or asks,
     * when it is stopped -- the owner's Stop, the ops API, the stall watchdog -- or when
     * {@link #NOTHING_TO_RUN_IN_A_ROW} steps in a row ran nothing. A step limit ended a task that
     * was twenty successful steps into the router fixes the owner had asked for.
     */
    private AgentResult runLoop(AgentContext context) {
        var nothing = new RunOfNothing(); // steps in a row that ran nothing
        int unansweredQuestions = 0;      // ask_user calls on a task with nobody to answer them

        for (int step = 0; ; step++) {
            // Stopped from outside -- the owner's Stop or /cancel, the ops API, or the stall
            // watchdog (cancelStalledTasks) -- between steps. Inside one, a model call hears it on
            // its progress hook and a tool through the supplier it was handed.
            if (context.isCancelled()) return stopped(context);
            // The step its model calls are made for, as the owner is shown them (LiveCall).
            context.setStep(step + 1);

            boolean debug = debugService.isEnabled(context.userId());

            // === DETERMINISTIC SKILL CREATION ===
            // When CapabilityResolver detected a gap (step 0 only), bypass the
            // ThinkingEngine entirely: synthesize the skill_create action from
            // the deterministic hint and go straight to cloud code generation.
            // This removes ALL LLM involvement from the routing decision —
            // no thinking call, no risk of refusal, no wasted tokens.
            // The cloud LLM is only used for code generation (its strength).
            if (step == 0 && context.capabilityHint() != null) {
                CapabilityResolver.CapabilityHint hint = context.capabilityHint();

                log.info("Task {} step 1: deterministic skill_create from CapabilityResolver → '{}'",
                        context.taskId(), hint.suggestedName());

                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                        "Creating skill '" + hint.suggestedName() + "' (auto-detected)...");

                // Build skill_create params directly from the hint
                Map<String, Object> skillParams = new HashMap<>();
                skillParams.put("name", hint.suggestedName());
                skillParams.put("description", hint.description());
                skillParams.put("parameters", hint.parametersJson());
                skillParams.put("timeout", String.valueOf(hint.timeout()));
                if (!hint.systemPackages().isEmpty()) {
                    skillParams.put("system_packages", String.join(" ", hint.systemPackages()));
                }
                if (!hint.pipPackages().isEmpty()) {
                    skillParams.put("requirements", String.join("\n", hint.pipPackages()));
                }
                if (!hint.credentials().isEmpty()) {
                    skillParams.put("credentials", String.join(",", hint.credentials()));
                }

                AgentAction action = new AgentAction(
                        AgentAction.SKILL_CREATE, skillParams,
                        "CapabilityResolver detected missing " + hint.category()
                                + " capability — creating skill deterministically");
                // No model chose this step, so nothing was written beside it; its code is written
                // on the cloud, or on the local model when the cloud is not available.
                context.chat().step(step + 1, action, null, llmRouter.isLocal(llmRouter.selectProvider(context)));

                if (debug) {
                    emitDebug(context.userId(),
                            "DETERMINISTIC SKILL_CREATE: " + hint.suggestedName()
                                    + " (bypassed ThinkingEngine, no LLM call)");
                }

                AgentObservation obs = createSkill(action, context);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_CREATE [" + hint.suggestedName() + "] "
                                    + (obs.success() ? "OK" : "FAIL") + " (" + obs.durationMs() + "ms)\n"
                                    + obs.output());
                }

                // Clear the hint so subsequent steps don't re-trigger
                context.setCapabilityHint(null);
                continue;
            }

            // === THINK ===
            LlmProvider provider = llmRouter.selectProvider(context);
            boolean local = llmRouter.isLocal(provider);
            readMessages(context, step + 1, local);
            String providerLabel = local ? "local" : provider.name();
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    "Step " + (step + 1) + " · " + providerLabel,
                    tokenData(context));

            // While the call runs, its chat is shown what it is doing (AgentContext#call).
            ThinkResult thinkResult;
            try {
                // On the local model, when the cloud is not available: work the task waits on.
                thinkResult = local
                        ? lane.foreground(() -> thinkingEngine.decideNextActionFull(context, provider))
                        : thinkingEngine.decideNextActionFull(context, provider);
            } catch (EgressRefused refused) {
                // The gateway found bytes of a PRIVATE artifact in the request and nothing was
                // sent. Deterministic, so there is no retry; and no valve, because handing the
                // registry back would not change what the next prompt contains. Expected count
                // in normal operation: zero. An occurrence is a bug report with the handle and
                // the part index attached: that goes to the log and the ledger, and the owner
                // gets an ending that names the result and hands him his private results.
                log.error("Task {} step {}: PRIVACY_BLOCKED — {}", context.taskId(), step + 1,
                        refused.getMessage());
                return AgentResult.privacyBlocked(TaskEnding.blocked(refused, context),
                        context.trajectory(), context.elapsedMs());
            } catch (ProviderRefused declined) {
                // Declined as reasoning extraction: the provider judged the step to be giving away
                // the model's hidden reasoning, most likely because of the words the prompt asks
                // for beside each call, which ended the owner's task on 2026-10-01. The step is
                // asked again with no such words asked for, which changes only the end of the
                // request, so the provider reads the rest from its cache; declined again, it is
                // asked once more without the words the model wrote beside its earlier calls,
                // which rewrites the steps it is shown (AgentContext#declinedAsReasoning); declined
                // a third time, it ends like any refusal.
                if (!declined.asReasoningExtraction() || context.timesDeclinedAsReasoning() == 2) {
                    return noAnswer(context, local, provider, declined.reply(), declined);
                }
                account(context, local, provider, declined.reply());
                context.markProgress(); // the model replied
                context.markDeclinedAsReasoning();
                log.warn("Task {} step {}: declined as reasoning extraction; asking again with no "
                        + "words beside the call{}", context.taskId(), step + 1,
                        context.earlierWordsWithheld() ? ", nor those beside the earlier ones" : "");
                context.chat().askedAgainQuietly(step + 1, context.earlierWordsWithheld());
                step--; // the same step, asked again
                continue;
            } catch (LlmException noReply) {
                // What the engine does not ask again (ThinkingEngine): a limit of the model, a
                // request the provider refused as it stands, a provider that cannot be reached.
                // A cloud that cannot be reached is asked once more or handed over to the local
                // model, and the step is taken again; anything else ends the task, saying which.
                if (!local && noReply.unreachable() && goesOnWithoutTheCloud(context, provider, noReply)) {
                    step--; // the same step, asked again
                    continue;
                }
                return noAnswer(context, local, provider, noReply.reply(), noReply);
            }
            context.markProgress(); // LLM responded — task is alive
            if (!local && thinkResult.reply() != null) context.cloudAnswered();
            AgentAction action = thinkResult.action();
            int billed = account(context, local, provider, thinkResult.reply());
            // One tool call runs per step. The others the reply made are not dropped unsaid: the
            // observation this step records names them (recordAndEmitObservation).
            if (!ThinkingEngine.THINKING.equals(action.tool())) {
                context.setCallsNotRun(callsNotRun(thinkResult.reply()));
            }

            // Emit running token totals so the frontend can update the live counter
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.PROGRESS,
                    action.tool() + " (" + String.format("%,d", billed) + " tok)",
                    tokenData(context));

            // Emit thinking detail: user prompt (skip system — it repeats), reasoning, chosen tool
            emitThinkDetail(context, thinkResult, step + 1, providerLabel);

            // Emit debug info when debug mode is active
            if (debug) {
                emitDebugPrompt(context.userId(), thinkResult, step + 1);
            }

            log.info("Task {} step {}: tool={} reasoning={}", 
                    context.taskId(), step + 1, action.tool(),
                    truncate(action.reasoning(), 100));

            // === A STEP THAT PRODUCED NOTHING TO RUN ===
            // The engine returns THINKING for a reply that was empty, that is not an action, that
            // never came because the call failed, or -- unattended, before anything ran -- that
            // answered instead of doing the work. Nothing runs (stepRanNothing).
            if (ThinkingEngine.THINKING.equals(action.tool())) {
                AgentResult end = stepRanNothing(context, action, action.reasoning(),
                        thinkResult.callFailed(), step, nothing);
                if (end != null) return end;
                continue;
            }

            // === RESPOND / ASK ===
            // An answer or a question that is not delivered ran nothing either: the model is told
            // why, and asked again.
            if (action.isResponse()) {
                Answer answer = answerFor(action.responseText(), context);
                String refusal = answer.refusal() != null ? answer.refusal() : alongsideOtherCalls(context);
                if (refusal != null) {
                    AgentResult end = stepRanNothing(context, action, "Not delivered: " + refusal, null, step, nothing);
                    if (end != null) return end;
                    continue;
                }
                return AgentResult.completed(
                        answer.response(),
                        context.trajectory(),
                        context.elapsedMs()
                ).withOwnerText(answer.ownerText());
            }

            if (action.isAskUser()) {
                // Unattended work has nobody to ask. The question used to be returned as a
                // COMPLETED result, so a scheduled task could stop on its first uncertainty,
                // report success, and quietly never do the thing it was scheduled for.
                //
                // Telling it so and letting it carry on is better than failing here: most
                // questions an agent asks have a defensible default, and it is the agent, not a
                // rule in this file, that knows what the sensible one is. It gets one nudge; a
                // second question means it genuinely cannot proceed without an answer, and then
                // the honest outcome is to stop and say what it needed to know.
                if (context.isUnattended() && unansweredQuestions == 0) {
                    unansweredQuestions++;
                    log.info("Task {} step {}: ask_user on unattended work — telling it to decide",
                            context.taskId(), step + 1);
                    AgentObservation noOne = AgentObservation.failure("ask_user",
                            "Nobody can answer: this task is running unattended, with no user at "
                                    + "the chat. Decide it yourself using the best available "
                                    + "evidence and say plainly in your final answer which "
                                    + "assumption you made, so it can be corrected later. If the "
                                    + "task genuinely cannot proceed without this answer, ask "
                                    + "again and it will stop and report the question.", 0);
                    recordAndEmitObservation(context, action, noOne, step + 1);
                    context.markProgress();
                    continue;
                }
                Answer question = answerFor(action.responseText(), context);
                String refusal = question.refusal() != null ? question.refusal() : alongsideOtherCalls(context);
                if (refusal != null) {
                    AgentResult end = stepRanNothing(context, action, "Not delivered: " + refusal, null, step, nothing);
                    if (end != null) return end;
                    continue;
                }
                return AgentResult.needsInput(
                        question.response(),
                        context.trajectory(),
                        context.elapsedMs()
                ).withOwnerText(question.ownerText());
            }

            // === CRITIQUE ===
            // skill_manage and the registry's tools: the other special actions have no rules
            // there. A call the critic blocks runs nothing (stepRanNothing).
            CriticAgent.Verdict verdict = action.isSkillManage() || action.isFindTools() || !action.isSpecialAction()
                    ? criticAgent.evaluate(action, context) : CriticAgent.Verdict.allow(List.of());
            if (!verdict.allowed()) {
                log.warn("Task {} step {} blocked by critic: {}", context.taskId(), step + 1, verdict.blockReason());
                if (debug) emitDebug(context.userId(), "CRITIC BLOCKED: " + verdict.blockReason());
                AgentResult end = stepRanNothing(context, action, "BLOCKED: " + verdict.blockReason(), null, step, nothing);
                if (end != null) return end;
                continue;
            }
            for (String warning : verdict.warnings()) {
                log.info("Task {} critic warning: {}", context.taskId(), warning);
            }

            // === PROGRESS ===
            // Before the step runs, the owner's chat says which step it is and what the model
            // wrote beside the call: what it found, and what it does now and why. A call the
            // critic blocked, and an answer or a question that was not delivered, get none.
            context.chat().step(step + 1, action, action.reasoning(), local);

            // === SKILL MANAGEMENT (special actions — always available) ===
            if (action.isSkillCreate()) {
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                        "Creating skill '" + action.params().getOrDefault("name", "?") + "'...");

                // Never refused for having failed before, however often: a skill that has failed
                // is fixed under its own name, and a refusal left the model no way to fix it. A
                // failure is told what the earlier attempts at that name failed with instead, so
                // the next attempt changes the approach rather than repeating it.
                String earlier = earlierAttempts(context.trajectory(), str(action.params(), "name"));
                AgentObservation obs = createSkill(action, context);
                if (!obs.success() && earlier != null) {
                    obs = AgentObservation.failure(obs.tool(), obs.output() + earlier,
                            Map.of(SKILL_CREATE_ERROR, obs.output()), obs.durationMs());
                }
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_CREATE [" + action.params().getOrDefault("name", "?") + "] "
                                    + (obs.success() ? "OK" : "FAIL") + " (" + obs.durationMs() + "ms)\n"
                                    + obs.output());
                }
                continue;
            }

            if (action.isSkillManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeSkillManage(action.params(), context);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");

                // If listing returned empty inventory, append guidance
                String manageAction = action.params().getOrDefault("action", "").toString();
                if ("list".equals(manageAction) && toolRegistry.all().isEmpty()) {
                    result += "\n\nNo tools are registered. Use skill_create to build tools for your task.";
                }

                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                if (debug) {
                    emitDebug(context.userId(),
                            "SKILL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + result);
                }
                continue;
            }

            // === FIND TOOLS (special action: the local model running the task itself) ===
            if (action.isFindTools()) {
                long startMs = System.currentTimeMillis();
                String result = findTools(action.params(), context);
                long durationMs = System.currentTimeMillis() - startMs;
                AgentObservation obs = result.startsWith("ERROR")
                        ? AgentObservation.failure(action.tool(), result, durationMs)
                        : AgentObservation.success(action.tool(), result, Map.of(), durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                continue;
            }

            // === MEMORY MANAGEMENT (special action) ===
            if (action.isMemoryManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeMemoryManage(action.params(), context);
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                if (debug) {
                    emitDebug(context.userId(),
                            "MEMORY_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + result);
                }
                continue;
            }

            // === CREDENTIAL MANAGEMENT (special action) ===
            if (action.isCredentialManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeCredentialManage(action.params(), context.userId());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran

                // Refresh credential keys after store so subsequent ✓/✗ marks are accurate
                if (ok && "store".equals(action.params().get("action"))) {
                    try {
                        context.setCredentialKeys(credentialVault.listCredentialKeys(context.userId()));
                    } catch (Exception e) {
                        log.debug("Failed to refresh credential keys: {}", e.getMessage());
                    }
                }

                if (debug) {
                    emitDebug(context.userId(),
                            "CREDENTIAL_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + result);
                }
                continue;
            }

            // === SCHEDULE MANAGEMENT (special action) ===
            if (action.isScheduleManage()) {
                long startMs = System.currentTimeMillis();
                String result = executeScheduleManage(action.params(), context.userId());
                long durationMs = System.currentTimeMillis() - startMs;
                boolean ok = !result.startsWith("ERROR");
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, Map.of(), durationMs)
                        : AgentObservation.failure(action.tool(), result, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                if (debug) {
                    emitDebug(context.userId(),
                            "SCHEDULE_MANAGE [" + action.params().getOrDefault("action", "?") + "] "
                                    + (ok ? "OK" : "FAIL") + " (" + durationMs + "ms)\n"
                                    + result);
                }
                continue;
            }

            // === DELEGATE TO LOCAL LLM (special action) ===
            if (action.isDelegate()) {
                long startMs = System.currentTimeMillis();
                DelegationPlan plan;
                try {
                    plan = LocalExecutor.parsePlan(action.params());
                } catch (IllegalArgumentException malformed) {
                    AgentResult end = notStarted(context, action, "ERROR: " + malformed.getMessage(),
                            Map.of(), step, nothing);
                    if (end != null) return end;
                    continue;
                }
                // The goal and the texts the cloud wrote, for the step's row, whether the delegation
                // runs or not (stepDetails). Ones the local model wrote, the cloud not being
                // available, can quote what it has read.
                Map<String, Object> asked = new LinkedHashMap<>();
                if (!local) {
                    asked.put("goal", plan.goal());
                    if (!plan.texts().isEmpty()) asked.put("texts", plan.texts());
                }
                if (plan.goal().isBlank()) {
                    AgentResult end = notStarted(context, action,
                            "ERROR: 'goal' parameter is required for delegate action.", asked, step, nothing);
                    if (end != null) return end;
                    continue;
                }
                // A block of lines the local model would have to retype -- refused before anything
                // runs, with what to do instead: hand it over by name (LiteralBlocks).
                LiteralBlocks.Block block = LiteralBlocks.first(plan);
                if (block != null) {
                    log.warn("Task {} step {}: delegation not started — {} holds {} of {} lines.",
                            context.taskId(), step + 1, block.where(), block.kind(), block.lines());
                    context.chat().notStarted(step + 1, block, local);
                    AgentResult end = notStarted(context, action, LiteralBlocks.refusal(block), asked, step, nothing);
                    if (end != null) return end;
                    continue;
                }
                // A text is inserted exactly, so one holding a secret the filter removed would
                // write the marker in the secret's place -- in every call that uses it.
                if (Redactor.holdsRemovedSecret(plan.texts())) {
                    AgentResult end = notStarted(context, action, "Not started: a text holds a removed "
                            + "secret. " + REMOVED_SECRET, asked, step, nothing);
                    if (end != null) return end;
                    continue;
                }

                log.info("Task {} step {}: delegating to local LLM — goal: {}, steps: {}",
                        context.taskId(), step + 1, truncate(plan.goal(), 100), plan.steps().size());

                // Work the task waits on: the local model's background work makes way for it.
                LocalExecutor.Outcome outcome = lane.foreground(
                        () -> localExecutor.execute(plan, context, accountOf(context)));
                long durationMs = System.currentTimeMillis() - startMs;
                String result = outcome.text();
                // Zero tools ran is not a success, whatever the summary says -- unless the goal
                // gave it results to read, and it wrote what it read (LocalExecutor.completed).
                // A local model that fetched nothing and called done with a confident paragraph
                // used to produce a
                // successful step, a successful task, and a scheduled run recorded as delivered
                // -- and with the registry withheld the cloud has no way to check it. Failing
                // here also trips the valve in ThinkingEngine, so the registry comes back and
                // the cloud can finish the job itself rather than delegating into a wall.
                boolean ok = outcome.ok();
                if (!ok && outcome.stepCount() == 0 && !result.startsWith("ERROR")) {
                    log.warn("Task {} step {}: delegation claimed completion with no tool call.",
                            context.taskId(), step + 1);
                }
                // Which skills really ran, so curation and scheduled_task_runs.skills_used see
                // the work instead of a single 'delegate' entry.
                Map<String, Object> structured = new LinkedHashMap<>(asked);
                if (!outcome.toolsRun().isEmpty()) structured.put("delegatedTools", outcome.toolsRun());
                if (!outcome.produced().isEmpty()) {
                    structured.put("artifacts", outcome.produced().stream().map(a -> Map.of(
                            "n", a.n(), "tool", a.tool(), "label", a.label().name(),
                            "chars", a.output().length(), "why", a.why(),
                            "indexed", a.indexed())).toList());
                }
                AgentObservation obs = ok
                        ? AgentObservation.success(action.tool(), result, structured, durationMs)
                        : AgentObservation.failure(action.tool(), result, structured, durationMs);
                recordAndEmitObservation(context, action, obs, step + 1);
                context.markProgress();
                nothing.steps = 0; // a step that ran
                if (debug) {
                    emitDebug(context.userId(),
                            "DELEGATE (" + durationMs + "ms, goal: "
                                    + plan.goal() + ")\n"
                                    + result);
                }
                continue;
            }

            // === ACT ===
            emitActDetail(context, action, step + 1);
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    "Running " + action.tool() + "...");
            ScheduledFuture<?> toolHeartbeat = startToolHeartbeat(context,
                    "Running " + action.tool());
            AgentObservation observation;
            try {
                observation = executeTool(action, context);
            } finally {
                stopHeartbeat(toolHeartbeat);
            }

            // Append critic warnings to the observation so the LLM sees them
            if (verdict.hasWarnings()) {
                String warningBlock = "\n\n⚠️ SYSTEM: " + String.join(" | ", verdict.warnings());
                observation = new AgentObservation(
                        observation.tool(), observation.success(),
                        observation.output() + warningBlock,
                        observation.structured(), observation.durationMs());
            }

            // === OBSERVE ===
            recordAndEmitObservation(context, action, observation, step + 1);
            context.markProgress(); // tool completed — task is alive
            nothing.steps = 0; // a step that ran

            if (debug) {
                emitDebug(context.userId(),
                        "TOOL RESULT [" + action.tool() + "] "
                                + (observation.success() ? "OK" : "FAIL")
                                + " (" + observation.durationMs() + "ms)\n"
                                + observation.output());
            }

            // Track tool usage for skill curation analytics. On a FAILURE, keep the parameters
            // and the error too: that is what makes the failure reproducible, and a call that
            // really broke is a better test case than any input we could invent. Successes stay
            // counters only — there is no reason to store the arguments of every call that
            // worked, and doing so would put far more of the user's data in the database.
            if (observation.success()) {
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.PROGRESS,
                        action.tool() + " ✓ " + TaskRecord.duration(observation.durationMs()),
                        tokenData(context));
            } else {
                // Which task, and how long the failure's text is -- not the text, which is whole
                // in this step's observe detail just before it. This line goes to Telegram as
                // well, where a long traceback went out in parts for every failed call, of a task
                // the owner was following in the browser too, ahead of the results and answers
                // queued after it.
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.WARNING,
                        action.tool() + " ✗ " + TaskRecord.duration(observation.durationMs())
                                + String.format(Locale.ROOT, ", %,d chars — task %s",
                                        observation.output().length(), context.taskId()));
            }

            // === DELEGATION NUDGE ===
            // Detect when the cloud LLM is doing repetitive tool calls that should
            // be delegated to the local LLM. After 2+ consecutive calls to the same
            // registered skill, inject a cost warning into the prompt context.
            injectDelegationNudge(context);

            // Inject reflection after consecutive failures OR consecutive hollow results
            injectReflection(context, action);
        }
    }

    /** The steps in a row that ran nothing, as {@link #stepRanNothing} counts them. */
    private static final class RunOfNothing {
        /** Steps in a row that ran nothing; a step that ran sets it back to 0. */
        int steps;
        /** Of those, the calls to the model that failed. */
        int failedCalls;
        /** How the last of those failed. */
        String lastFailure;
    }

    /**
     * Marks a delegate step that was refused before the local model was given anything
     * ({@link #notStarted}): the local tier has not had its turn, so it is no failed delegation
     * (ThinkingEngine#delegationFailed).
     */
    static final String NOT_STARTED = "notStarted";

    /**
     * A delegation refused before the local model was given anything: texts that are not an
     * object of names to texts, a missing goal, a block the local model would have to retype
     * ({@link LiteralBlocks}), a text holding a removed secret. A step that ran nothing
     * ({@link #stepRanNothing}), recorded with what the cloud asked ({@code asked}) and
     * {@link #NOT_STARTED}.
     */
    private AgentResult notStarted(AgentContext context, AgentAction action, String told,
                                   Map<String, Object> asked, int step, RunOfNothing run) {
        var structured = new LinkedHashMap<String, Object>(asked);
        structured.put(NOT_STARTED, true);
        return stepRanNothing(context, action, told, null, step, run, structured);
    }

    /**
     * A step that ran nothing: a reply with nothing to run in it, or no reply because the call
     * failed ({@code callFailed}); an answer or a question that was not delivered; a call the
     * critic blocked; a delegation refused before it started ({@link #notStarted}). What the
     * model is told about it is recorded as the step -- with a warning when one more would stop
     * the task -- and the model is asked again, until {@link #NOTHING_TO_RUN_IN_A_ROW} of them
     * come in a row. However many there are in a long task that keeps making progress between
     * them, they do not end it.
     *
     * @param callFailed how the call to the model failed, or null when it did not
     * @return the task's end when this step is the last one allowed in a row, or null to go on
     */
    private AgentResult stepRanNothing(AgentContext context, AgentAction action, String told,
                                       String callFailed, int step, RunOfNothing run) {
        return stepRanNothing(context, action, told, callFailed, step, run, Map.of());
    }

    /** As above, the step recorded with {@code structured}. */
    private AgentResult stepRanNothing(AgentContext context, AgentAction action, String told,
                                       String callFailed, int step, RunOfNothing run,
                                       Map<String, Object> structured) {
        // A new run of them starts wherever a step that ran reset the count.
        if (++run.steps == 1) run.failedCalls = 0;
        if (callFailed != null) {
            run.failedCalls++;
            run.lastFailure = callFailed;
        }
        if (run.steps == NOTHING_TO_RUN_IN_A_ROW - 1) {
            told += "\n\nWARNING: one more step like this and the task is stopped.";
        }
        // Recorded before any stop, so the task's steps hold every one of them -- the one that
        // ends the task too -- under their own name.
        recordAndEmitObservation(context, action, AgentObservation.failure(action.tool(), told, structured, 0),
                step + 1);
        // failureLimit, not completed: an abort. Recording it as COMPLETED marked the event log
        // "info" and stored the episode with a [SUCCESS] prefix, so the memory layer later
        // recalled a failed task as a worked example.
        if (run.steps >= NOTHING_TO_RUN_IN_A_ROW) {
            log.error("Task {} step {}: {} steps in a row ran nothing — aborting task",
                    context.taskId(), step + 1, run.steps);
            return AgentResult.failureLimit(ranNothing(run.steps, run.failedCalls, run.lastFailure, context),
                    context.trajectory(), context.elapsedMs());
        }
        context.markProgress();
        log.warn("Task {} step {}: nothing ran, asking the model again ({}/{} in a row)",
                context.taskId(), step + 1, run.steps, NOTHING_TO_RUN_IN_A_ROW);
        return null;
    }

    /**
     * Why steps that ran nothing ended the task, as facts. A reply the model gave that could not
     * be run or delivered and a call to the model that failed are different stops: the ending
     * used to say "the model produced nothing that could be run" of a model the call had never
     * reached -- a network that could not be reached, a Models API lookup that timed out.
     *
     * @param steps       how many steps in a row ran nothing
     * @param failedCalls how many of the {@code steps} were calls that failed
     * @param lastFailure how the last of those failed, the provider's message
     */
    static String ranNothing(int steps, int failedCalls, String lastFailure, AgentContext context) {
        if (failedCalls == 0) {
            return "The model produced nothing that could be run or delivered " + steps + " times in a row.";
        }
        String last = " (the last: " + quotable(lastFailure, context) + ")";
        if (failedCalls == steps) return "The call to the model failed " + steps + " times in a row" + last + ".";
        return steps + " steps in a row ran nothing: the model's reply could not be run or delivered "
                + times(steps - failedCalls) + ", and the call to it failed " + times(failedCalls) + last + ".";
    }

    /** Where a failed skill_create's observation keeps its own error, apart from the earlier ones. */
    static final String SKILL_CREATE_ERROR = "skillCreateError";

    /**
     * What the model is told of a call, or a skill's code, that holds a secret the privacy filter
     * removed ({@link Redactor#holdsRemovedSecret}): it is not run, and why.
     */
    static final String REMOVED_SECRET = "«secret removed» and «vault:KEY» stand for a secret that "
            + "was removed from what you were shown. Neither is a value: written anywhere, it would "
            + "replace the secret with that text. Leave the setting it stands for as it is and change "
            + "only what you mean to change, or have a skill read the secret where it is -- from the "
            + "device, or from the vault as one of its credentials.";

    /**
     * What a skill_create that failed is told beyond its own error when skill_create has already
     * failed for the same name in this task: each earlier attempt's error, whole and in order, so
     * the model changes the approach instead of the wording. Null when there is none.
     */
    static String earlierAttempts(AgentTrajectory trajectory, String name) {
        if (name == null) return null;
        var errors = new ArrayList<String>();
        for (var turn : trajectory.turns()) {
            if (turn.action().isSkillCreate() && !turn.observation().success()
                    && name.equals(String.valueOf(turn.action().params().get("name")))) {
                // Its own error, without the earlier ones it was told of: those are listed here.
                Object own = turn.observation().structured() == null ? null
                        : turn.observation().structured().get(SKILL_CREATE_ERROR);
                errors.add(own != null ? own.toString() : turn.observation().output());
            }
        }
        if (errors.isEmpty()) return null;
        var sb = new StringBuilder("\n\nskill_create for '").append(name).append("' failed ")
                .append(times(errors.size())).append(" before in this task. Change the approach -- "
                        + "a different library, method or data source -- not only the wording. "
                        + "The earlier errors, in order:");
        for (int i = 0; i < errors.size(); i++) {
            sb.append("\n\n--- attempt ").append(i + 1).append(" ---\n").append(errors.get(i));
        }
        return sb.toString();
    }

    /**
     * What the model is told of the tool calls a reply made beyond the first, which is the one
     * the step runs: each by name with its arguments as it wrote them, and that they did not run.
     * Null when the reply made one call or none.
     */
    static String callsNotRun(LlmResponse reply) {
        if (reply == null || reply.toolCalls().size() < 2) return null;
        var sb = new StringBuilder("Your reply made ").append(reply.toolCalls().size())
                .append(" tool calls, and a step runs one: the first. These did not run:");
        for (var call : reply.toolCalls().subList(1, reply.toolCalls().size())) {
            String args;
            try {
                args = TextCalls.MAPPER.writeValueAsString(call.arguments() == null ? Map.of() : call.arguments());
            } catch (Exception e) {
                args = String.valueOf(call.arguments());
            }
            sb.append("\n- ").append(call.name()).append(' ').append(args);
        }
        return sb.append("\nMake them again, one per step, if they are still needed.").toString();
    }

    /**
     * Why an answer or a question is not delivered when the reply that made it made other tool
     * calls too, or null when it made none: it would end the task, and those calls would be
     * dropped with nobody told. The model is told which calls they were (the note the step's
     * observation carries), and can make them first, or answer alone.
     */
    private static String alongsideOtherCalls(AgentContext context) {
        return context.hasCallsNotRun()
                ? "it came with other tool calls, and an answer or a question ends the task. Make "
                        + "the calls you still need first, one per step, or send it on its own."
                : null;
    }

    private static String times(int n) {
        return n + (n == 1 ? " time" : " times");
    }

    /**
     * Execute a tool and wrap the result in an AgentObservation.
     */
    private AgentObservation executeTool(AgentAction action, AgentContext context) {
        // The restriction, made structural. ThinkingEngine can withhold a skill from the tools
        // array, but withholding is not enforcement: the text protocol is still parsed, the
        // parser accepts any name, and this method resolves against the whole registry. Without
        // this check the model can talk its way back onto the path it was taken off -- and it
        // would, because on the step where it resists it emits the old text envelope.
        var offered = context.offeredTools();
        if (offered != null && !offered.contains(action.tool())) {
            log.info("Task {}: refused '{}' — it was not offered on this step.",
                    context.taskId(), action.tool());
            return AgentObservation.failure(action.tool(),
                    "'" + action.tool() + "' is not available to you on this task. "
                            + (context.isUnattended() ? "Nobody is waiting for it"
                                    : "The owner has chosen cost over speed")
                            + ", so the work runs on the local model: call "
                            + "'delegate' with the goal stated in full — including anything you "
                            + "have already worked out — and the skills it needs in 'tools'.", 0);
        }

        var toolOpt = toolRegistry.find(action.tool());
        if (toolOpt.isEmpty()) {
            String available = String.join(", ", toolRegistry.names());
            String hint = available.isEmpty()
                    ? "No tools are currently registered. Use skill_create to build the tool you need."
                    : "Available tools: " + available + ". Use skill_create if none of these fit (to fix an existing skill, reuse its name).";
            return AgentObservation.failure(action.tool(),
                    "Tool '" + action.tool() + "' not found. " + hint, 0);
        }

        Tool tool = toolOpt.get();

        // A secret the filter removed is no value. The cloud reads a router's configuration with
        // option key '«secret removed»', and a call that writes the section back as it read it
        // would set the WiFi key to that text and lock every device out. Refused, whatever the
        // tool: no call has a use for the marker.
        if (Redactor.holdsRemovedSecret(action.params())) {
            log.warn("Task {}: refused '{}' — an argument holds a removed secret.",
                    context.taskId(), action.tool());
            return AgentObservation.failure(action.tool(),
                    "Not run: an argument holds a removed secret. " + REMOVED_SECRET, 0);
        }

        // Where a skill's progress reports are shown: through LongRunningTaskManager. The
        // callback is available to every skill; only skills that call report_progress() will
        // actually use it. On the first progress report the task is auto-registered as
        // long-running. Each report is also progress for the stall watchdog (toolContext).
        SandboxManager.ProgressCallback progressCallback = new SandboxManager.ProgressCallback() {
            private volatile boolean registered = false;

            @Override
            public void onProgress(String message, Integer percent) {
                if (!registered) {
                    registered = true;
                    longRunningTaskManager.register(
                            context.taskId(), context.userId(), context.originalMessage(), action.tool());
                }
                longRunningTaskManager.reportProgress(context.taskId(), message, percent);
            }
        };

        ToolExecutionContext execCtx = context.toolContext(progressCallback);

        // The same resolver the delegation uses, against the task's results -- the cloud sees
        // task-wide handles in every descriptor, so {{3}} here is the task's third result. One
        // pass substitutes and refuses: a reference that does not resolve, names a failed result,
        // or is malformed would otherwise reach the skill as literal text -- as an argument to
        // smtp_send_email, an email whose whole body is five characters, sent and recorded green.
        // One inside text is filled in (resolveInText): a date line and then the menu.
        References.Resolved refs = References.resolveInText(action.params(), context.artifacts());
        if (!refs.ok()) {
            log.warn("Task {}: '{}' — reference refused.", context.taskId(), refs.refused());
            return AgentObservation.failure(action.tool(), "Not run: the value of '"
                    + refs.refused() + "' would have been sent as literal text. " + refs.reason(), 0);
        }
        Map<String, Object> resolved = refs.params();

        long startMs = System.currentTimeMillis();
        ToolResult result;
        try {
            result = tool.execute(resolved, execCtx);
        } catch (Exception e) {
            log.error("Tool '{}' threw exception: {}", action.tool(), e.getMessage(), e);
            result = ToolResult.failure("Tool execution error: " + e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - startMs;

        // The record, and the substitution at the source. This is the only place a result on
        // the attended path enters the trajectory, and it is before the critic-warning rebuild
        // further up the loop constructs a fresh observation -- which is why the label cannot be
        // a flag on the observation: that rebuild would drop it. The bytes go to the task's
        // store; what goes on is either the bytes (PUBLIC) or the descriptor (PRIVATE), and
        // nothing downstream that reads the observation -- the renderers, the episode, the events
        // rows, the repair evidence -- ever sees the other. The owner's own copy of a private
        // result is taken from the store (answerFor, TaskEnding).
        // The label from what the resolver actually pulled in, so it describes what moved.
        // The same decision the delegation uses. Never tainted here -- the cloud has not read
        // private bytes -- but a call that pulled in an unindexed result stays unindexed, and
        // one whose output repeats a private result is private.
        Artifact.Decision decision = context.decide(tool.requiredCredentials(), refs.used(), false,
                result.output());
        Artifact artifact = context.addArtifact(tool.name(), action.params(), resolved,
                result.output(), result.success(), decision);
        // The cloud is shown a description of it, and its words beside the next call cannot say
        // what it holds: the local model summarises it for the owner's chat.
        if (artifact.isPrivate()) context.chat().privateResult(artifact);

        // Usage, with the error whole: the curator's row is the owner's diagnostic and is read
        // through ops; the label on it is what keeps it out of the cloud's repair prompt. Whole
        // but for vault values, as everything stored for display is: a failed call can quote
        // the password it ran with, and ops is read by sessions whose model runs in the cloud.
        curatorService.recordUsage(action.tool(), context.userId(), context.taskId(),
                result.success(), durationMs,
                result.success() ? null : action.params(),
                result.success() ? null : Redactor.scrubVault(result.output(),
                        context.secretValues()).text(), artifact.label());

        // If this tool was tracked as long-running, finalize it -- with the shaped text.
        if (longRunningTaskManager.isActive(context.taskId())) {
            String shown = artifact.isPrivate() ? artifact.describe() : result.output();
            if (result.success()) longRunningTaskManager.complete(context.taskId(), shown);
            else longRunningTaskManager.fail(context.taskId(), shown);
        }

        return Artifact.asObservation(artifact, result, durationMs);
    }

    /** How many of the owner's most used skills every task's model is offered from the start. */
    static final int USUAL_TOOLS = 5;

    /**
     * find_tools: one page of the skills that match the query, best first, each with the first
     * sentence of its description; the matches join the task's tools ({@link AgentContext#addTaskTools}),
     * so from the next step the model has their whole descriptions and parameters.
     */
    private String findTools(Map<String, Object> params, AgentContext context) {
        Object query = params.get("query");
        if (query == null || query.toString().isBlank()) {
            return "ERROR: 'query' is required: what the tool should do, in English keywords.";
        }
        int page = 1;
        Object asked = params.get("page");
        if (asked != null) {
            try {
                page = Math.max(1, (int) Double.parseDouble(asked.toString()));
            } catch (NumberFormatException e) {
                return "ERROR: 'page' is a number, from 1.";
            }
        }
        ToolFinder.Page found = ToolFinder.find(toolRegistry.all(), query.toString(), page);
        if (found.total() == 0) {
            return "No skill matches \"" + query + "\" among the " + toolRegistry.all().size()
                    + ". Try other words, or create one with skill_create.";
        }
        if (found.tools().isEmpty()) {
            return "\"" + query + "\" has " + found.total() + " matches, on pages 1 to " + found.pages()
                    + ": there is no page " + found.page() + ".";
        }
        // Offered from the next step -- where skills are offered by TOOLS messages at all.
        if (context.toolsAdded() != null) {
            context.addTaskTools(found.tools().stream().map(com.ownclaw.agent.tools.Tool::name).toList());
        }
        var sb = new StringBuilder();
        int first = (found.page() - 1) * ToolFinder.PAGE + 1;
        sb.append(found.total()).append(found.total() == 1 ? " skill matches \"" : " skills match \"")
          .append(query).append("\"");
        if (found.pages() > 1) {
            sb.append("; these are ").append(first).append(" to ").append(first + found.tools().size() - 1)
              .append(", best first");
        }
        // On local-first work -- unattended, or a chat that prefers cost -- the cloud calls no
        // skill: it names them to delegate.
        sb.append(context.offeredTools() != null
                ? ". They are for delegate's 'tools': you do not call them yourself:\n"
                : ". You can call them from your next step:\n");
        for (var tool : found.tools()) {
            sb.append("- ").append(tool.name()).append(": ").append(ToolFinder.gist(tool.description())).append('\n');
        }
        if (found.page() < found.pages()) {
            sb.append("More: find_tools with the same query and page ").append(found.page() + 1)
              .append(" (of ").append(found.pages()).append(").");
        }
        return sb.toString().strip();
    }

    /** What skill_manage can do: the cases of {@link #executeSkillManage}. */
    static final List<String> SKILL_MANAGE_ACTIONS = List.of("read", "delete", "list", "analyze");

    /**
     * Dispatch a skill_manage action to the appropriate SkillManager method.
     */
    private String executeSkillManage(Map<String, Object> params, AgentContext context) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String name = params.get("name") != null ? params.get("name").toString() : null;

        return switch (action) {
            case "read" -> skillManager.readSkill(name);
            case "delete" -> skillManager.deleteSkill(name);
            case "list" -> skillManager.listSkills();
            case "analyze" -> {
                LlmProvider provider = llmRouter.selectProvider(context);
                // While the call runs, its chat is shown what it is doing (AgentContext#call).
                try (LiveCall live = context.call(provider, llmRouter.isLocal(provider),
                        context.atStep("the analysis of the skill library"))) {
                    java.util.function.Supplier<String> analysis = () -> skillManager.analyzeSkills(provider,
                            context.egress("analyze"), live, accountOf(context));
                    // On the local model: work the task waits on, as its steps are.
                    yield llmRouter.isLocal(provider) ? lane.foreground(analysis) : analysis.get();
                }
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: " + String.join(", ", SKILL_MANAGE_ACTIONS);
        };
    }

    /**
     * Dispatch a credential_manage action to the CredentialVault.
     * Supports: list, check, store.
     */
    private String executeCredentialManage(Map<String, Object> params, String userId) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String key = params.get("key") != null ? params.get("key").toString().strip().toUpperCase() : null;
        // NOTE: a 'value' is deliberately NOT read. See the 'store' branch below.

        return switch (action) {
            case "list" -> {
                List<String> keys = credentialVault.listCredentialKeys(userId);
                if (keys.isEmpty()) {
                    yield "No credentials stored. " + credSetInstruction("KEY");
                }
                yield "Stored credentials: " + String.join(", ", keys);
            }
            case "check" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='check'";
                }
                boolean exists = credentialVault.hasCredential(userId, key);
                yield exists
                        ? "Credential '" + key + "' exists in the vault."
                        : "Credential '" + key + "' NOT found. " + credSetInstruction(key);
            }
            // Storing through this action is refused on purpose.
            //
            // The old flow was: ask_user for the password -> the user types it into chat ->
            // credential_manage(store, key, value). That put the secret in plaintext in
            // conversations (and the FTS index), sent it to the cloud model as the next task,
            // had the model echo it back as a parameter, replayed it in the action params of
            // every later step, folded it into the rolling summary, wrote 200 chars of it to
            // events, stored it in an episode, and showed it in the activity panel. Only the
            // vault copy was ever encrypted.
            //
            // /cred set writes straight to the vault, never reaches an LLM, and its command text
            // is not saved to the conversation. So the agent asks the user to run that instead.
            case "store" -> {
                String name = (key == null || key.isBlank()) ? "THE_KEY" : key;
                log.info("credential_manage(store) refused for key='{}' — directing user to /cred set", name);
                yield "Storing a credential through this action is disabled: it would send the secret "
                        + "through the model and leave it unencrypted in the chat history. " + credSetInstruction(name);
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: list, check";
        };
    }

    /**
     * How a credential gets into the vault: the owner types it himself with /cred set, which
     * writes it straight to the encrypted vault and never reaches a model. The one way this
     * action tells the model to ask for a credential -- the missing-key answer used to say "use
     * ask_user, then store it", which is how a password ends up typed into the chat.
     */
    private static String credSetInstruction(String key) {
        return "Ask the user to type this in the chat, which writes it straight to the encrypted "
                + "vault without the value passing through you:\n\n    /cred set " + key + " <value>\n\n"
                + "Then continue — the value is injected into skills that declare '" + key + "' "
                + "as a required credential. Do not ask the user to paste the value to you.";
    }

    /**
     * Dispatch a memory_manage action to the AgentMemory.
     * Supports: store, list, delete, recall.
     */
    private String executeMemoryManage(Map<String, Object> params, AgentContext context) {
        String userId = context.userId();
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String key = params.get("key") != null ? params.get("key").toString().strip() : null;
        String content = params.get("content") != null ? params.get("content").toString().strip() : null;

        return switch (action) {
            case "recall" -> recall(context, params.get("query") == null ? null : params.get("query").toString());
            case "list" -> {
                List<AgentMemory.MemoryEntry> facts;
                try {
                    facts = memory.getFacts(userId);
                } catch (Exception e) {
                    yield "ERROR: could not read the stored facts: " + e.getMessage();
                }
                if (facts.isEmpty()) {
                    yield "No facts stored. Use action='store' to save user preferences and instructions.";
                }
                var sb = new StringBuilder("Stored facts:\n");
                for (var fact : facts) {
                    String factKey = (fact.tags() != null && !fact.tags().isEmpty()) ? fact.tags().getFirst() : "?";
                    sb.append("- [" + factKey + "] " + fact.content() + "\n");
                }
                yield sb.toString();
            }
            case "store" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='store'. Use a short identifier like 'lunch_preference' or 'email_style'.";
                }
                if (content == null || content.isBlank()) {
                    yield "ERROR: 'content' parameter is required for action='store'. This is the fact or instruction to remember.";
                }
                try {
                    memory.storeFact(userId, key, content);
                    yield "Remembered: [" + key + "] " + content;
                } catch (Exception e) {
                    yield "ERROR: Failed to store fact: " + e.getMessage();
                }
            }
            case "delete" -> {
                if (key == null || key.isBlank()) {
                    yield "ERROR: 'key' parameter is required for action='delete'.";
                }
                try {
                    boolean deleted = memory.deleteFact(userId, key);
                    yield deleted
                            ? "Fact '" + key + "' deleted."
                            : "Fact '" + key + "' not found — nothing to delete.";
                } catch (Exception e) {
                    yield "ERROR: Failed to delete fact: " + e.getMessage();
                }
            }
            default -> "ERROR: Unknown action '" + action + "'. Use one of: store, list, delete, recall";
        };
    }

    /**
     * memory_manage action=recall: every past task of this user that has a word of the query,
     * whole, most relevant first. Asked for, where "Past Experience" used to put the three best
     * keyword matches into every task's prompt unasked -- unrelated tasks from other chats, and
     * failures filed as [SUCCESS]. The words that were not looked for are named, so "no match" is
     * never said of a word nobody looked for. Handles in the tasks named another task's results,
     * so they are written as words. What it returns, earlier tasks gave the cloud, and the task
     * records it as such ({@link AgentContext#givenEarlier}).
     */
    private String recall(AgentContext context, String query) {
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' parameter is required for action='recall': words that appear "
                    + "in the task to find.";
        }
        AgentMemory.Recall recalled;
        try {
            recalled = memory.recallEpisodes(context.userId(), query);
        } catch (Exception e) {
            return "ERROR: could not read past tasks: " + e.getMessage();
        }
        String skipped = recalled.skipped().isEmpty() ? ""
                : " (not looked for: " + String.join(", ", recalled.skipped())
                        + " — words and letters nearly every task has)";
        if (recalled.words().isEmpty()) {
            return "ERROR: '" + query + "' has no word to look for" + skipped + ". Ask with words "
                    + "the task itself would contain: a host, a name, a skill, a term.";
        }
        List<AgentMemory.MemoryEntry> found = recalled.episodes();
        if (found.isEmpty()) return "No past task matches '" + query + "'" + skipped + ".";
        var sb = new StringBuilder(found.size() + (found.size() == 1 ? " past task matches '" : " past tasks match '"))
                .append(query).append("'").append(skipped).append(", most relevant first:");
        for (int i = 0; i < found.size(); i++) {
            AgentMemory.MemoryEntry e = found.get(i);
            sb.append("\n\n--- ").append(i + 1).append(" of ").append(found.size()).append(", ")
              .append(java.time.Instant.ofEpochMilli(e.timestamp())).append(" ---\n")
              .append(e.content());
        }
        String past = TaskRecord.inWords(sb.toString());
        context.givenEarlier(past);
        return past;
    }

    /**
     * Dispatch a schedule_manage action to the ScheduledTaskService.
     * Supports: schedule_once, schedule_recurring, list, cancel, pause, resume.
     */
    private String executeScheduleManage(Map<String, Object> params, String userId) {
        String action = params.get("action") != null ? params.get("action").toString() : "";
        String description = params.get("description") != null ? params.get("description").toString().strip() : null;

        return switch (action) {
            case "schedule_once" -> {
                if (description == null || description.isBlank()) {
                    yield "ERROR: 'description' parameter is required — the task message to execute.";
                }
                String timeExpr = params.get("time") != null ? params.get("time").toString().strip() : null;
                if (timeExpr == null || timeExpr.isBlank()) {
                    yield "ERROR: 'time' parameter is required (e.g. 'in 30 minutes', 'tomorrow at 9am', 'at 14:30').";
                }
                var runAt = scheduledTaskService.parseTimeExpression(timeExpr);
                if (runAt.isEmpty()) {
                    yield "ERROR: Could not parse time expression: '" + timeExpr
                            + "'. Try: 'in N minutes/hours', 'tomorrow at HH:mm', 'at HH:mm'.";
                }
                try {
                    long id = scheduledTaskService.scheduleDeferred(userId, description, runAt.get());
                    yield "Scheduled one-shot task #" + id + " for " + runAt.get() + ": " + description;
                } catch (Exception e) {
                    yield "ERROR: Failed to schedule task: " + e.getMessage();
                }
            }
            case "schedule_recurring" -> {
                if (description == null || description.isBlank()) {
                    yield "ERROR: 'description' parameter is required — the task message to execute each time.";
                }
                String scheduleExpr = params.get("schedule") != null ? params.get("schedule").toString().strip() : null;
                if (scheduleExpr == null || scheduleExpr.isBlank()) {
                    yield "ERROR: 'schedule' parameter is required (e.g. 'every day at 11:00', 'every monday at 9am', 'every 30 minutes').";
                }
                var cronExpr = scheduledTaskService.parseScheduleExpression(scheduleExpr);
                if (cronExpr.isEmpty()) {
                    yield "ERROR: Could not parse schedule: '" + scheduleExpr
                            + "'. Try: 'every day at HH:mm', 'every N minutes', 'every <weekday> at HH:mm', or a raw Spring cron expression.";
                }
                Integer maxRuns = null;
                if (params.get("max_runs") != null) {
                    try {
                        maxRuns = Integer.parseInt(params.get("max_runs").toString());
                    } catch (NumberFormatException e) {
                        yield "ERROR: 'max_runs' must be an integer.";
                    }
                }
                try {
                    long id = scheduledTaskService.scheduleRecurring(userId, description, cronExpr.get(), maxRuns);
                    yield "Scheduled recurring task #" + id + " [" + cronExpr.get() + "]: " + description;
                } catch (Exception e) {
                    yield "ERROR: Failed to schedule recurring task: " + e.getMessage();
                }
            }
            case "list" -> {
                yield scheduledTaskService.formatTasksSummary(userId);
            }
            case "cancel" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.cancel(userId, taskId);
                yield ok ? "Task #" + taskId + " cancelled."
                         : "ERROR: Task #" + taskId + " not found or not cancellable.";
            }
            case "pause" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.pause(userId, taskId);
                yield ok ? "Task #" + taskId + " paused."
                         : "ERROR: Task #" + taskId + " not found or not pausable.";
            }
            case "resume" -> {
                long taskId = parseTaskId(params);
                if (taskId < 0) yield "ERROR: 'task_id' parameter is required (integer).";
                boolean ok = scheduledTaskService.resume(userId, taskId);
                yield ok ? "Task #" + taskId + " resumed."
                         : "ERROR: Task #" + taskId + " not found or not resumable.";
            }
            default -> "ERROR: Unknown action '" + action + "'. Use: schedule_once, schedule_recurring, list, cancel, pause, resume";
        };
    }

    private long parseTaskId(Map<String, Object> params) {
        Object val = params.get("task_id");
        if (val == null) return -1;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Detect repetitive tool calls and inject a delegation nudge into context metadata.
     *
     * When the cloud LLM has made 2+ consecutive calls to registered skills (non-special
     * tools), this suggests routine execution that the local model could carry instead.
     * <p>
     * Only on unattended work. The argument for delegating here is entirely about cloud tokens,
     * and it ignores the time the local model takes — which is free when nobody is waiting; when
     * someone is, the prompt states the local model's speed and the model weighs it.
     *
     * The nudge is picked up by ThinkingEngine's buildDynamicContext() and rendered
     * as a cost warning in the prompt.
     */
    private void injectDelegationNudge(AgentContext context) {
        // Only nudge if the local tier can actually take the work -- the same question the tools
        // decision asks, so it reuses the same per-task answer rather than opening its own HTTP
        // round trip on every step.
        if (!thinkingEngine.localTierReady(context)) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Never while someone is waiting. The nudge counts only cloud tokens, and on that axis
        // delegation is always the right answer -- but the local model writes about eight tokens
        // a second, so taking this advice in a live chat can trade seconds of cloud time for
        // minutes of waiting. There the prompt says where the local model is the right tool and
        // how fast it is, and that is what the model weighs.
        if (!context.isUnattended()) {
            context.metadata().remove("delegationNudge");
            return;
        }

        List<AgentTrajectory.Turn> turns = context.trajectory().turns();
        if (turns.size() < 2) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Count consecutive calls to registered skills (non-special actions) from the end
        int consecutive = 0;
        String repeatedTool = null;
        Set<String> recentSkills = new LinkedHashSet<>();
        for (int i = turns.size() - 1; i >= 0; i--) {
            AgentAction act = turns.get(i).action();
            if (act == null || act.isSpecialAction()) break; // stop at special actions
            String toolName = act.tool();
            // Only count registered tools (skills), not special actions
            if (toolRegistry.find(toolName).isEmpty()) break;
            recentSkills.add(toolName);
            consecutive++;
            if (consecutive == 1) repeatedTool = toolName;
        }

        if (consecutive < 2) {
            context.metadata().remove("delegationNudge");
            return;
        }

        // Build the nudge message
        String toolNames = String.join(", ", recentSkills);
        String nudge;
        if (recentSkills.size() == 1) {
            nudge = String.format(
                "You've called '%s' %d times in a row, which is what 'delegate' is for. "
                + "Bundle the remaining calls into one delegate action. Nobody is waiting for "
                + "this task, so the local model's minute-per-step costs you nothing and the "
                + "cloud tokens it saves are real.",
                repeatedTool, consecutive);
        } else {
            nudge = String.format(
                "You've made %d consecutive skill calls (%s) without needing reasoning between "
                + "them. Hand the rest to 'delegate'. Nobody is waiting for this task, so the "
                + "local model's minute-per-step costs you nothing and the cloud tokens it "
                + "saves are real.",
                consecutive, toolNames);
        }

        context.metadata().put("delegationNudge", nudge);
        log.info("Task {} delegation nudge: {} consecutive skill calls ({})",
                context.taskId(), consecutive, toolNames);
    }

    /**
     * Inject a reflection observation when the agent is struggling.
     * Triggers on consecutive failures OR consecutive hollow (empty-output) results.
     */
    private void injectReflection(AgentContext context, AgentAction lastAction) {
        int failures = context.trajectory().consecutiveFailures();
        int hollow = context.trajectory().consecutiveHollowResults();
        int trouble = Math.max(failures, hollow);

        // Also check overall failure ratio — catches non-consecutive waste patterns
        // (e.g., fail, succeed trivially, fail, succeed trivially, fail...). Over the steps the
        // model took, as the counts above are (AgentTrajectory#modelSteps).
        var steps = context.trajectory().modelSteps();
        int totalSteps = steps.size();
        int totalFailed = (int) steps.stream().filter(t -> !t.observation().success()).count();
        boolean highWasteRatio = totalSteps >= 6 && totalFailed * 2 > totalSteps;

        if (trouble < 2 && !highWasteRatio) return;

        String reflectionHint;
        if (highWasteRatio && trouble < 3) {
            // Many failures overall but not strictly consecutive — strategic pivot needed
            reflectionHint = "REFLECT: " + totalFailed + "/" + totalSteps
                    + " steps have failed. Your overall approach is ineffective. "
                    + "PIVOT STRATEGY: (1) Search the internet for how others solve this, "
                    + "(2) Try a completely different library/method/data source, "
                    + "(3) Simplify — deliver a partial result rather than failing completely. "
                    + "Do NOT retry what already failed.";
        } else if (trouble == 2) {
            reflectionHint = "REFLECT: " + trouble + "x " +
                    (failures >= 2 ? "failed" : "empty output") + ". " +
                    "Read skill code (skill_manage read), fix with skill_create (same name), or try different approach.";
        } else {
            reflectionHint = "REFLECT: " + trouble + "x consecutive " +
                    (failures >= trouble ? "failures" : "empty results") + ". " +
                    "STOP repeating. Try a fundamentally different approach: "
                    + "search the internet for solutions, use a different library, "
                    + "or simplify the task. If truly stuck, respond with what you have.";
        }

        // Record reflection so the ThinkingEngine sees it -- as a FAILURE, not a success: the
        // agent did not do anything here; this is the harness telling it that what it has been
        // doing is not working.
        //
        // Nor is it one of the model's steps (AgentTrajectory.Turn#byTheLoop), and what looks
        // backwards from the tail -- consecutiveFailures(), consecutiveHollowResults(), the
        // critic's repeated-action check -- skips it. Read as a step, it got in the way of each:
        // as a success it ended the runs they look for, so the "stop repeating yourself" hint was
        // itself what cleared the evidence of repetition; as a failure it ended a run of
        // identical calls, and added to a run of failures -- three real ones read as five.
        AgentAction reflectionAction = new AgentAction("_reflection", Map.of(), "System-injected reflection");
        AgentObservation reflectionObs = AgentObservation.failure("_reflection", reflectionHint, 0);
        context.trajectory().record(reflectionAction, reflectionObs);
    }

    /** The tool a message the owner sent while the task worked is recorded under: a step of the loop's own. */
    static final String FROM_THE_OWNER_STEP = "_message";

    /** What the model reads for a message the owner sent while the task worked: this, then his words whole. */
    static final String FROM_THE_OWNER = "Message from the user while you were working:\n";

    /**
     * Read what the owner sent in the task's chat since the last step ({@code TaskQueue#steer}),
     * before a step's think call -- so a message sent during a delegation is read once the
     * delegation has returned. Each message becomes a step the loop records itself, after
     * everything before it ({@link AgentTrajectory.Turn#byTheLoop}): every provider's prompt shows
     * it where it came, as {@link #FROM_THE_OWNER} and his words whole, and grows only at its end,
     * so what the provider cached of it stays cached. The canary takes his words as his own, as
     * it takes the task's message ({@link AgentContext#fromTheOwner}), and his chat records that
     * the task read them ({@link TaskChat#read}).
     */
    private void readMessages(AgentContext context, int step, boolean local) {
        com.ownclaw.core.Inbox inbox = context.inbox();
        List<com.ownclaw.core.UserMessage> read = inbox == null ? List.of() : inbox.drain();
        if (read.isEmpty()) return;
        for (var m : read) {
            context.fromTheOwner(m.text());
            context.trajectory().record(
                    new AgentAction(FROM_THE_OWNER_STEP, Map.of(), "A message the user sent while the task worked"),
                    AgentObservation.success(FROM_THE_OWNER_STEP, FROM_THE_OWNER + m.text(), Map.of(), 0));
        }
        context.markProgress();
        context.chat().read(step, read.stream().map(com.ownclaw.core.UserMessage::messageId).toList(), local);
        log.info("Task {} step {}: read {} message(s) the user sent while it worked",
                context.taskId(), step, read.size());
    }

    /**
     * Store the completed task as an episodic memory for future recall.
     */
    private void storeEpisode(AgentContext context, AgentResult result) {
        // A task waiting on an answer has no outcome yet. Storing it as success=false would
        // teach the memory layer that an approach failed when all that happened is that it
        // asked a question — the same lie as the COMPLETED it used to report, pointing the
        // other way. Nothing is recorded until the task actually ends.
        if (result.awaitingUser()) return;
        try {
            String summary = episodeSummary(context.originalMessage(), result);

            // Extract tool names used as tags
            List<String> tags = context.trajectory().turns().stream()
                    .map(t -> t.action().tool())
                    .filter(t -> t != null && !t.equals(AgentAction.RESPOND))
                    .distinct()
                    .collect(Collectors.toList());

            memory.storeEpisode(
                    context.userId(),
                    context.taskId(),
                    summary,
                    result.success(),
                    tags
            );
        } catch (Exception e) {
            log.warn("Could not store the episode of task {}: {}", context.taskId(), e.getMessage());
        }
    }

    /**
     * The text an episode is remembered by, whole. memory_manage recall hands it to later cloud
     * prompts, so it reads {@code response()}, never the owner's private text.
     */
    static String episodeSummary(String originalMessage, AgentResult result) {
        return "Task: " + originalMessage +
                "\nSteps: " + result.totalSteps() +
                "\nOutcome: " + result.terminationReason() +
                "\nResponse: " + result.response();
    }

    private void emitResult(AgentContext context, AgentResult result) {
        String userId = context.userId();
        int local = context.localTokens();
        int cloud = context.cloudTokens();

        // Build compact summary line with tokens included
        StringBuilder summary = new StringBuilder();
        if (result.success()) {
            summary.append(result.totalSteps()).append(" steps · ")
                    .append(TaskRecord.duration(result.totalDurationMs()));
        } else if (result.awaitingUser()) {
            summary.append("waiting for your answer");
        } else {
            summary.append(result.terminationReason());
        }
        // The cloud as what it cost: its tokens together are mostly cache reads, at a tenth of the
        // price, and the page shows them kind by kind under this line (tokenData).
        if (cloud > 0 || local > 0) {
            summary.append(" · ");
            if (cloud > 0) summary.append(String.format(Locale.ROOT, "$%.2f", context.cloudCostUsd())).append(" cloud");
            if (cloud > 0 && local > 0) summary.append(" + ");
            if (local > 0) summary.append(String.format("%,d", local)).append(" local tokens");
        }

        // Three outcomes, not two. A task that stopped to ask the user a question is neither
        // done nor broken, and showing it as FAILED is as wrong as the COMPLETED it used to show.
        StatusMessage.Type type = result.awaitingUser() ? StatusMessage.Type.NEED_INPUT
                : result.success() ? StatusMessage.Type.COMPLETED
                : StatusMessage.Type.FAILED;
        // Attributed to the task. A terminal status with no id is indistinguishable from any
        // other task's, and the frontend treats one as "the work is finished" -- so a background
        // digest completing would stop the spinner and close out the activity strip of an
        // interactive task still running, telling the user their question was done when it was
        // not. Everything else emitted from inside a task already carries the id; this, the one
        // that ends the UI's story, did not.
        statusEmitter.emitForTask(userId, context.taskId(), type, summary.toString(),
                tokenData(context));

        // Persist token usage to the events table for auditing
        try {
            String details = String.format(
                    "{\"cloudTokens\":%d,\"localTokens\":%d,\"steps\":%d,\"durationMs\":%d,\"reason\":\"%s\"}",
                    cloud, local, result.totalSteps(), result.totalDurationMs(), result.terminationReason());
            eventLog.log(userId, context.taskId(), "task_completed",
                    result.success() || result.awaitingUser() ? "info" : "warn",
                    context.originalMessage(),
                    details, cloud + local);
        } catch (Exception e) {
            log.warn("Failed to log token usage for task {}: {}", context.taskId(), e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** Add line numbers to code for precise error location in repair prompts. */
    private String numberCodeLines(String code) {
        if (code == null) return "";
        String[] lines = code.split("\n", -1);
        var sb = new StringBuilder(code.length() + lines.length * 5);
        for (int i = 0; i < lines.length; i++) {
            sb.append(String.format("%3d| %s\n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    /**
     * The task's running totals for status messages: its steps, and its tokens -- the cloud's
     * kind by kind with what they cost ({@link AgentContext#cloudUse}), which the page shows, and
     * all of them together, which Telegram does.
     */
    private Map<String, Object> tokenData(AgentContext context) {
        var steps = context.trajectory().steps();
        int totalSteps = steps.size();
        int successes = (int) steps.stream().filter(t -> t.observation().success()).count();
        return Map.of(
                "cloudTokens", context.cloudTokens(),
                "cloud", context.cloudUse(),
                "localTokens", context.localTokens(),
                "totalSteps", totalSteps,
                "successCount", successes
        );
    }

    // ── Skill code generation ──

    /** How many times a syntax error in generated code is sent back for repair. */
    private static final int SYNTAX_REPAIRS = 3;

    /**
     * Real calls to this skill that failed, as evidence for a repair: every recorded PUBLIC
     * failure, whole -- the same call failing the same way once, with how many times it did.
     * <p>
     * Returns null when there is nothing recorded. Parameters whose names say they are secrets
     * were redacted when they were stored, and only PUBLIC rows are read, so this is safe to put
     * in a prompt. Each row is a PUBLIC result the cloud was given in its own task
     * ({@link AgentContext#givenEarlier}), so a private result of this task that shares a run
     * with it -- a traceback's first line -- does not keep the request from being sent.
     * <p>
     * Deliberately evidence and not a test harness. Re-running these calls to check whether a
     * repair worked would be the obvious next step and it is not safe: replaying a recorded
     * invocation of a skill like {@code imap_move_to_trash_by_sender} would move real mail. The
     * only thing that could gate such a replay is the skill's own {@code has_side_effects} flag,
     * which the model that wrote the skill supplied — trusting a model's self-declaration to
     * decide whether it is safe to execute something is exactly the kind of judgement that fails
     * quietly on the case nobody thought about. Showing the failures to the model repairing the
     * code gets most of the benefit with none of that risk.
     */
    private String pastFailureEvidence(String skillName) {
        try {
            var failures = curatorService.failures(skillName);
            if (failures.isEmpty()) return null;
            var sb = new StringBuilder("Real calls to this skill that FAILED previously "
                    + "(parameters are redacted where they looked sensitive):\n");
            for (var f : failures) {
                long times = f.get("times") instanceof Number n ? n.longValue() : 1;
                sb.append("- called with: ").append(f.get("params_json"))
                  .append(times > 1 ? " (" + times + " times)" : "").append('\n')
                  .append("  failed with: ").append(f.get("error")).append('\n');
            }
            sb.append("Make sure the fixed code handles these cases.");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load failure history for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * What code generation gave: the skill's parameters with its code, or -- when there is none
     * -- why, as the ERROR line the step records. It used to return null for four different
     * causes, and the caller named one of them, "Cloud LLM unavailable": a cloud model that had
     * answered with code cut off at its output limit was reported to the owner as an outage.
     */
    record Codegen(Map<String, Object> params, String error) {
        static Codegen failed(String why) {
            return new Codegen(null, "ERROR: " + why);
        }

        static Codegen stopped(String name) {
            return failed("the task was stopped while the code for '" + name + "' was being "
                    + "written, so nothing was created.");
        }
    }

    /**
     * skill_create: the name checked, the code written by a model, then the skill made from it.
     * The name first ({@link SkillManager#nameRefusal}): the code cannot change it, and a name
     * refused after the code was written cost a whole code generation for nothing. Timed from
     * the first code call, so the step's duration is the code generation as much as the write --
     * a step that took four calls of two minutes each used to say 0ms.
     */
    private AgentObservation createSkill(AgentAction action, AgentContext context) {
        long startMs = System.currentTimeMillis();
        String result = skillManager.nameRefusal(str(action.params(), "name"));
        if (result == null) {
            Codegen code = generateSkillCode(action.params(), context);
            result = code.error() != null ? code.error() : skillManager.createSkill(code.params());
        }
        // The skill the model has just created is one of its tools from the next step.
        if (!result.startsWith("ERROR") && context.toolsAdded() != null) {
            context.addTaskTools(List.of(str(action.params(), "name")));
        }
        long durationMs = System.currentTimeMillis() - startMs;
        return result.startsWith("ERROR")
                ? AgentObservation.failure(action.tool(), result, durationMs)
                : AgentObservation.success(action.tool(), result, Map.of(), durationMs);
    }

    /**
     * Write a skill's Python on the model the task's calls go to ({@link LlmRouter#selectProvider}):
     * the cloud, or the local model -- local only, no cloud configured, or a cloud that could not
     * be reached, here or earlier in the task. The thinking model decides what the skill is; this
     * writes its code.
     * <p>
     * One loop over attempts: the first reply, then up to {@link #SYNTAX_REPAIRS} repairs of a
     * syntax error. A repair sends the specification and the latest attempt only, in the
     * specification's own message so the roles still alternate. Each used to append the reply and
     * a numbered copy of it to everything sent before, so the third repair resent both earlier
     * attempts twice over: 195,723 bytes for a 6,434-byte request. A reply that reached the
     * model's maximum output is refused -- never syntax-checked, never repaired: the file is
     * incomplete, and asking for the complete corrected code asks for the same length again.
     * <p>
     * Every call streams with the task's progress hook, so a long one is seen as alive and a
     * Stop ends it; between calls the stop is checked before the next is sent. Every call's
     * tokens are counted against the tier that did the work.
     */
    Codegen generateSkillCode(Map<String, Object> originalParams, AgentContext context) {
        String name = str(originalParams, "name");
        LlmProvider provider = llmRouter.selectProvider(context);
        boolean local = llmRouter.isLocal(provider);
        if (!provider.isAvailable()) {
            log.error("No model available to write the code of '{}'", name);
            return Codegen.failed("no model can write the code for '" + name + "': " + (local
                    ? "the local model is not available" : "neither the cloud model nor the local "
                    + "model is available") + ", so nothing was created.");
        }
        if (local) log.warn("The code of '{}' is written on the local model (degraded quality)", name);

        String description = str(originalParams, "description");
        String parameters = str(originalParams, "parameters");
        String requirements = str(originalParams, "requirements");

        // For existing skills: read old code and find last execution error for targeted fix
        String oldCode = skillManager.readSkillCode(name);
        String lastError = null;
        if (oldCode != null) {
            // Walk trajectory backwards to find the most recent failed execution of this skill
            var turns = context.trajectory().turns();
            for (int i = turns.size() - 1; i >= 0; i--) {
                var turn = turns.get(i);
                if (turn.action().tool().equals(name) && !turn.observation().success()) {
                    lastError = turn.observation().output();
                    break;
                }
            }
            // The trajectory only knows about failures in THIS task. A skill that broke last
            // week, in a different conversation, left nothing here — so the repair regenerated
            // blind, against an error it could not see. skill_usage now keeps the parameters
            // and the error of real failures, which is evidence rather than guesswork: the
            // exact calls that broke, so the fix can be aimed at them.
            String history = pastFailureEvidence(name);
            if (history != null) {
                context.givenEarlier(history);
                lastError = lastError == null ? history : lastError + "\n\n" + history;
            }
            log.info("Skill '{}' exists — will attempt targeted fix{}{}",
                    name,
                    lastError != null ? " (error available)" : " (no error known)",
                    history != null ? " + recorded failure history" : "");
        }

        String providerLabel = local ? "local (degraded)" : "cloud";
        statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                    oldCode != null ? "Fixing skill code · " + providerLabel : "Generating skill code · " + providerLabel,
                    tokenData(context));
        List<LlmMessage> spec = buildSkillCodePrompt(
                name, description, parameters, requirements, originalParams, context,
                oldCode, lastError, local);
        // 0.2 where the model takes a temperature: AnthropicProvider leaves it out for the models
        // that reject one -- Opus 4.7 and later and every 5.x model, claude-opus-5 among them --
        // and the local model is sent its own settings (OwnClawConfig.Executor#getThinking).
        // At the task's thinking effort, as the steps are: writing a skill is part of the task's
        // work. On the local model, low means the code is written without reasoning first.
        LlmRequestConfig codeGenConfig = new LlmRequestConfig(null, 0.2, false)
                .withEgress(context.egress("codegen"))
                .withEffort(context.options().effort());

        List<LlmMessage> prompt = spec;
        for (int attempt = 0; ; attempt++) {
            if (context.isCancelled()) return Codegen.stopped(name);
            if (attempt > 0) {
                statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                        "Repairing syntax error · " + providerLabel + " (attempt " + attempt + "/" + SYNTAX_REPAIRS + ")",
                        tokenData(context));
            }
            // The task's own hook goes with the call, and while it runs its chat is shown what it is
            // doing (AgentContext#call).
            LiveCall live = context.call(provider, local, context.atStep(
                    (attempt == 0 ? "the code of skill '" : "repairing the code of skill '") + name + "'"));
            LlmResponse reply;
            try {
                LlmProvider writer = provider;
                List<LlmMessage> asked = prompt;
                LlmRequestConfig asking = codeGenConfig.withProgress(live);
                java.util.function.Supplier<LlmResponse> call = () -> writer.chat(asked, asking);
                // On the local model, when the cloud is not available: work the task waits on.
                reply = local ? lane.foreground(call) : call.get();
            } catch (TaskCancellationService.TaskCancelledException stop) {
                return Codegen.stopped(name);
            } catch (ProviderRefused declined) {
                account(context, local, provider, declined.reply());
                return Codegen.failed("the model declined to write the code for '" + name + "' ("
                        + declined.getMessage() + "), so nothing was created.");
            } catch (OutputTruncated cutOff) {
                account(context, local, provider, cutOff.reply());
                return Codegen.failed(cutOff.limit() == OutputTruncated.Limit.MAX_OUTPUT
                        ? "the code for '" + name + "' did not fit in one reply (" + cutOff.getMessage()
                                + "). It was discarded, not repaired, and nothing was created. A "
                                + "smaller skill fits: one job per skill, or split this one into several."
                        : "the request for the code of '" + name + "' did not fit ("
                                + cutOff.getMessage() + "), so nothing was created.");
            } catch (EgressRefused refused) {
                return Codegen.failed("the request for the code of '" + name + "' was not sent ("
                        + refused.getMessage() + "), so nothing was created.");
            } catch (LlmException callFailed) {
                if (!local && callFailed.unreachable() && goesOnWithoutTheCloud(context, provider, callFailed)) {
                    if (!context.onLocal()) {
                        attempt--; // the same attempt, asked of the cloud once more
                        continue;
                    }
                    // Written from the start on the local model, whose prompt is its own.
                    live.close();
                    return generateSkillCode(originalParams, context);
                }
                if (callFailed instanceof RepeatedOutput looped) {
                    context.chat().repeated(looped, live, "the code of '" + name + "' was not written");
                }
                return Codegen.failed("the request for the code of '" + name + "' failed ("
                        + callFailed.getMessage() + "), so nothing was created.");
            } finally {
                live.close();
            }
            // A reply came back: the task is alive, whatever the reply holds.
            context.markProgress();
            if (!local) context.cloudAnswered();
            account(context, local, provider, reply);

            String code = extractPythonCode(reply.content());
            if (code == null || !code.contains("def run(")) {
                log.warn("Skill '{}': a reply of {} chars held {}", name,
                        reply.content() == null ? 0 : reply.content().length(),
                        code == null ? "no Python code" : "Python code without def run(params)");
                return Codegen.failed(code == null
                        ? "the reply for '" + name + "' held no Python code, so nothing was created."
                        : "the reply for '" + name + "' held Python code but no def run(params), which "
                                + "every skill needs, so nothing was created.");
            }
            if (Redactor.holdsRemovedSecret(code)) {
                log.warn("Skill '{}': the code holds a removed secret — refused.", name);
                return Codegen.failed("the code for '" + name + "' holds a removed secret, so nothing "
                        + "was created. " + REMOVED_SECRET);
            }
            String syntaxError = skillManager.checkPythonSyntax(code);
            if (syntaxError == null) {
                log.info("{} generated {} chars of skill code for '{}' ({} tokens)",
                        local ? "Local LLM (fallback)" : "Cloud LLM", code.length(), name, reply.totalTokens());
                Map<String, Object> enhanced = new HashMap<>(originalParams);
                enhanced.put("code", code);
                // The model may also name better requirements
                String cloudRequirements = extractRequirements(reply.content());
                if (cloudRequirements != null) {
                    enhanced.put("requirements", cloudRequirements);
                }
                return new Codegen(enhanced, null);
            }
            if (attempt == SYNTAX_REPAIRS) {
                log.error("Skill '{}' still has syntax errors after {} repair attempts: {}", name, SYNTAX_REPAIRS, syntaxError);
                return Codegen.failed("Python syntax error:\n" + syntaxError);
            }
            log.warn("Skill '{}' syntax error (repair attempt {}/{}): {}", name, attempt + 1, SYNTAX_REPAIRS, syntaxError);
            prompt = List.of(spec.get(0), LlmMessage.user(spec.get(1).content()
                    + "\n\nThe previous attempt failed the syntax check:\n" + syntaxError
                    + "\n\nNumbered code:\n" + numberCodeLines(code)
                    + "\n\nFix the error. Return the COMPLETE corrected code in a ```python fence."));
        }
    }

    /**
     * What one model call made for this task was billed for, counted once: the think, code,
     * analysis and delegation calls all come here -- the last two through {@link #accountOf} --
     * with their reply or with the one the exception that ended the call carries
     * ({@code LlmException.reply()}); and so, as it ends, does each of their attempts that ended
     * without a reply ({@link AgentContext#setBilledWithoutReply}). Billed
     * means input with the prompt cache's reads and writes -- Anthropic reports those apart from
     * {@code input_tokens}, and with the static system prompt cached they are most of the input --
     * plus output. The tokens go to the counter of the tier that did the work -- local ones billed
     * after the task's ending, to a row of their own ({@link #recordAfterEnd}) -- and a cloud call's
     * to the budget too, priced attempt by attempt at the rates of the model that ran each
     * ({@link ModelPricing#costUsd(String, LlmResponse)}): a declined request can be finished by a
     * fallback model, or retried on the one the refusal names. Local tokens never reach the cloud
     * budget: an outage that forced everything local used to spend the daily cloud allowance
     * fastest, without a single cloud call having been made.
     *
     * @param reply the reply, or null when none came -- what such a call's attempts were billed
     *              for has been counted as each ended
     * @return the tokens billed
     */
    private int account(AgentContext context, boolean local, LlmProvider provider, LlmResponse reply) {
        if (reply == null) return 0;
        int billed = reply.billedInputTokens() + reply.completionTokens();
        if (local) {
            if (billed > 0 && !context.addLocalTokens(billed)) recordAfterEnd(context, billed);
            return billed;
        }
        context.addCloudTokens(reply);
        if (billed > 0) {
            double cost = ModelPricing.costUsd(reply.model() != null ? reply.model() : provider.model(), reply);
            context.addCloudCost(cost);
            budgetTracker.recordUsage(context.userId(), provider.name(), billed, cost);
        }
        return billed;
    }

    /**
     * Local tokens billed after the task's ending was recorded -- a summary of one of its private
     * results whose reply came back just as the task ended, which ends the one under way
     * (TaskChat) -- as a row of their own, which the
     * owner's counts of local use read beside the ending's ({@code EventLogService},
     * {@code TaskTraceService}).
     */
    private void recordAfterEnd(AgentContext context, int localTokens) {
        try {
            eventLog.log(context.userId(), context.taskId(), EventLogService.TOKENS_AFTER_END, "info",
                    "Local tokens billed after the task ended", "{\"localTokens\":" + localTokens + "}",
                    localTokens);
        } catch (Exception e) {
            log.warn("Failed to log local tokens billed after task {} ended: {}", context.taskId(), e.getMessage());
        }
    }

    /**
     * The task's {@link #account}, for code outside this class that calls a model on the task's
     * behalf -- the library analysis and a delegation's local calls -- and hands it each reply
     * with the provider that served it.
     */
    private java.util.function.BiConsumer<LlmProvider, LlmResponse> accountOf(AgentContext context) {
        return (provider, reply) -> account(context, llmRouter.isLocal(provider), provider, reply);
    }

    /**
     * A think call the provider declined, whose reply or request did not fit a limit of the
     * model, or whose request the provider refused as it stands: the same request would end the
     * same way, so the task ends saying which -- a refusal in words ({@link TaskEnding#declined}).
     * A reply's tokens were billed all the same. A
     * request longer than the context window ends as that ({@link TaskEnding}), because the
     * next message in the chat is read with all of it.
     */
    private AgentResult noAnswer(AgentContext context, boolean local, LlmProvider provider,
                                 LlmResponse reply, LlmException why) {
        account(context, local, provider, reply);
        log.warn("Task {}: no usable reply from the model — {}", context.taskId(), why.getMessage());
        String clause;
        if (why instanceof ProviderRefused declined) {
            clause = TaskEnding.declined(declined);
        } else if (why.unreachable()) {
            clause = TaskEnding.unreachable(why, local, context.options().localOnly());
        } else {
            // A provider's own error can quote the request it refused.
            String said = quotable(why.getMessage(), context);
            clause = said.equals(why.getMessage()) ? said : "the call to the model failed (" + said + ")";
        }
        return why instanceof OutputTruncated cut && cut.limit() == OutputTruncated.Limit.CONTEXT_WINDOW
                ? AgentResult.contextWindow(clause, context.trajectory(), context.elapsedMs())
                : AgentResult.error(clause, context.trajectory(), context.elapsedMs());
    }

    /**
     * After a call to the cloud model that could not reach it ({@link LlmException#unreachable}):
     * whether the task goes on. The first of a task's calls in a row whose connection failed is
     * made again on the cloud -- a connection that broke off once is often back. Otherwise the
     * rest of the task runs on the local model, when it answers, with a note in the chat saying
     * why; when it does not, the task ends ({@link #noAnswer}). A reply it came with is billed.
     */
    private boolean goesOnWithoutTheCloud(AgentContext context, LlmProvider cloud, LlmException why) {
        if (why.connectionFailed() && context.countCloudConnectionFailure() == 1) {
            account(context, false, cloud, why.reply());
            log.warn("Task {}: the connection to the cloud model failed ({}); asking it once more",
                    context.taskId(), why.getMessage());
            return true;
        }
        if (!llmRouter.canGoLocal()) return false;
        account(context, false, cloud, why.reply());
        String because = TaskEnding.cloudUnreachable(why);
        log.warn("Task {}: the cloud model cannot be reached ({}: {}); the rest of the task runs on "
                + "the local model", context.taskId(), because, why.getMessage());
        context.goLocal(because);
        context.chat().onLocalModel(cloud.name(), because);
        return true;
    }

    /**
     * Build a specialized prompt for the cloud LLM to generate high-quality skill code: a system
     * message and one user message, the specification.
     *
     * <p>When {@code oldCode} is non-null, the prompt switches to "fix" mode:
     * the cloud LLM sees the existing code and the error, and is instructed to
     * make a targeted fix rather than regenerating from scratch.
     *
     * @param oldCode   the current Python code of the skill (null for new skills)
     * @param lastError the most recent execution error (null if unknown)
     * @param local     the local model writes it, the cloud being unavailable
     */
    private List<LlmMessage> buildSkillCodePrompt(
            String name, String description, String parameters,
            String requirements, Map<String, Object> originalParams, AgentContext context,
            String oldCode, String lastError, boolean local) {

        List<LlmMessage> messages = new ArrayList<>();

        // System prompt: expert Python code generator
        var sys = new StringBuilder();
        sys.append("Expert Python developer. Production-quality, first try.\n\n");
        sys.append("Contract: `def run(params)` → `{'output': str, 'success': bool}`. No unhandled exceptions.\n");
        sys.append("Always `def run(params):` — never keyword args. Use `params.get('key')`.\n");
        sys.append("```python\ndef run(params):\n    url = params.get('url', '')\n    resp = requests.get(url, timeout=30)\n    return {'success': True, 'output': resp.text}\n```\n");
        sys.append("Local env, full access. Credentials as env vars. system_packages on PATH.\n");
        sys.append("Files the user sent arrive as params['_attached_files']: a list of {'id','name',"
                + "'content_type','path','container_path'}. Open 'path'; inside a container open "
                + "'container_path'.\n");
        sys.append("Output: ```python fence + ```requirements fence.\n\n");
        sys.append("SELF-CHECK before outputting:\n");
        sys.append("1. All strings/f-strings properly closed (watch triple-quotes and nested quotes)\n");
        sys.append("2. All brackets/parens matched\n");
        sys.append("3. Consistent indentation (4 spaces, no tabs)\n");
        sys.append("4. `def run(params):` exists at module level\n");
        sys.append("5. Every code path returns {'output': str, 'success': bool}\n\n");

        if (oldCode != null) {
            sys.append("FIXING existing skill. Minimal targeted fix — preserve working parts.\n");
        } else {
            sys.append("Clean, efficient code. Established libraries. No unnecessary boilerplate.\n");
        }
        if (local) {
            sys.append("CRITICAL: You are a local model. Keep code SIMPLE. Use only stdlib + one "
                    + "well-known library. Avoid complex logic. Prefer straightforward imperative "
                    + "code over abstractions.\n");
        }

        messages.add(LlmMessage.system(sys.toString()));

        // User prompt: the skill specification (or fix request)
        var user = new StringBuilder();
        user.append(oldCode != null ? "Fix this skill:\n\n" : "Generate skill code:\n\n");
        user.append("Name: ").append(name).append(" | Params: ").append(parameters).append("\n");
        user.append("Description: ").append(description).append("\n");
        if (requirements != null && !requirements.isBlank()) {
            user.append("Pip: ").append(requirements).append("\n");
        }

        String credentials = str(originalParams, "credentials");
        if (credentials != null && !credentials.isBlank()) {
            user.append("Env vars (guaranteed present): ").append(credentials).append("\n");
        }

        if (oldCode != null) {
            user.append("\nBroken code:\n```python\n").append(oldCode).append("\n```\n");
            if (lastError != null && !lastError.isBlank()) {
                user.append("Error: ").append(lastError).append("\n");
            }
            user.append("Return complete fixed code.\n");
        }

        user.append("\nTask context: \"").append(context.originalMessage()).append("\"\n");

        messages.add(LlmMessage.user(user.toString()));

        return messages;
    }

    /**
     * Where a module starts, in a reply with no fence around its code: a line that begins with
     * an import, a def, a class, a decorator or a shebang. Prose such as "Here is the code from
     * the spec:" is not one.
     */
    private static final java.util.regex.Pattern CODE_START = java.util.regex.Pattern.compile(
            "(?m)^(?:import \\S|from \\S+ import |def |class |@|#!)");

    /**
     * Extract Python code from a model's reply: a ```python fence, a plain ``` fence holding
     * code, a ```python fence that is never closed, or -- with no fence -- everything from the
     * first line that starts a module.
     */
    private String extractPythonCode(String response) {
        if (response == null || response.isBlank()) return null;

        // Try to find ```python ... ``` fence (case-insensitive, handles ```Python too)
        java.util.regex.Matcher pyFence = java.util.regex.Pattern
                .compile("```[Pp]ython\\s*\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (pyFence.find()) {
            String code = pyFence.group(1).strip();
            if (!code.isBlank()) return code;
        }

        // Try plain ``` fence (first one)
        java.util.regex.Matcher plainFence = java.util.regex.Pattern
                .compile("```\\s*\n(.*?)```", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (plainFence.find()) {
            String code = plainFence.group(1).strip();
            if (!code.isBlank() && (code.contains("def run") || code.startsWith("import ") || code.startsWith("from "))) {
                return code;
            }
        }

        // Handle a ```python fence that is never closed. A reply cut off at the model's maximum
        // output never gets here -- the provider path refuses it (OutputTruncated) -- so this is
        // a model that left the fence open.
        java.util.regex.Matcher unclosedPy = java.util.regex.Pattern
                .compile("```[Pp]ython\\s*\n(.*)", java.util.regex.Pattern.DOTALL)
                .matcher(response);
        if (unclosedPy.find()) {
            String code = unclosedPy.group(1).strip();
            // Remove any trailing ``` fences from other blocks (e.g. ```requirements)
            int nextFence = code.indexOf("```");
            if (nextFence > 0) {
                code = code.substring(0, nextFence).strip();
            }
            if (!code.isBlank() && code.contains("def run")) {
                log.warn("Extracted {} chars of Python code from a ```python fence that was never closed.",
                        code.length());
                return code;
            }
        }

        // No fence: from the first line that starts a module -- every import with it, where
        // this used to start at the LAST import before def run and drop the ones above it, so
        // the skill loaded and then died with NameError on its first real call.
        java.util.regex.Matcher start = CODE_START.matcher(response);
        if (start.find()) {
            String candidate = response.substring(start.start()).strip();
            // Remove any trailing explanation after the code
            int trailingFence = candidate.indexOf("```");
            if (trailingFence > 0) {
                candidate = candidate.substring(0, trailingFence).strip();
            }
            if (!candidate.isBlank()) return candidate;
        }

        return null;
    }

    /**
     * Extract pip requirements from a cloud LLM response if it included a
     * ```requirements fence.
     */
    private String extractRequirements(String response) {
        if (response == null) return null;

        int start = response.indexOf("```requirements");
        if (start < 0) return null;

        start = response.indexOf('\n', start) + 1;
        int end = response.indexOf("```", start);
        if (end > start) {
            String reqs = response.substring(start, end).strip();
            return reqs.isBlank() ? null : reqs;
        }
        return null;
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    // ── Detail emission helpers (always-on activity panel enrichment) ──
    // Each status carries its task's id, as everything emitted inside a task should: without it
    // an unattended task's would go out unmarked (ChatStatusEmitter#BACKGROUND).

    /** Emit thinking step detail: user prompt messages (skip system), reasoning, chosen action. */
    private void emitThinkDetail(AgentContext context, ThinkResult result, int step, String provider) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "think");
        detail.put("step", step);
        detail.put("provider", provider);
        detail.put("tokens", result.totalTokens());

        // Collect user/assistant prompt messages (skip system — it repeats every step)
        var promptParts = new ArrayList<String>();
        for (var msg : result.promptMessages()) {
            if (msg.role() == LlmMessage.Role.SYSTEM) continue;
            promptParts.add("[" + msg.role().apiValue() + "] " + msg.content());
        }
        detail.put("prompt", String.join("\n---\n", promptParts));

        // LLM decision
        detail.put("tool", result.action().tool());
        detail.put("reasoning", result.action().reasoning());

        // The params, for every action but the answer
        if (!result.action().isResponse() && result.action().params() != null) {
            detail.put("params", result.action().params().toString());
        }

        statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                "💭 Think · Step " + step + " → " + result.action().tool(), detail);
    }

    /** Emit tool execution detail: tool name + input parameters. */
    private void emitActDetail(AgentContext context, AgentAction action, int step) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "act");
        detail.put("step", step);
        detail.put("tool", action.tool());
        if (action.params() != null && !action.params().isEmpty()) {
            var params = new LinkedHashMap<String, String>();
            for (var entry : action.params().entrySet()) {
                params.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "null");
            }
            detail.put("params", params);
        }
        statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.STEP,
                "⚡ Act · " + action.tool(), detail);
    }

    /**
     * Record an observation in trajectory AND emit detail to the frontend (for live stats). The
     * one place a step's observation is recorded, so the one place the tool calls its reply made
     * beyond the one that ran are added to it ({@link #callsNotRun}), and the one place the
     * owner's chat is told what the filter takes out of it ({@link TaskChat#filtered}).
     */
    private void recordAndEmitObservation(AgentContext context, AgentAction action,
                                           AgentObservation obs, int step) {
        String notRun = context.takeCallsNotRun();
        if (notRun != null) {
            obs = new AgentObservation(obs.tool(), obs.success(), obs.output() + "\n\n" + notRun,
                    obs.structured(), obs.durationMs());
        }
        context.trajectory().record(action, obs);
        emitObserveDetail(context.userId(), action, obs, step, context);
        persistStep(context, action, obs, step);
        // What the gateway's filter takes out of this result before the cloud reads it, said in
        // the owner's chat: the one place every step's result is recorded.
        context.chat().filtered(redactor.count(context.userId(), obs.output(), context.secretValues()));
    }

    /**
     * Write one row per step, so what a task did outlives the task.
     * <p>
     * The trajectory was in memory only — no INSERT anywhere in the codebase — so the durable
     * record of a multi-minute run was a single {@code task_completed} row plus the final chat
     * message. Ten minutes later nobody, including the agent, could answer "why did it do that"
     * or "what did that skill actually return", which is most of what "user insight into what is
     * happening is limited" means in practice.
     * <p>
     * No new table: {@code events} already has task_id, a JSON details column and an index on
     * (user_id, task_id), and json_extract is already used elsewhere. The severity carries the
     * outcome, so failed steps are greppable without parsing anything.
     * <p>
     * Parameters are deliberately NOT stored here. The tool sequence is what this is for —
     * seeing what a run did, and later noticing that the same sequence keeps succeeding, which
     * is the signal a capability is worth consolidating. Arguments would put far more of the
     * user's data in the database for no added signal, and where they genuinely are needed —
     * reproducing a failure — skill_usage already keeps them, redacted. The one exception is a
     * delegation's goal when the cloud wrote it: its own instructions to the local model, which
     * the owner reads on the task page and nowhere else.
     * <p>
     * Never allowed to break a task: a task that works but is not recorded is much better than a
     * task that dies because recording failed.
     */
    private void persistStep(AgentContext context, AgentAction action,
                             AgentObservation obs, int step) {
        try {
            eventLog.log(context.userId(), context.taskId(), "step",
                    obs.success() ? "info" : "warn",
                    action.tool() + (obs.success() ? " ok" : " FAILED")
                            + " (" + obs.durationMs() + "ms)",
                    JSON.writeValueAsString(stepDetails(context, action, obs, step)), 0);
        } catch (Exception e) {
            log.debug("Could not persist step {} of task {}: {}",
                    step, context.taskId(), e.getMessage());
        }
    }

    /** The skill an action names, when its name is one a skill can have; otherwise null. */
    static String skillOf(AgentAction action) {
        return action.params().get("name") instanceof String skill && SkillManager.isSkillName(skill)
                ? skill : null;
    }

    /** A step row's details. Claims the step's artifact on the context, so call it once. */
    static Map<String, Object> stepDetails(AgentContext context, AgentAction action,
                                           AgentObservation obs, int step) {
        var details = new LinkedHashMap<String, Object>();
        details.put("step", step);
        details.put("tool", action.tool());
        // Which skill a skill_create or skill_manage step was about: a name the cloud chose, not
        // the owner's data -- and without it no record could say which skill was built or failed.
        String skill = action.isSkillCreate() || action.isSkillManage() ? skillOf(action) : null;
        if (skill != null) details.put("skill", skill);
        // And what a skill_manage step did -- one of its actions, or nothing: a skill written and
        // then deleted was reported as kept.
        if (action.isSkillManage() && action.params().get("action") instanceof String what
                && SKILL_MANAGE_ACTIONS.contains(what)) {
            details.put("skillAction", what);
        }
        // A delegation's goal and the texts it was handed, whole, without the vault's values: the
        // task page shows them, so the chat need not (LocalExecutor says only that the local model
        // works). Only ones the cloud wrote, which the loop puts beside the delegation's result --
        // or beside why it was not started.
        if (action.isDelegate() && obs.structured() != null
                && obs.structured().get("goal") instanceof String goal) {
            details.put("goal", com.ownclaw.privacy.Redactor.scrubVault(goal, context.secretValues()).text());
        }
        if (action.isDelegate() && obs.structured() != null
                && obs.structured().get("texts") instanceof Map<?, ?> texts) {
            var scrubbed = new LinkedHashMap<String, String>();
            texts.forEach((name, text) -> scrubbed.put(String.valueOf(name),
                    com.ownclaw.privacy.Redactor.scrubVault(String.valueOf(text), context.secretValues()).text()));
            details.put("texts", scrubbed);
        }
        // And one that was refused before the local model was given anything, said as such.
        if (action.isDelegate() && obs.structured() != null
                && Boolean.TRUE.equals(obs.structured().get(NOT_STARTED))) {
            details.put(NOT_STARTED, true);
        }
        details.put("success", obs.success());
        details.put("durationMs", obs.durationMs());
        details.put("localTokens", context.localTokens());
        details.put("cloudTokens", context.cloudTokens());
        java.util.Optional<Artifact> claimed = java.util.Optional.empty();
        // Metadata only, same as the ledger: handle, label, size, hash, why. Never content.
        if (action.isDelegate()) {
            Object arts = obs.structured() == null ? null : obs.structured().get("artifacts");
            if (arts != null) {
                details.put("artifacts", arts);
                // The delegation listed its own; no later step may claim them again. Only
                // when it actually ran -- a delegate step that never reached the executor
                // recorded nothing, and claiming there would swallow an earlier artifact.
                context.claimAllArtifacts();
            }
        } else if (!action.isSpecialAction()) {
            // Only when THIS step recorded one. A refused, not-found or critic-blocked step
            // records nothing, and attributing the previous step's handle, label and hash to
            // it made the ops page say a tool ran that never did.
            //
            // Claimed, not counted. The first attempt compared the store's size against a
            // count the caller had just derived from that same store on the same thread:
            // one expression evaluated twice, always equal, so the guard excluded nothing
            // and the misattribution it was written to stop carried on unchanged.
            claimed = context.lastArtifact().filter(a -> context.claimArtifact(a.n()));
            claimed.ifPresent(a -> {
                details.put("artifact", a.handle());
                details.put("label", a.label().name());
                details.put("chars", a.output().length());
                details.put("sha256_16", com.ownclaw.llm.CloudGateway.sha256_16(a.output()));
                if (!a.why().isEmpty()) details.put("why", a.why());
            });
        }
        // A delegation's failure text is its own words plus whatever the local model and its
        // server said, which after a private read can quote that data -- so then, none. Nor
        // when the text holds anything the gateway would refuse to send: a PUBLIC step can fail
        // quoting a file a delegation wrote private data into.
        boolean withhold = action.isDelegate() && context.localTierReadPrivate()
                || context.firstLeakIn(obs.output()) != null;
        stepOutcome(details, obs, claimed, withhold, context.secretValues());
        return details;
    }

    /**
     * What a step's row says about how it went, beyond success: whether its result is indexed
     * for the canary, whether the skill reported a failure the loop counted as success, and --
     * for a failed step -- how it failed, whole.
     * <p>
     * reportedFailure is always written, so a row without it is one from before it existed and
     * the page can say "not recorded" instead of reading its absence as "no".
     * <p>
     * The reason is kept locally, shown to the owner, and read by later tasks in the record of
     * this one (TaskRecord), so it must not hold what the gateway would keep from the cloud:
     * vault values are scrubbed out, and there is none at all for a PRIVATE step, or when the
     * caller says to withhold it (see stepDetails). Without it a failure's reason reached only
     * the log and the model -- the 2026-09-24 "context window full" delegation left a row that
     * said FAILED and nothing else.
     */
    static void stepOutcome(Map<String, Object> details, AgentObservation obs,
                            java.util.Optional<Artifact> claimed, boolean withhold,
                            Map<String, String> secrets) {
        claimed.ifPresent(a -> details.put("indexed", a.indexed()));
        boolean reported = claimed.map(a -> a.success() && !a.succeeded()).orElse(false);
        details.put("reportedFailure", reported);
        boolean privateText = withhold || claimed.map(Artifact::isPrivate).orElse(false);
        if ((!obs.success() || reported) && !privateText) {
            String text = Redactor.scrubVault(obs.output(), secrets).text();
            details.put("reason", text == null ? "" : text);
        }
    }

    /**
     * An attachment's row: which file, what it is and how it is labelled -- never its text, and
     * not its name, which can say what the file holds (a statement's carries its account
     * number): the ops API reads these rows. The owner's task page looks the name up by the
     * file's id ({@code TaskTraceController}).
     */
    static Map<String, Object> attachmentDetails(Artifact a) {
        var d = new LinkedHashMap<String, Object>();
        d.put("artifact", a.handle());
        d.put("tool", a.tool());
        d.put("fileId", a.written().get("fileId"));
        d.put("label", a.label().name());
        d.put("chars", a.output().length());
        d.put("indexed", a.indexed());
        if (!a.why().isEmpty()) d.put("why", a.why());
        return d;
    }

    /** Emit observation detail: success/fail status, duration, output preview. */
    private void emitObserveDetail(String userId, AgentAction action,
                                    AgentObservation obs, int step, AgentContext context) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("category", "observe");
        detail.put("step", step);
        detail.put("tool", action.tool());
        detail.put("success", obs.success());
        detail.put("durationMs", obs.durationMs());
        detail.put("output", obs.output());

        // Stats snapshot
        var steps = context.trajectory().steps();
        int totalSteps = steps.size();
        int successes = (int) steps.stream().filter(t -> t.observation().success()).count();
        detail.put("totalSteps", totalSteps);
        detail.put("successCount", successes);
        detail.put("cloudTokens", context.cloudTokens());
        detail.put("cloud", context.cloudUse());
        detail.put("localTokens", context.localTokens());
        detail.put("elapsedMs", context.elapsedMs());

        String status = obs.success() ? "✓" : "✗";
        statusEmitter.emitForTask(userId, context.taskId(), StatusMessage.Type.STEP,
                "👁 Observe · " + action.tool() + " " + status + " " + TaskRecord.duration(obs.durationMs()),
                detail);
    }

    // ── Debug helpers ──

    private void emitDebug(String userId, String text) {
        statusEmitter.emit(userId, StatusMessage.Type.DEBUG, text);
    }

    /**
     * Emit the full prompt and raw LLM response for a thinking step.
     */
    private void emitDebugPrompt(String userId, ThinkResult result, int step) {
        var sb = new StringBuilder();
        sb.append("### Step ").append(step).append(" — Thinking\n\n");

        sb.append("**Prompt messages** (").append(result.promptMessages().size()).append("):\n");
        for (var msg : result.promptMessages()) {
            sb.append("\n---\n**[").append(msg.role().name()).append("]**\n");
            sb.append(msg.content()).append('\n');
        }

        sb.append("\n---\n**Raw LLM output** (").append(result.totalTokens()).append(" tokens):\n```json\n");
        String raw = result.rawLlmOutput();
        sb.append(raw != null ? raw : "(null)").append("\n```\n");

        sb.append("**Parsed action**: tool=`").append(result.action().tool())
                .append("` reasoning=").append(result.action().reasoning());

        emitDebug(userId, sb.toString());
    }

    // ── Stall watchdog ──

    /**
     * End the model call each running task that now reads as stopped is waiting on. Run after
     * every stop request (TaskCancellationService#onRequest): the task hears the stop on the next
     * event of the reply, and a call that sends nothing -- a local model still loading, a cloud
     * call before its first event -- has no next event.
     */
    private void interruptStopped() {
        for (AgentContext ctx : inFlight.values()) {
            if (ctx.isCancelled()) ctx.interruptCall();
        }
        // And the local model's summary under way, when its task is stopped.
        lane.interruptStopped();
    }

    /**
     * Tasks currently inside the loop, so the stall watchdog can see them: a task that hangs
     * hangs INSIDE a step -- in a tool call, or a model call that never returns -- and while it
     * does, no check on its own thread runs.
     */
    private final Map<String, AgentContext> inFlight = new ConcurrentHashMap<>();

    /**
     * Whether a task has stalled long enough to be stopped.
     *
     * @param alreadyStopped a stopped task has not reached a point that notices it yet; stopping
     *                       it again does not make it notice sooner
     */
    static boolean shouldCancelForStall(long idleMs, long stallTimeoutMs, boolean alreadyStopped) {
        if (alreadyStopped) return false;
        if (stallTimeoutMs <= 0) return false;   // disabled
        return idleMs > stallTimeoutMs;
    }

    /**
     * Stop tasks that have stopped making progress.
     *
     * <p>Runs on the scheduler, not on the task's own thread, which is the whole point. Progress
     * is a step finishing, a skill's progress report, any event of a model's reply as it streams
     * in, or a model call ending -- and the time a model call is under way is not counted at all
     * ({@link AgentContext#msSinceLastProgress}): its own timeouts bound it, and a local model
     * reading a long prompt sends nothing for many minutes while it works. A task idle past the
     * stall timeout is marked stalled with the facts ({@link AgentContext#stall}): it then reads
     * as stopped wherever it checks -- the top of the next step, the supplier a tool polls, the
     * next model call -- unwinds like a task the owner stopped, and ends STALLED saying why.
     *
     * <p>Cooperative: a tool that does not poll runs until it returns. Interrupting threads
     * mid-call instead would risk a half-written skill directory or a dangling sandbox process.
     */
    @Scheduled(fixedDelay = 30_000L)
    public void cancelStalledTasks() {
        long stallTimeoutMs = config.getTasks().getStallTimeout() * 1000L;
        for (var entry : inFlight.entrySet()) {
            AgentContext ctx = entry.getValue();
            long idle = ctx.msSinceLastProgress();
            if (!shouldCancelForStall(idle, stallTimeoutMs, ctx.isCancelled())) continue;
            log.warn("Task {} has made no progress for {}s (limit {}s): the stall watchdog is stopping it.",
                    entry.getKey(), idle / 1000, stallTimeoutMs / 1000);
            ctx.stall("no progress for " + TaskRecord.duration(idle) + " — no step finished, no model "
                    + "call was under way and no skill reported progress — and the limit is "
                    + TaskRecord.duration(stallTimeoutMs));
            statusEmitter.emitForTask(ctx.userId(), entry.getKey(), StatusMessage.Type.WARNING,
                    "No progress for " + (idle / 1000) + "s — stopping this task.");
            lane.interruptStopped();
        }
    }

    /**
     * One scheduler for every heartbeat in the process.
     * <p>
     * This used to be created per call, and only the ScheduledFuture was returned. Cancelling a
     * future does not shut down the executor that owns it, so each LLM call and each tool call
     * left a live {@code llm-heartbeat} thread parked forever. On a server that runs two
     * scheduled tasks a day plus interactive chat, at tens of steps a task, that is
     * thousands of threads and their stacks — an ordinary day's work would eventually exhaust
     * the process. Nothing surfaced it because the threads are daemons and idle.
     * <p>
     * A shared pool makes cancel() sufficient: the future stops, the threads stay and are reused.
     */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "llm-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * Start a periodic heartbeat that emits PROGRESS status messages while a tool call is
     * running. Keeps the UI activity indicator alive so users know the system isn't hung. A model
     * call is shown by its own live state instead ({@link #watch}).
     *
     * @param context     the task the call is made for, whose user is sent the messages
     * @param description what's happening (e.g. "Running openwrt_run")
     * @return a ScheduledFuture to cancel when the call completes
     */
    private ScheduledFuture<?> startToolHeartbeat(AgentContext context, String description) {
        ScheduledExecutorService scheduler = HEARTBEAT_SCHEDULER;
        long[] startMs = { System.currentTimeMillis() };
        return scheduler.scheduleAtFixedRate(() -> {
            long elapsed = (System.currentTimeMillis() - startMs[0]) / 1000;
            String time;
            if (elapsed < 60) {
                time = elapsed + "s";
            } else {
                time = (elapsed / 60) + "m " + (elapsed % 60) + "s";
            }
            // Emit as PROGRESS so the frontend updates the last step label
            // rather than adding a new step entry.
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.PROGRESS,
                    description + " (" + time + ")");
        }, 30, 20, TimeUnit.SECONDS);    // first tick at 30s, then every 20s
    }

    private void stopHeartbeat(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    // ── What a model call is doing, as the owner is shown it ──

    /**
     * How often the chat of a task is sent the live state of its model call under way: every 15
     * seconds, and once as the call begins and once as it ends. Often enough that the owner sees
     * the call move -- the local model writes a few hundred characters in that time -- and no
     * chat is flooded: a frame takes the place of the one before it on the page, and is never
     * saved.
     */
    static final long LIVE_EVERY_MS = 15_000;

    /** How often a call's live state is sent: {@link #LIVE_EVERY_MS}, which a test shortens. */
    volatile long liveEveryMs = LIVE_EVERY_MS;

    /**
     * The task's {@link AgentContext#setCallWatch}: while {@code call} runs, its chat is sent the
     * call's live state ({@link LiveCall#shown}) as it begins and every {@link #liveEveryMs}, as a
     * LIVE status the page shows in place of the one before -- marked as unattended work's for a
     * scheduled run, as every status of one is ({@link ChatStatusEmitter#inBackground}); once it
     * has ended, how long it ran, and nothing after that. The sends take turns, so the one saying
     * it ended is the last.
     */
    private Runnable watch(AgentContext context, LiveCall call) {
        Object turn = new Object();
        showLive(context, call.shown());
        ScheduledFuture<?> beat = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            synchronized (turn) {
                LiveCall.Shown shown = call.shown();
                if (!shown.ended()) showLive(context, shown);
            }
        }, liveEveryMs, liveEveryMs, TimeUnit.MILLISECONDS);
        return () -> {
            beat.cancel(false);
            synchronized (turn) {
                showLive(context, call.shown());
            }
        };
    }

    /** One live frame for the task's chat; a frame that cannot be sent costs only itself. */
    private void showLive(AgentContext context, LiveCall.Shown shown) {
        try {
            statusEmitter.emitForTask(context.userId(), context.taskId(), StatusMessage.Type.LIVE,
                    shown.text(), Map.of("live", shown.data()));
        } catch (RuntimeException e) {
            log.warn("Task {}: the live state of its model call could not be sent: {}",
                    context.taskId(), e.getMessage());
        }
    }

    /**
     * What a page that connects is told of the task's model call under way, as the live frame it
     * would otherwise wait for says it; null while none is ({@link ChatStatusEmitter#doing}).
     */
    private StatusMessage liveStatus(AgentContext context) {
        LiveCall call = context.liveCall();
        if (call == null) return null;
        LiveCall.Shown shown = call.shown();
        return shown.ended() ? null : new StatusMessage(StatusMessage.Type.LIVE, shown.text(),
                Map.of("live", shown.data()), context.taskId());
    }

    /**
     * The model call under way of a task running now, as the ops API shows it
     * ({@link LiveCall#forOps}), or null when the task is not running or is between calls.
     */
    public Map<String, Object> liveCallOf(String taskId) {
        AgentContext context = inFlight.get(taskId);
        LiveCall call = context == null ? null : context.liveCall();
        return call == null ? null : call.forOps();
    }
}
