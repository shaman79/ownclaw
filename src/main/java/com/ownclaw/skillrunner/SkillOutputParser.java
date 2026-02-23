package com.ownclaw.skillrunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses JSON-lines output from skill scripts (stdout).
 * Skills emit one JSON line per message: progress, need_input, or result.
 */
@Component
public class SkillOutputParser {

    private static final Logger log = LoggerFactory.getLogger(SkillOutputParser.class);

    private final ObjectMapper mapper;

    public SkillOutputParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parse the stdout of a skill execution into structured messages.
     *
     * @param stdout raw stdout content (may contain multiple JSON lines)
     * @return list of parsed messages in order
     */
    public List<SkillOutput> parse(String stdout) {
        List<SkillOutput> outputs = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return outputs;
        }

        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.startsWith("{")) continue;

            try {
                JsonNode node = mapper.readTree(line);
                String type = node.path("type").asText("");
                outputs.add(switch (type) {
                    case "progress" -> new SkillOutput(SkillOutput.Type.PROGRESS,
                            node.path("message").asText(""), null);
                    case "need_input" -> new SkillOutput(SkillOutput.Type.NEED_INPUT,
                            node.path("prompt").asText(""), node);
                    case "result" -> new SkillOutput(SkillOutput.Type.RESULT,
                            node.path("output").toString(), node);
                    default -> {
                        log.debug("Unknown skill output type: {}", type);
                        yield new SkillOutput(SkillOutput.Type.UNKNOWN, line, node);
                    }
                });
            } catch (Exception e) {
                log.debug("Non-JSON line in skill output: {}", line);
            }
        }
        return outputs;
    }

    /**
     * Extract the final result from parsed outputs.
     *
     * @return the result output, or null if no result was found
     */
    public SkillOutput extractResult(List<SkillOutput> outputs) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            if (outputs.get(i).type() == SkillOutput.Type.RESULT) {
                return outputs.get(i);
            }
        }
        return null;
    }

    public record SkillOutput(Type type, String content, JsonNode raw) {
        public enum Type { PROGRESS, NEED_INPUT, RESULT, UNKNOWN }

        public boolean isSuccess() {
            return type == Type.RESULT && raw != null
                    && "success".equals(raw.path("status").asText(""));
        }
    }
}
