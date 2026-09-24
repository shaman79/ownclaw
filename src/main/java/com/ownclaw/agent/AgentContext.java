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
    /** Timestamp of the last forward progress (step completion, LLM response, etc.). */
    private volatile long lastProgressMs;

    /**
     * True when nobody is waiting for this task — the scheduler submitted it, or the user
     * explicitly sent it to the background. It is a fact about ORIGIN, not a guess: the
     * scheduler genuinely has no one watching. What it buys is permission to spend local
     * inference time, which is free but slow, on work that would otherwise be truncated.
     */
    private boolean unattended;

    private volatile boolean cancelled;
    /** Authoritative external cancellation source (the Stop button). See {@link #isCancelled()}. */
    private volatile java.util.function.BooleanSupplier externalCancel;
    private String conversationSummary;
    private String userPreferences;

    /** Deterministic capability hint from CapabilityResolver (null if no gap detected). */
    private CapabilityResolver.CapabilityHint capabilityHint;

    /** Credential keys available in the vault for this user (loaded once at task start). */
    private List<String> credentialKeys = List.of();

    /** Null until a step restricts the set; see {@link #offeredTools()}. */
    private volatile java.util.Set<String> offeredTools;

    /** Null until the local tier is checked; see {@link #localTierReady()}. */
    private volatile Boolean localTierReady;

    // Per-task token usage counters
    private int localTokens;
    private int cloudTokens;

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
        this.cancelled = false;
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

    /** Milliseconds since the last forward progress. */
    public long msSinceLastProgress() { return System.currentTimeMillis() - lastProgressMs; }

    /**
     * Whether this task should stop, consulting both the local flag and the external source
     * the Stop button writes to.
     * <p>
     * This used to read the local flag only, and nothing ever called {@link #cancel()} — zero
     * callers repo-wide — so it was permanently false for the lifetime of every task. The main
     * loop polls {@code cancellationService} itself at the top of each step, so Stop appeared to
     * work; what was dead was everything <em>inside</em> a step. The per-step check in
     * {@code LocalExecutor} and the {@code context::isCancelled} supplier handed to every tool
     * could never fire, so a running tool or an in-flight local call — 60 to 133 seconds on this
     * deployment, and up to ten of them in a delegated plan — carried on to completion after the
     * user pressed Stop.
     * <p>
     * Consulting the external source here revives all of those checks at once, rather than
     * relying on each caller to remember to poll two places.
     */
    public boolean isUnattended() { return unattended; }
    public void setUnattended(boolean unattended) { this.unattended = unattended; }

    public boolean isCancelled() {
        if (cancelled) return true;
        java.util.function.BooleanSupplier ext = externalCancel;
        return ext != null && ext.getAsBoolean();
    }

    public void cancel() { this.cancelled = true; }

    /**
     * Attach the authoritative cancellation source for this task, normally
     * {@code () -> cancellationService.isCancelled(userId)}. Set once at task start.
     */
    public void setExternalCancel(java.util.function.BooleanSupplier supplier) { this.externalCancel = supplier; }

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

    // ── Token tracking ──

    public void addLocalTokens(int tokens) { this.localTokens += tokens; }
    public void addCloudTokens(int tokens) { this.cloudTokens += tokens; }
    public int localTokens() { return localTokens; }
    public int cloudTokens() { return cloudTokens; }
    public int totalTokens() { return localTokens + cloudTokens; }

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
     * From the call's own facts ({@link Artifact#labelFor}); then, inside a delegation whose local
     * model has read private data ({@code tainted}), PRIVATE regardless, because anything it
     * typed from then on can carry what it read. Such a result is not indexed for the canary, and
     * neither is one that is PRIVATE only because it pulled in such a result: its bytes are the
     * same public page one hop on, and indexing them is what made the cloud's own later fetch of
     * that page trip the canary. In a task holding a file every result is PRIVATE and, unless it
     * needed credentials, unindexed -- so the cloud sees each as a handle, a kind and a size.
     */
    public Artifact.Decision decide(List<String> requiredCredentials, List<Artifact> used,
                                    boolean tainted) {
        Artifact.Decision own = Artifact.labelFor(requiredCredentials, used);
        boolean credentials = requiredCredentials != null && !requiredCredentials.isEmpty();
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
            return own;
        }
        // Not indexed on a file task, credentials or not: text read out of a PDF is nothing the
        // canary holds, so indexing it guards nothing -- and an indexed result's descriptor shows
        // its JSON key names and booleans, which for a statement parser are the statement. Every
        // skill is handed the file, a credentialed one too, so its keys can be the file's data.
        // References still resolve; the cloud forwards the whole {{N}} instead of a field.
        boolean unindexed = !files.isEmpty() || (!credentials && used != null
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

    public com.ownclaw.privacy.PrivateIndex privateIndex() { return privateIndex; }

    public Map<String, String> secretValues() { return secretValues; }
    public void setSecretValues(Map<String, String> values) {
        this.secretValues = values == null ? Map.of() : Map.copyOf(values);
    }

    /**
     * Whether a canary hit is material the cloud was already given, and may go.
     * <p>
     * Four sources count. What the task started with — the message, the conversation summary,
     * the preferences, the recalled memories. The output of every PUBLIC artifact recorded
     * BEFORE the private one that hit; the order matters there. What the CLOUD itself wrote —
     * its own tool-call arguments and reasoning, which the Anthropic renderer replays verbatim
     * as assistant turns: a skill that echoes an argument it was given would otherwise make the
     * next prompt unsendable. And the SOURCE of the skill that produced the hit artifact: a
     * Python traceback quotes the line that threw, so a credentialed skill's failure would
     * otherwise make its own repair prompt — the loop this project exists for — impossible. A public artifact recorded after a
     * private one can be that private content laundered — a skill that echoes what it was given,
     * a summary the local model wrote — and whitelisting it would let the leak through as
     * "already public". The smtp confirmation that quotes the public digest it just sent is the
     * case the order exists to allow; a public result quoting a private one is the case it
     * exists to refuse. Computed on demand: hits are rare.
     */
    public synchronized boolean isAllowedLeak(int hitHandle, String normalisedWindow) {
        if (normalisedWindow == null || normalisedWindow.isEmpty()) return false;
        Function<String, String> n = com.ownclaw.privacy.PrivateIndex::normalise;
        for (String given : new String[] {originalMessage, conversationSummary, userPreferences,
                String.valueOf(metadata.get("relevantMemories"))}) {
            if (given != null && n.apply(given).contains(normalisedWindow)) return true;
        }
        for (Artifact a : artifacts) {
            if (a.n() >= hitHandle) break;
            if (!a.isPrivate() && n.apply(a.output()).contains(normalisedWindow)) return true;
        }
        for (var turn : trajectory.turns()) {
            var action = turn.action();
            if (action == null) continue;
            if (action.reasoning() != null && n.apply(action.reasoning()).contains(normalisedWindow)) {
                return true;
            }
            for (Object v : action.params().values()) {
                if (v != null && n.apply(String.valueOf(v)).contains(normalisedWindow)) return true;
            }
        }
        Artifact hit = hitHandle >= 1 && hitHandle <= artifacts.size()
                ? artifacts.get(hitHandle - 1) : null;
        if (hit != null) {
            String source = skillSource.apply(hit.tool());
            if (source != null && n.apply(source).contains(normalisedWindow)) return true;
        }
        return false;
    }

    /**
     * How to read a skill's source, for the clause above. Supplied once per task by the loop;
     * the default answers nothing, so a context built without it is no more permissive.
     */
    public void setSkillSource(Function<String, String> reader) {
        this.skillSource = reader == null ? n -> null : reader;
    }

    /** What a cloud call made on behalf of this task carries to the door. */
    public com.ownclaw.llm.EgressContext egress(String purpose) {
        return new com.ownclaw.llm.EgressContext(userId, taskId, purpose, privateIndex,
                secretValues, this::isAllowedLeak);
    }
}
