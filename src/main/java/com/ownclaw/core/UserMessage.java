package com.ownclaw.core;

import com.ownclaw.agent.AgentResult;
import com.ownclaw.agent.TaskChat;
import com.ownclaw.conversation.ChatOptions;

import java.util.List;
import java.util.function.Consumer;

/**
 * A message the owner sent in a chat, saved there as its user row, on its way to a task: to the
 * task running in that chat, which reads it before its next step, or to a task of its own
 * ({@link TaskQueue#send}).
 *
 * @param sessionId     the chat it was saved in, whose running task it goes to
 * @param messageId     its user row: the message a task of its own answers
 * @param text          what he wrote, whole
 * @param attachmentIds the files sent with it
 * @param channel       where it came from: where a task of its own shows its progress
 * @param options       what it was sent with of the owner's defaults: from the web chat, what was
 *                      chosen next to the message box; from Telegram, what its chat has chosen;
 *                      {@link ChatOptions#NONE} from the ops API. A task of its own runs on these
 * @param answer        what is done with the answer of a task of its own: saved in its chat and
 *                      sent where the message came from
 */
public record UserMessage(String userId, String sessionId, String messageId, String text,
                          List<String> attachmentIds, TaskChat.Channel channel, ChatOptions options,
                          Consumer<AgentResult> answer) {

    public UserMessage {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}
