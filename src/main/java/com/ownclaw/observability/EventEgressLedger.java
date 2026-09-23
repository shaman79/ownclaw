package com.ownclaw.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.llm.EgressLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * The ledger as {@code events} rows.
 * <p>
 * No new table. {@code events} already has {@code task_id}, a {@code details} JSON column and
 * the {@code (user_id, task_id)} index — exactly what {@code persistStep} uses for the step
 * rows this sits beside, so "what did task X send to the cloud" is the same query as "what did
 * task X do". {@code tokens_used} stays 0 and the tokens live in details, so the completed-task
 * token sum, which selects {@code task_completed} rows only, cannot double count. Details carry
 * sizes, kinds and hash prefixes; a content column cannot be added by a later convenience
 * because there is no content here to add.
 */
@Component
public class EventEgressLedger implements EgressLedger {

    private static final Logger log = LoggerFactory.getLogger(EventEgressLedger.class);

    private final EventLogService eventLog;
    private final ObjectMapper mapper;

    public EventEgressLedger(EventLogService eventLog, ObjectMapper mapper) {
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    @Override
    public void record(Row r) {
        try {
            var d = new LinkedHashMap<String, Object>();
            d.put("decision", r.decision().name());
            d.put("purpose", r.purpose());
            d.put("provider", r.provider());
            d.put("model", r.model());
            d.put("bytesOut", r.bytesOut());
            d.put("toolCount", r.toolCount());
            d.put("promptTokens", r.promptTokens());
            d.put("completionTokens", r.completionTokens());
            d.put("cacheWriteTokens", r.cacheWriteTokens());
            d.put("cacheReadTokens", r.cacheReadTokens());
            d.put("costUsd", r.costUsd());
            d.put("scrubs", r.scrubs());
            d.put("privateArtifacts", r.privateArtifacts());
            if (r.refusalRef() != null) d.put("refusal", r.refusalRef());
            d.put("parts", r.parts().stream().map(p -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("i", p.index()); m.put("kind", p.kind());
                m.put("chars", p.chars()); m.put("sha256_16", p.sha256_16());
                return m;
            }).toList());
            String severity = switch (r.decision()) {
                case SENT -> "info";
                case OBSERVED_LEAK, ERROR -> "warn";
                case REFUSED -> "error";
            };
            String summary = r.decision() + " " + r.purpose() + " " + r.bytesOut() + " B "
                    + r.provider() + "/" + r.model();
            // events.user_id is NOT NULL; a call with no task still has a user.
            eventLog.log(r.userId() == null ? "system" : r.userId(), r.taskId(), "egress",
                    severity, summary, mapper.writeValueAsString(d), 0);
        } catch (Exception e) {
            // The ledger must never take the call down with it — but a row that could not be
            // written is worth a loud line, because the ledger is the whole point.
            log.error("Egress ledger row could not be written ({} {}): {}", r.decision(),
                    r.purpose(), e.getMessage());
        }
    }
}
