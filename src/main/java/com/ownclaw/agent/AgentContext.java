package com.ownclaw.agent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Carries all contextual information for a single agent execution.
 * This is the unified state object threaded through the agent loop.
 */
public class AgentContext {

    private final String userId;
    private final String taskId;
    private final String originalMessage;
    private final AgentTrajectory trajectory;
    private final Map<String, Object> metadata;
    private final long startTimeMs;
    /**
     * When the task last moved: a step finished, an event of a model's reply arrived, a model
     * call ended, or a skill reported its progress. See {@link #msSinceLastProgress}.
     */
    private volatile long lastProgressMs;

    /**
     * True when nobody is waiting for this task — the scheduler submitted it, or the user
     * explicitly sent it to the background. It is a fact about ORIGIN, not a guess: the
     * scheduler genuinely has no one watching. What it buys is permission to spend local
     * inference time, which is free but slow, where nobody is waiting for it.
     */
    private boolean unattended;

    /** What the stall watchdog stopped this task on, or null. See {@link #stall}. */
    private volatile String stalled;
    /** How to end the model call this task is waiting on, or null. See {@link #progress}. */
    private volatile Runnable callInFlight;
    /** Authoritative external cancellation source (the Stop button). See {@link #isCancelled()}. */
    private volatile java.util.function.BooleanSupplier externalCancel;
    /** Counts what a model call's attempt without a reply was billed for. See {@link #setBilledWithoutReply}. */
    private volatile java.util.function.Consumer<com.ownclaw.llm.LlmResponse.Usage> billedWithoutReply = usage -> { };
    private String conversationSummary;
    private String userPreferences;

    /** Deterministic capability hint from CapabilityResolver (null if no gap detected). */
    private CapabilityResolver.CapabilityHint capabilityHint;

    /** Credential keys available in the vault for this user (loaded once at task start). */
    private List<String> credentialKeys = List.of();

    /** See {@link #setPersonalSources}. */
    private List<String> personalSources = com.ownclaw.config.OwnClawConfig.Privacy.DEFAULT_PERSONAL_SOURCES;

    /** Null until a step restricts the set; see {@link #offeredTools()}. */
    private volatile java.util.Set<String> offeredTools;

    /** Null until the local tier is checked; see {@link #localTierReady()}. */
    private volatile Boolean localTierReady;

    // Per-task token usage counters, and what the cloud calls cost
    private int localTokens;
    private int cloudTokens;
    private double cloudCostUsd;
    /** See {@link #closeCounters}. */
    private boolean countersClosed;

    /** Where the task reports its progress; see {@link TaskChat}. */
    private volatile TaskChat chat = TaskChat.NONE;

    /** See {@link #setCallsNotRun}. */
    private String callsNotRun;

    /**
     * The files sent with this message, as registered: each one this user's, each a PRIVATE
     * artifact. See {@link #addFile}.
     */
    private final List<Artifact> files = new java.util.ArrayList<>();

    // ── the task's results, and what may be said about them ──

    /** Every result this task has produced, numbered {{1}}, {{2}} ... — the bytes live here only. */
    private final List<Artifact> artifacts = new java.util.ArrayList<>();
    /** The canary index over every PRIVATE artifact's bytes. */
    private final com.ownclaw.privacy.PrivateIndex privateIndex = new com.ownclaw.privacy.PrivateIndex();
    /** Decrypted secret vault values, by key — decrypted once at task start, scrubbed at the door. */
    private Map<String, String> secretValues = Map.of();

    /** See {@link #setSkillSource}. */
    private Function<String, String> skillSource = n -> null;

    public AgentContext(String userId, String taskId, String originalMessage) {
        this.userId = userId;
        this.taskId = taskId;
        this.originalMessage = originalMessage;
        this.trajectory = new AgentTrajectory();
        this.metadata = new HashMap<>();
        this.startTimeMs = System.currentTimeMillis();
        this.lastProgressMs = this.startTimeMs;
    }

    public String userId() { return userId; }
    public String taskId() { return taskId; }
    public String originalMessage() { return originalMessage; }
    public AgentTrajectory trajectory() { return trajectory; }
    public Map<String, Object> metadata() { return metadata; }
    public long startTimeMs() { return startTimeMs; }

    public long elapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /** Mark forward progress (resets stall timer). */
    public void markProgress() { this.lastProgressMs = System.currentTimeMillis(); }

    /**
     * Milliseconds since the last forward progress -- what the stall watchdog reads -- and none
     * while a model call is under way ({@link #progress}): a request sent and not yet returned,
     * or a wait before trying one again. Such a call is bounded by its own timeouts, and Ollama
     * sends nothing, not even its headers, while it loads the model and reads the prompt: at the
     * hundred tokens a second the production host reads, a result of 60,000 tokens is ten minutes
     * of silence from a model that is working. Counted, that silence was the stall limit, and a
     * task that had handed a long result to its local model ended STALLED while the model read it.
     */
    public long msSinceLastProgress() {
        return callInFlight != null ? 0 : System.currentTimeMillis() - lastProgressMs;
    }

    public boolean isUnattended() { return unattended; }
    public void setUnattended(boolean unattended) { this.unattended = unattended; }

    /**
     * Whether this task should stop: the stall watchdog stopped it, or the external source the
     * Stop button and the ops API write to says so.
     * <p>
     * Every check inside a step reads this -- the per-step check in {@code LocalExecutor}, the
     * {@code context::isCancelled} supplier handed to every tool, the {@link #progress} hook of
     * the task's model calls -- so a stop reaches a running step wherever that step checks, not
     * only the top of the next one. A skill does not check: it runs until it returns.
     */
    public boolean isCancelled() {
        if (stalled != null) return true;
        java.util.function.BooleanSupplier ext = externalCancel;
        return ext != null && ext.getAsBoolean();
    }

    /**
     * Attach the authoritative cancellation source for this task, normally
     * {@code () -> cancellationService.isCancelled(userId, taskId, startTimeMs)}. Set once at task
     * start.
     */
    public void setExternalCancel(java.util.function.BooleanSupplier supplier) { this.externalCancel = supplier; }

    /**
     * Attach what counts the tokens of a model call's attempt that ended without a reply --
     * stopped part-way, or cut by a timeout, a dropped connection or an error event -- which the
     * call's hook is told of ({@link com.ownclaw.llm.LlmProgress#billed}). Set once at task start,
     * to the loop's own accounting, so they are counted as a reply's tokens are.
     */
    public void setBilledWithoutReply(java.util.function.Consumer<com.ownclaw.llm.LlmResponse.Usage> account) {
        this.billedWithoutReply = account;
    }

    /**
     * The stall watchdog's stop, with the facts it stopped on: from here {@link #isCancelled()}
     * is true, and the task ends STALLED saying them. The watchdog stops no task while a model
     * call of it is under way ({@link #msSinceLastProgress}), but one can begin as it stops the
     * task: that call is ended too ({@link #interruptCall}), or at once when it hands over its
     * cancel ({@link #progress}). The first facts are kept.
     */
    public void stall(String facts) {
        if (stalled == null) stalled = facts;
        interruptCall();
    }

    /**
     * End the model call this task is waiting on, if it is waiting on one. A stop is otherwise
     * heard on the next event of the reply ({@link #progress}), and a call that sends nothing --
     * Ollama loading the model and reading the prompt, a cloud call before its first event, a
     * call waiting minutes to try again after an overload -- has no next event: it ran until its
     * read timeout, most of an hour for Ollama, or to the end of its wait.
     */
    public void interruptCall() {
        Runnable cancel = callInFlight;
        if (cancel != null) cancel.run();
    }

    /** What the stall watchdog stopped this task on, or null when it has not. */
    public String stalled() { return stalled; }

    /**
     * The hook for every model call made on this task's behalf ({@code withProgress}): each event
     * of the streamed reply is progress, and the time a call is under way is not counted as
     * silence at all ({@link #msSinceLastProgress}), so the stall watchdog never takes a call
     * that is still reading or answering for a stalled task; its end is progress too. Once the
     * task has been stopped, the next event ends the call by throwing
     * {@code TaskCancelledException}, instead of the stop waiting minutes for it. While the call
     * runs its cancel is kept here, so a stop ends it before any event too
     * ({@link #interruptCall}); one stopped before the call was under way ends it at once. What
     * an attempt that ended without a reply was billed for is counted
     * ({@link #setBilledWithoutReply}).
     */
    public com.ownclaw.llm.LlmProgress progress() {
        return new com.ownclaw.llm.LlmProgress() {
            @Override
            public void onProgress() {
                markProgress();
                if (isCancelled()) throw new com.ownclaw.core.TaskCancellationService.TaskCancelledException(taskId);
            }

            @Override
            public void calling(Runnable cancel) {
                // Marked before the call is let go, so the watchdog never reads the call gone
                // and the clock from before it.
                if (cancel == null) markProgress();
                callInFlight = cancel;
                if (cancel != null && isCancelled()) cancel.run();
            }

            @Override
            public void billed(com.ownclaw.llm.LlmResponse.Usage usage) {
                billedWithoutReply.accept(usage);
            }
        };
    }

    public String conversationSummary() { return conversationSummary; }
    public void setConversationSummary(String summary) { this.conversationSummary = summary; }

    public String userPreferences() { return userPreferences; }
    public void setUserPreferences(String prefs) { this.userPreferences = prefs; }

    public CapabilityResolver.CapabilityHint capabilityHint() { return capabilityHint; }
    public void setCapabilityHint(CapabilityResolver.CapabilityHint hint) { this.capabilityHint = hint; }

    // ── Credential keys ──

    /**
     * The tool names offered to the model on the current step, or null for "no restriction".
     * <p>
     * Set by {@link ThinkingEngine} each step and read by {@link AgentLoop} before a registry
     * tool runs. It exists because withholding a tool from the provider's tools array is not
     * enforcement: the text protocol is still parsed, {@code tryParseAction} accepts any name,
     * and the loop resolves it straight off the full registry. This is the one place the
     * restriction becomes structural rather than advisory.
     */
    /**
     * Whether the local tier is usable, decided once for this task, or null if not yet asked.
     * <p>
     * A fact settled at task origin, like {@link #isUnattended()} and {@link #credentialKeys()}.
     * It was being re-probed on every reasoning step, which is a synchronous HTTP round trip on
     * the hot path — and worse, the answer feeds the tools array, which sits inside the
     * Anthropic cache prefix. One blipped probe mid-task would change the array, invalidate the
     * whole prefix and re-bill six figures of cached tokens at full rate, while handing the
     * cloud the registry for one step and taking it away the next.
     * <p>
     * Nothing is lost by deciding once. A local tier that dies mid-task fails its next
     * delegation, and that failed turn is exactly what restores the registry for the rest of
     * the task.
     */
    public Boolean localTierReady() { return localTierReady; }
    public void setLocalTierReady(boolean ready) { this.localTierReady = ready; }

    public java.util.Set<String> offeredTools() { return offeredTools; }
    public void setOfferedTools(java.util.Set<String> names) { this.offeredTools = names; }

    public List<String> credentialKeys() { return credentialKeys; }
    public void setCredentialKeys(List<String> keys) { this.credentialKeys = keys != null ? keys : List.of(); }

    /**
     * The credential key prefixes that mark a personal-content source
     * ({@code ownclaw.privacy.personal-sources}), set from the configuration at task start.
     */
    public void setPersonalSources(List<String> prefixes) {
        this.personalSources = prefixes == null ? List.of() : List.copyOf(prefixes);
    }

    /**
     * Whether a skill that needs these credentials reads a personal-content source: one of the
     * keys starts with a {@link #setPersonalSources} prefix -- IMAP_ and POP3_ by default, which
     * is reading mail.
     */
    public boolean readsPersonalSource(List<String> requiredCredentials) {
        if (requiredCredentials == null) return false;
        for (String key : requiredCredentials) {
            if (key == null) continue;
            String k = key.toUpperCase(java.util.Locale.ROOT);
            for (String prefix : personalSources) {
                if (k.startsWith(prefix.toUpperCase(java.util.Locale.ROOT))) return true;
            }
        }
        return false;
    }

    // ── Token tracking ──

    // Synchronized: the local model's summaries of private results are counted from their own
    // thread (TaskChat), beside the task's.

    /**
     * Count local tokens on the task -- unless its ending has been written ({@link #closeCounters}):
     * then they are not counted here, and the caller records them apart.
     *
     * @return whether they were counted
     */
    public synchronized boolean addLocalTokens(int tokens) {
        if (countersClosed) return false;
        this.localTokens += tokens;
        return true;
    }
    public synchronized void addCloudTokens(int tokens) { this.cloudTokens += tokens; }
    public synchronized int localTokens() { return localTokens; }
    public synchronized int cloudTokens() { return cloudTokens; }

    /**
     * The task is ending: from now on its counters stay as its ending reads and records them. A
     * summary of a private result can still be written after it (TaskChat), and its tokens are
     * then not added here ({@link #addLocalTokens}).
     */
    public synchronized void closeCounters() { countersClosed = true; }

    /** What a cloud call made for this task cost, in USD, at the rates ModelPricing knows. */
    public synchronized void addCloudCost(double usd) { this.cloudCostUsd += usd; }
    /** What this task's cloud calls have cost so far, in USD; a progress message's header shows it. */
    public synchronized double cloudCostUsd() { return cloudCostUsd; }

    // ── Progress ──

    /** The chat this task reports its progress in; {@link TaskChat#NONE} when it has none. */
    public TaskChat chat() { return chat; }
    public void setChat(TaskChat chat) { this.chat = chat == null ? TaskChat.NONE : chat; }

    /**
     * What the model is told of the tool calls its last reply made beyond the one a step runs,
     * or null: the loop sets it after the step is decided and adds it to the observation the
     * step records ({@code AgentLoop.recordAndEmitObservation}), once.
     */
    void setCallsNotRun(String note) { this.callsNotRun = note; }

    /** Whether there is a {@link #setCallsNotRun} note still to be taken. */
    boolean hasCallsNotRun() { return callsNotRun != null; }

    /** {@link #setCallsNotRun}'s note, taken: null after the first time. */
    String takeCallsNotRun() {
        String note = callsNotRun;
        callsNotRun = null;
        return note;
    }

    // ── File attachments ──

    /**
     * Register a file sent with this message: a PRIVATE artifact, and one of the task's files.
     * <p>
     * PRIVATE whoever is watching: a file sent to this assistant is not a file sent to the
     * cloud, and a statement or a contract is exactly what should not go there. The text of a
     * text upload is indexed for the canary; a file that is not text has none, and
     * {@link #decide} is its guard.
     */
    public synchronized Artifact addFile(String fileId, String text, List<String> why) {
        Artifact a = addArtifact("attachment", Map.of("fileId", fileId), Map.of("fileId", fileId),
                text == null ? "" : text, true,
                new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE, why));
        files.add(a);
        return a;
    }

    /** The files registered for this task, in order — a read-only view. */
    public List<Artifact> files() {
        return java.util.Collections.unmodifiableList(files);
    }

    /**
     * The ids of the registered files: what every skill is handed as {@code _attached_files}.
     * <p>
     * Derived from {@link #files()} rather than set beside it, so the list a skill is given and
     * the list {@link #decide} labels by are one list and cannot drift apart. An id that was
     * not registered -- missing, or another user's file -- is never handed to a skill.
     */
    public List<String> attachmentIds() {
        return files.stream().map(f -> String.valueOf(f.written().get("fileId"))).toList();
    }

    /**
     * What a skill run for this task is handed, the loop's call and a delegation's alike: the
     * stop to poll, the task's files, and where its progress reports go. Each report is progress
     * for the stall watchdog ({@link #markProgress}) and then shown, as {@code shown} shows it.
     * One rule for both paths: a report counted on one of them only let the watchdog stop a scan
     * the owner watched report its progress for ten minutes, and tell him nothing had moved.
     */
    public com.ownclaw.agent.tools.ToolExecutionContext toolContext(
            com.ownclaw.sandbox.SandboxManager.ProgressCallback shown) {
        return new com.ownclaw.agent.tools.ToolExecutionContext(userId, taskId, null, this::isCancelled,
                (message, percent) -> {
                    markProgress();
                    shown.onProgress(message, percent);
                },
                attachmentIds());
    }

    // ── artifacts ──

    /**
     * Record a result as the next artifact of this task and return it, numbered.
     * <p>
     * Numbering is task-wide and never resets: {@code {{3}}} means one thing to the cloud's
     * descriptor, the egress ledger and the events row. (Inside a delegation the local model
     * counts only that delegation's results, from {{1}}.) A PRIVATE artifact's bytes are indexed
     * for the canary here — unless the decision says it is PRIVATE only for when it was made; a
     * PUBLIC one's never are, because they may go.
     */
    public synchronized Artifact addArtifact(String tool, Map<String, Object> written,
                                             Map<String, Object> resolved, String output,
                                             boolean success, Artifact.Decision decision) {
        Artifact a = new Artifact(artifacts.size() + 1, tool, written, resolved, output, success,
                decision.label(), decision.why(), decision.indexed());
        artifacts.add(a);
        if (a.isPrivate() && a.indexed()) privateIndex.addPrivate(a.n(), a.output());
        return a;
    }

    /**
     * The label for a result about to be recorded — one place, for both paths.
     * <p>
     * From the call's own facts ({@link Artifact#labelFor}: a personal-content source, a PRIVATE
     * result pulled in); then, inside a delegation whose local model has read private data
     * ({@code tainted}), PRIVATE regardless, because anything it typed from then on can carry
     * what it read. Such a result is not indexed for the canary, and neither is one that is
     * PRIVATE only because it pulled in such a result: its bytes are the same public page one hop
     * on, and indexing them is what made the cloud's own later fetch of that page trip the
     * canary. In a task holding a file every result is PRIVATE and unindexed -- so the cloud sees
     * each as a handle, a kind and a size.
     * <p>
     * Last, the bytes themselves: a result its own facts call PUBLIC whose {@code output} repeats
     * a PRIVATE one ({@link #firstLeakIn}) is PRIVATE -- "repeats {{N}}" -- and unindexed, like
     * every result that is PRIVATE only because of what it carries from another: the run it
     * repeats is {{N}}'s, and {{N}} is indexed. It is the question the gateway asks of each part
     * it scans, asked here first. Shown to the cloud, the result would have been refused at the
     * door on the next step and the task would have ended there; labelled, the cloud reads a
     * description of it and the task goes on.
     * <p>
     * A run is 32 characters, and some runs are nobody's in particular: a Python traceback opens
     * with "Traceback (most recent call last)", which is one window, and web pages share their
     * standard head. After a credentialed skill has failed with a traceback, or returned a page,
     * a public result carrying the same boilerplate repeats it, and is withheld whole: the cloud
     * is told that the public skill failed but not why, and cannot repair it from the error. The
     * same collision used to end the task at the door.
     *
     * @param output the result's text, as it will be recorded
     */
    public Artifact.Decision decide(List<String> requiredCredentials, List<Artifact> used,
                                    boolean tainted, String output) {
        boolean personal = readsPersonalSource(requiredCredentials);
        Artifact.Decision own = Artifact.labelFor(personal, used);
        if (own.label() == com.ownclaw.privacy.Label.PUBLIC) {
            if (tainted) {
                return new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE,
                        List.of("after private data in this delegation"), false);
            }
            // Every skill run in a task holding a file is handed it, and can reach it without a
            // reference -- through _attached_files, or by opening the uploads directory itself --
            // so what it returns is the file's as far as anyone can tell.
            if (!files.isEmpty()) {
                return new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE,
                        List.of(givenTheFiles()), false);
            }
            com.ownclaw.privacy.PrivateIndex.Hit repeated = firstLeakIn(output);
            if (repeated != null) {
                return new Artifact.Decision(com.ownclaw.privacy.Label.PRIVATE,
                        List.of("repeats {{" + repeated.handle() + "}}"), false);
            }
            return own;
        }
        // Not indexed on a file task, a personal source's result or not: an indexed result's
        // descriptor shows its JSON key names and booleans, which for a statement parser are the
        // statement. Every skill is handed the file, a mail reader too, so its keys can be the
        // file's data. References still resolve; the cloud forwards the whole {{N}} instead of a
        // field. (What the local model writes after reading such a result is checked against it
        // all the same: LocalExecutor.recordAnswer.)
        boolean unindexed = !files.isEmpty() || (!personal && used != null
                && used.stream().filter(Artifact::isPrivate).noneMatch(Artifact::indexed));
        return unindexed ? new Artifact.Decision(own.label(), own.why(), false) : own;
    }

    /** "given the file {{1}}" -- the reason, by handle, never by name. */
    private String givenTheFiles() {
        return (files.size() == 1 ? "given the file " : "given the files ")
                + String.join(", ", files.stream().map(Artifact::handle).toList());
    }

    /**
     * Whether a delegation in this task has had its local model read private data. A later
     * delegation starts from that: the local model can have written what it read into a file or
     * a note, and the next delegation reading it back would otherwise see a PUBLIC result.
     */
    private volatile boolean localTierReadPrivate;

    public boolean localTierReadPrivate() {
        return localTierReadPrivate;
    }

    public void markLocalTierReadPrivate() {
        localTierReadPrivate = true;
    }

    /**
     * Every artifact so far, in handle order — a read-only VIEW, not a copy, so a delegation
     * that records results sees them in the same list it resolves references against.
     */
    public List<Artifact> artifacts() {
        return java.util.Collections.unmodifiableList(artifacts);
    }

    /** The most recently recorded artifact — the one the step just executed. */
    /**
     * True once: for the step that recorded this artifact, and no later step.
     * <p>
     * {@code attributedThrough} is the highest artifact number already reported in the ops log.
     * An artifact belongs to exactly one step. Without the mark, a step that recorded nothing —
     * a tool not found, a critic block — inherited the previous step's handle, label and hash,
     * and the ops page named a tool that had never run.
     */
    private int attributedThrough = 0;

    public synchronized boolean claimArtifact(int n) {
        if (n <= attributedThrough) return false;
        attributedThrough = n;
        return true;
    }

    /** Claim everything recorded so far — a delegation reports its own artifacts. */
    public synchronized void claimAllArtifacts() {
        attributedThrough = lastArtifact().map(Artifact::n).orElse(attributedThrough);
    }

    public synchronized java.util.Optional<Artifact> lastArtifact() {
        return artifacts.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(artifacts.get(artifacts.size() - 1));
    }

    /** See {@link #markShown}. */
    private final java.util.Set<Integer> shown = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The task's answer or question shows this result in full: the one it placed, and each local
     * answer it carries for the owner ({@code AgentLoop.answerFor}). The ending of a task waiting
     * for an answer then lists it as shown above instead of repeating it.
     */
    public void markShown(Artifact a) { shown.add(a.n()); }

    /** Whether the task's answer or question shows this result in full. See {@link #markShown}. */
    public boolean isShown(Artifact a) { return shown.contains(a.n()); }

    public Map<String, String> secretValues() { return secretValues; }
    public void setSecretValues(Map<String, String> values) {
        this.secretValues = values == null ? Map.of() : Map.copyOf(values);
    }

    /**
     * The first run of an indexed PRIVATE artifact in a result that {@link Excuses} does not
     * excuse, or null -- what the gateway would refuse to send. One question, over one index
     * and one set of excuses: {@link #decide} asks it of every result before it is labelled, and
     * the gateway of each part it scans, with what {@link #egress} hands it.
     * <p>
     * The door sees the result in its frame ({@link AgentTrajectory.Turn#observationText}: a line
     * naming the tool and how it went, then the output, then whatever follows it). The line
     * breaks around the output are whitespace: the label scans the result with a space at each
     * end ({@code PrivateIndex.firstLeakInResult}) and the scan strips whitespace from what it
     * asks about, so the label and the door see the same windows of the result and excuse them
     * alike. What can still set them apart is a coincidence at the output's two edges: a window
     * that takes in a visible character of the frame (the ')' ending the line before the output,
     * the first character of whatever follows it) is refused at the door if a private result has
     * that character in that place too, though the label passed the result -- a collision, like
     * any other the door refuses. And the door reads what the privacy filter left of the result:
     * a run that holds an identifier or a secret is not in what it sends, so the door can pass a
     * short repeat the label withholds -- the label errs on the private side, never the other.
     *
     * @param output the result's text, as it will be recorded
     */
    public com.ownclaw.privacy.PrivateIndex.Hit firstLeakIn(String output) {
        return privateIndex.firstLeakInResult(output, new Excuses());
    }

    /**
     * {@link #firstLeakIn}, asked of {@code output} against the PRIVATE results among
     * {@code sources}, indexed for the canary or not: the first run of one of them that
     * {@link Excuses} does not excuse, or null. What the local model writes after reading private
     * data goes to the cloud only when this finds nothing in it ({@code LocalExecutor.recordAnswer}),
     * and what it read includes results the canary does not index -- the text a skill read out of
     * a PDF the owner sent.
     */
    public com.ownclaw.privacy.PrivateIndex.Hit firstRunOf(List<Artifact> sources, String output) {
        var index = new com.ownclaw.privacy.PrivateIndex();
        for (Artifact a : sources) {
            if (a.isPrivate()) index.addPrivate(a.n(), a.output());
        }
        return index.firstLeakInResult(output, new Excuses());
    }

    /** See {@link #givenEarlier}. */
    private final List<String> fromEarlierTasks = new java.util.ArrayList<>();

    /**
     * Text an earlier task gave the cloud, which this task is handing it again: the past tasks
     * memory_manage recall returns -- a task's message and its response, the cloud's copy and
     * never the owner's private one -- and a skill's recorded PUBLIC failures, in the request for
     * its repair. A source of {@link Excuses} like the message: a past answer or a past
     * public traceback that shares a run with a private result of this task was the cloud's to
     * read before this task began. "Traceback (most recent call last" is a window of every Python
     * traceback, so once a credentialed skill had failed with one, recalling a task that had
     * failed so too ended the task at the next request, and the repair of a skill whose recorded
     * failures held one was refused.
     */
    public synchronized void givenEarlier(String text) {
        if (text != null && !text.isEmpty()) fromEarlierTasks.add(text);
    }

    /**
     * Whether a stretch of normalised text the canary matched is material the cloud was already
     * given, and may go: true when one source below holds the whole of it. The label asks it of a
     * result ({@link #firstLeakIn}) and the gateway of each part it scans ({@link #egress}).
     * <p>
     * Four sources count. What the task started with — the message, the conversation summary,
     * the preferences — and what earlier tasks gave the cloud that this one hands it again
     * ({@link #givenEarlier}). The output of every PUBLIC artifact recorded BEFORE the private
     * one that hit; the order matters there. What the CLOUD itself wrote —
     * its own tool-call arguments and reasoning, as typed (its placeholders put back by the
     * gateway), where they reach a part the gateway
     * scans: a result that echoes an argument it was given (which would otherwise make the next
     * prompt unsendable), the code generator's request, the correction after a reply that could
     * not be parsed, the OpenAI history. Its own turns, which the Anthropic renderer replays as
     * JSON, are assistant parts and are not scanned at all. And the SOURCE of the skill that
     * produced the hit artifact: a Python traceback quotes the line that threw, so a credentialed
     * skill's failure would otherwise make its own repair prompt — the loop this project exists
     * for — impossible. A public artifact recorded after a private one can be that private
     * content laundered — a skill that echoes what it was given,
     * a summary the local model wrote — and whitelisting it would let the leak through as
     * "already public". The smtp confirmation that quotes the public digest it just sent is the
     * case the order exists to allow; a public result quoting a private one is the case it
     * exists to refuse.
     * <p>
     * A scan asks once per stretch, not once per window ({@code PrivateIndex.firstLeakIn}), and
     * reads each source normalised once for all the stretches it asks about: each is normalised
     * the first time it is needed and kept after that. An Excuses is made per label and per
     * request to the cloud, so a source costs one normalisation per request, not one per window.
     * Kept by identity, since nothing recorded changes while a request is made; what is recorded
     * later is read when it is first met. Normalising every source again for every window made
     * labelling a quoted public digest quadratic in its length.
     */
    private final class Excuses implements java.util.function.BiPredicate<Integer, String> {
        private final Map<Object, String> normalised = new java.util.IdentityHashMap<>();
        private final Map<String, String> skillSources = new HashMap<>();

        private boolean holds(Object source, String stretch) {
            if (source == null) return false;
            return normalised.computeIfAbsent(source,
                    s -> com.ownclaw.privacy.PrivateIndex.normalise(String.valueOf(s)))
                    .contains(stretch);
        }

        @Override
        public boolean test(Integer hitHandle, String stretch) {
            if (stretch == null || stretch.isEmpty()) return false;
            synchronized (AgentContext.this) {
                for (Object given : new Object[] {originalMessage, conversationSummary,
                        userPreferences}) {
                    if (holds(given, stretch)) return true;
                }
                for (String given : fromEarlierTasks) {
                    if (holds(given, stretch)) return true;
                }
                for (Artifact a : artifacts) {
                    if (a.n() >= hitHandle) break;
                    if (!a.isPrivate() && holds(a.output(), stretch)) return true;
                }
                for (var turn : trajectory.turns()) {
                    var action = turn.action();
                    if (action == null) continue;
                    if (holds(action.reasoning(), stretch)) return true;
                    for (Object v : action.params().values()) {
                        if (holds(v, stretch)) return true;
                    }
                }
                Artifact hit = hitHandle >= 1 && hitHandle <= artifacts.size()
                        ? artifacts.get(hitHandle - 1) : null;
                return hit != null && skillSources.computeIfAbsent(hit.tool(), tool -> {
                    String source = skillSource.apply(tool);
                    return source == null ? "" : com.ownclaw.privacy.PrivateIndex.normalise(source);
                }).contains(stretch);
            }
        }
    }

    /**
     * How to read a skill's source, for the clause above. Supplied once per task by the loop;
     * the default answers nothing, so a context built without it is no more permissive.
     */
    public void setSkillSource(Function<String, String> reader) {
        this.skillSource = reader == null ? n -> null : reader;
    }

    /**
     * What a cloud call made on behalf of this task carries to the door: its excuses are read
     * once for every part of the call.
     */
    public com.ownclaw.llm.EgressContext egress(String purpose) {
        return new com.ownclaw.llm.EgressContext(userId, taskId, purpose, privateIndex,
                secretValues, new Excuses(), this::toolOf);
    }

    /** The tool that produced result {@code n}, or null when there is no such result. */
    private synchronized String toolOf(int n) {
        return n >= 1 && n <= artifacts.size() ? artifacts.get(n - 1).tool() : null;
    }
}
