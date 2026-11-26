package com.ownclaw.agent;

import com.ownclaw.conversation.FileStorageService;
import com.ownclaw.observability.EventLogService;

import java.util.List;

/** For tests outside this package: the loop's registration of a message's files, and the files section the cloud reads. */
public final class AgentLoopAccess {

    private AgentLoopAccess() {}

    public static void registerAttachments(AgentContext context, List<String> ids, FileStorageService files,
                                           EventLogService events) {
        AgentLoop.registerAttachments(context, ids, files, events);
    }

    public static String filesSection(AgentContext context) {
        return ThinkingEngine.filesSection(context);
    }
}
