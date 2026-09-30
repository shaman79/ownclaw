package com.ownclaw.llm;

import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code text/event-stream} framing both cloud providers read their replies through. A rule
 * broken here drops or merges events silently, so each is pinned on its own.
 */
class ServerSentEventsTest {

    static ServerSentEvents stream(String text) {
        return new ServerSentEvents(new Buffer().writeUtf8(text));
    }

    @Test
    @DisplayName("an event's data lines are joined with a newline, and its name is kept")
    void multiLineData() throws Exception {
        var events = stream("event: note\ndata: first\ndata: second\n\n");
        var e = events.next();
        assertEquals("note", e.name());
        assertEquals("first\nsecond", e.data());
        assertNull(events.next());
    }

    @Test
    @DisplayName("an event the stream stops in the middle of is not returned: it may be half of one")
    void anEventCutOffIsNotReturned() throws Exception {
        var events = stream("data: {\"a\":1}\n\ndata: {\"type\":\"message_stop\"}\n");
        assertEquals("{\"a\":1}", events.next().data());
        assertNull(events.next(), "no blank line after it, so it is not known to be whole");
    }

    @Test
    @DisplayName("comments are passed over, a name without data is no event, and id and retry are ignored")
    void theRest() throws Exception {
        var events = stream(": keep-alive\n\nevent: lonely\n\nid: 7\nretry: 100\ndata:x\n\n");
        var e = events.next();
        assertNull(e.name(), "the name of an event that had no data is not carried to the next");
        assertEquals("x", e.data(), "the space after the colon is optional");
        assertNull(events.next());
    }
}
