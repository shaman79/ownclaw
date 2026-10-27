package com.ownclaw.agent;

import com.ownclaw.core.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The owner's OK before a task's first change (owner, 2026-10-27). Before the first call of a
 * task that changes something -- a tool with side effects, run by the cloud's step, by a
 * delegation, or named in a delegation's plan -- the task says in its chat what it is about to
 * run and waits for his answer there. After an OK the rest of the task runs without asking;
 * "always" stops the asking in that chat until midnight. A reply that is no OK is passed to the
 * model as his words, and the call is not run. Work nobody is there to answer -- scheduled runs,
 * /bg, an ops API turn, a task with no chat -- runs as before, without asking.
 * <p>
 * The question goes to the task's own chat, and only a message sent there answers it: it is
 * taken from the task's inbox ({@link com.ownclaw.core.Inbox#awaitAnswer}), so with tasks of
 * several chats running, a reply typed in another chat answers nothing here. The wait ends on
 * the task's Stop, and the stall watchdog does not count it as silence.
 */
final class Approval {

    private static final Logger log = LoggerFactory.getLogger(Approval.class);

    /** How long a task waits for the owner's answer before it goes on without the change. */
    static volatile long waitMs = 30 * 60_000L;

    /** The chats where the owner answered "always", by user and chat, with when it ends (epoch ms). */
    private static final Map<String, Long> ALWAYS = new ConcurrentHashMap<>();

    /** A reply made of these words only, one of them an OK, is an OK. */
    static final Set<String> YES = Set.of("yes", "y", "yeah", "yep", "yup", "ok", "okay", "k", "sure", "go",
            "do", "proceed", "continue", "approve", "approved", "confirm", "confirmed",
            "ano", "jo", "jj", "dobre", "dobře", "souhlas", "souhlasim", "souhlasím", "pokracuj",
            "pokračuj", "jasne", "jasně");
    /** The OK for the rest of the day in that chat. */
    static final Set<String> ALWAYS_WORDS = Set.of("always", "vzdy", "vždy", "vzdycky", "vždycky");
    /** Words that may stand beside an OK without making it anything else. */
    static final Set<String> FILLER = Set.of("please", "ahead", "it", "thanks", "thank", "you",
            "prosim", "prosím", "klidne", "klidně", "dik", "díky");

    private Approval() {}

    /**
     * Before a change of {@code context}'s task: null when it may go on -- it needs no OK, it has
     * had one, or the owner says yes now -- or else why it does not, in words the model acts on.
     *
     * @param what what is about to run, as the owner is shown it (markdown)
     */
    static String ask(AgentContext context, String what) {
        if (!needsAsking(context)) return null;
        // From here the next message in the chat is the answer, until the wait ends; reset even
        // if posting the question throws, so a later message is not silently eaten as an answer.
        context.inbox().startAsking();
        UserMessage answer;
        try {
            context.chat().question("❓ **Before I change anything** -- this task is about to run:\n\n" + what
                    + "\n\nReply **yes** to go on, **always** to stop asking in this chat today, or say what "
                    + "to do instead.");
            answer = context.runningTool(() -> {
                try {
                    return context.inbox().awaitAnswer(waitMs, context::isCancelled);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });
        } finally {
            context.inbox().doneAsking();
        }
        if (answer == null) {
            if (context.isCancelled()) return "Not run: the task was stopped while it waited for the owner's OK.";
            log.info("Task {}: no answer to the question before its first change; nothing was run.",
                    context.taskId());
            return "Not run: the owner did not answer within " + TaskRecord.duration(waitMs) + " whether to "
                    + "go on, so nothing was changed. Tell him what you would run and ask him whether to "
                    + "go on (respond); do not run it.";
        }
        Reply reply = read(answer.text());
        if (reply == Reply.NO) {
            log.info("Task {}: the owner did not OK its first change.", context.taskId());
            return "Not run: the owner did not OK it. He wrote: \"" + answer.text().strip() + "\". Do as he "
                    + "says, and do not run it unless he asks you to.";
        }
        if (reply == Reply.ALWAYS) {
            ALWAYS.put(key(context.userId(), context.chatSession()), endOfToday());
        }
        context.approveChanges();
        log.info("Task {}: the owner OKed its changes{}.", context.taskId(),
                reply == Reply.ALWAYS ? ", and stopped the asking in this chat for today" : "");
        return null;
    }

    /** Whether a change of this task waits for the owner's OK now. */
    static boolean needsAsking(AgentContext context) {
        if (context.isUnattended() || context.changesApproved()) return false;
        if (context.inbox() == null || context.chatSession() == null || !context.chat().canAsk()) return false;
        Long until = ALWAYS.get(key(context.userId(), context.chatSession()));
        if (until == null) return true;
        if (System.currentTimeMillis() < until) return false;
        ALWAYS.remove(key(context.userId(), context.chatSession()), until);
        return true;
    }

    enum Reply { YES, ALWAYS, NO }

    /**
     * What a reply says: an OK when every word of it is an OK, "always" or filler, and one of them
     * is an OK or "always"; anything else -- "yes, but only step 1", "no", a question -- is no OK,
     * and goes to the model as the owner's words.
     */
    static Reply read(String text) {
        if (text == null) return Reply.NO;
        String[] words = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip()
                .split(" ");
        boolean ok = false, always = false;
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (ALWAYS_WORDS.contains(w)) always = true;
            else if (YES.contains(w)) ok = true;
            else if (!FILLER.contains(w)) return Reply.NO;
        }
        return always ? Reply.ALWAYS : ok ? Reply.YES : Reply.NO;
    }

    /** A call as the owner is shown it: the tool, and its arguments as they were written. */
    static String call(String tool, Map<String, Object> params) {
        var sb = new StringBuilder("`").append(tool).append('`');
        if (params == null || params.isEmpty()) return sb.toString();
        for (var e : params.entrySet()) {
            String value = String.valueOf(e.getValue());
            sb.append("\n- ").append(e.getKey()).append(": ");
            if (value.contains("\n")) sb.append("\n```\n").append(value).append("\n```");
            else sb.append('`').append(value).append('`');
        }
        return sb.toString();
    }

    /** A delegation as the owner is shown it: its goal, the tools it names and the texts it hands over. */
    static String delegation(DelegationPlan plan) {
        var sb = new StringBuilder("a delegation to the local model");
        if (plan.tools() != null && !plan.tools().isEmpty()) {
            sb.append(" with ").append(String.join(", ", plan.tools().stream().map(t -> "`" + t + "`").toList()));
        }
        sb.append(":\n\n> ").append(String.valueOf(plan.goal()).replace("\n", "\n> "));
        if (plan.texts() != null) {
            for (var t : plan.texts().entrySet()) {
                sb.append("\n\n{{").append(t.getKey()).append("}}:\n```\n").append(t.getValue()).append("\n```");
            }
        }
        return sb.toString();
    }

    private static String key(String userId, String chat) {
        return userId + "\n" + chat;
    }

    private static long endOfToday() {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** For a test: no chat has "always" any more. */
    static void forgetAlways() {
        ALWAYS.clear();
    }
}
