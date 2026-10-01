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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A server that is not there: every request is answered from a script, by path, and kept, and
 * nothing touches the network. The last reply scripted for a path repeats; earlier ones are
 * used once each, in order. Counts the reply bodies handed out and the ones closed, so a test
 * can see that a stream was closed however the call ended. A path made {@link #silent} answers
 * nothing at all until the call is cancelled; one made {@link #slow}, nothing for a while.
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
    private final Set<String> silent = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> slow = new ConcurrentHashMap<>();
    /** Counted down when a request to a silent path is under way. */
    final CountDownLatch silenced = new CountDownLatch(1);

    FakeHttp on(String path, int status, String contentType, String body) {
        replies.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Reply(status, contentType, body));
        return this;
    }

    FakeHttp json(String path, int status, String body) {
        return on(path, status, "application/json", body);
    }

    /**
     * This path sends nothing, not even its headers, until the call is cancelled -- as Ollama
     * does while it loads the model and reads the prompt -- and then the call fails as a
     * cancelled OkHttp call does. Fails the test if nothing cancels it within 30 seconds.
     */
    FakeHttp silent(String path) {
        silent.add(path);
        return this;
    }

    /**
     * This path sends nothing, not even its headers, for {@code ms} -- as Ollama sends nothing
     * while it loads the model and reads a long prompt -- and then answers as scripted. A call
     * cancelled meanwhile fails as a cancelled OkHttp call does.
     */
    FakeHttp slow(String path, long ms) {
        slow.put(path, ms);
        return this;
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
        if (silent.contains(request.url().encodedPath())) {
            silenced.countDown();
            long giveUp = System.currentTimeMillis() + 30_000;
            while (!chain.call().isCanceled()) {
                if (System.currentTimeMillis() > giveUp) throw new AssertionError("a silent call was never ended");
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            throw new IOException("Canceled");
        }
        Long quiet = slow.get(request.url().encodedPath());
        if (quiet != null) {
            long until = System.currentTimeMillis() + quiet;
            while (System.currentTimeMillis() < until) {
                if (chain.call().isCanceled()) throw new IOException("Canceled");
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        }
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
