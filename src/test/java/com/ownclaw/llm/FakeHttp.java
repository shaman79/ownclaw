package com.ownclaw.llm;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A server that is not there: every request is answered from a script, by path, and kept, and
 * nothing touches the network. The last reply scripted for a path repeats; earlier ones are
 * used once each, in order. Counts the reply bodies handed out and the ones closed, so a test
 * can see that a stream was closed however the call ended.
 */
final class FakeHttp implements Interceptor {

    record Sent(Request request, String body) {
        String header(String name) { return request.header(name); }
    }

    private record Reply(int status, String contentType, String body) {}

    final List<Sent> sent = new ArrayList<>();
    final AtomicInteger opened = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();
    private final Map<String, Deque<Reply>> replies = new HashMap<>();

    FakeHttp on(String path, int status, String contentType, String body) {
        replies.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Reply(status, contentType, body));
        return this;
    }

    FakeHttp json(String path, int status, String body) {
        return on(path, status, "application/json", body);
    }

    OkHttpClient client() {
        return new OkHttpClient.Builder().addInterceptor(this).build();
    }

    /** What was sent to this path, oldest first. */
    List<Sent> to(String path) {
        return sent.stream().filter(s -> s.request().url().encodedPath().equals(path)).toList();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String body = null;
        if (request.body() != null) {
            var buffer = new Buffer();
            request.body().writeTo(buffer);
            body = buffer.readUtf8();
        }
        sent.add(new Sent(request, body));
        Deque<Reply> queue = replies.get(request.url().encodedPath());
        if (queue == null || queue.isEmpty()) {
            throw new AssertionError("nothing scripted for " + request.url().encodedPath());
        }
        Reply reply = queue.size() > 1 ? queue.poll() : queue.peek();
        opened.incrementAndGet();
        var source = new ForwardingSource(new Buffer().writeUtf8(reply.body())) {
            private boolean done;
            @Override
            public void close() throws IOException {
                if (!done) closed.incrementAndGet();
                done = true;
                super.close();
            }
        };
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.status())
                .message("scripted")
                .body(ResponseBody.create(Okio.buffer(source), MediaType.get(reply.contentType()), -1L))
                .build();
    }
}
