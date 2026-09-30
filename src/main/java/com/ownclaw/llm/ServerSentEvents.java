package com.ownclaw.llm;

import okio.BufferedSource;

import java.io.IOException;

/**
 * A {@code text/event-stream} body, read one event at a time: an event is its {@code event:}
 * name and its {@code data:} lines, and a blank line ends it. Both cloud providers stream their
 * replies in this form.
 */
final class ServerSentEvents {

    /** One event. {@code name} is null when the stream gave it none, as OpenAI never does. */
    record Event(String name, String data) {}

    private final BufferedSource source;

    ServerSentEvents(BufferedSource source) {
        this.source = source;
    }

    /**
     * The next event, or null when the stream has ended. An event the stream stopped in the
     * middle of -- no blank line after it -- is not returned: it may be half of one.
     */
    Event next() throws IOException {
        String name = null;
        StringBuilder data = null;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.isEmpty()) {
                if (data != null) return new Event(name, data.toString());
                name = null;                                   // a name without data is no event
                continue;
            }
            if (line.startsWith(":")) continue;                // a comment
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (field.equals("event")) {
                name = value;
            } else if (field.equals("data")) {
                if (data == null) data = new StringBuilder(value);
                else data.append('\n').append(value);
            }
            // "id" and "retry" are not used.
        }
        return null;
    }
}
