package com.ownclaw.interfaces.telegram;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The Bot API, answered here: an OkHttp client whose interceptor records every call as
 * "method body", and the thread that made it, and answers it from {@link #answers} (per method,
 * in order), then from {@link #otherwise}, then with 200 ok. No network is touched.
 */
final class FakeTelegram {

    /** A canned reply: an HTTP status and a JSON body. */
    record Answer(int code, String body) {
        static Answer ok(String result) { return new Answer(200, "{\"ok\":true,\"result\":" + result + "}"); }
        static Answer refused(int code) { return new Answer(code, "{\"ok\":false,\"error_code\":" + code + "}"); }
        static Answer tooMany(int retryAfter) {
            return new Answer(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                    + "\"parameters\":{\"retry_after\":" + retryAfter + "}}");
        }
    }

    final List<String> calls = new CopyOnWriteArrayList<>();
    /** Each call as "method thread": the thread it was made on. */
    final List<String> threads = new CopyOnWriteArrayList<>();
    final Map<String, Deque<Answer>> answers = new ConcurrentHashMap<>();
    /** What a method is answered once its queue is empty. */
    final Map<String, Answer> otherwise = new ConcurrentHashMap<>();

    /** Queue the next answers for a method. */
    void answer(String method, Answer... next) {
        answers.computeIfAbsent(method, m -> new ConcurrentLinkedDeque<>()).addAll(List.of(next));
    }

    /** The bodies of the calls to one method, in order. */
    List<String> bodies(String method) {
        return calls.stream().filter(c -> c.startsWith(method + " "))
                .map(c -> c.substring(method.length() + 1)).toList();
    }

    /** The threads the calls to one method were made on, in order. */
    List<String> threadsOf(String method) {
        return threads.stream().filter(t -> t.startsWith(method + " "))
                .map(t -> t.substring(method.length() + 1)).toList();
    }

    /** Wait until the bot has sent everything handed to its outbox so far. */
    static void drain(TelegramBotService bot) throws Exception {
        var outbox = TelegramBotService.class.getDeclaredField("outbox");
        outbox.setAccessible(true);
        ((ExecutorService) outbox.get(bot)).submit(() -> { }).get(30, TimeUnit.SECONDS);
    }

    final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request r = chain.request();
        String method = r.url().pathSegments().getLast();
        Buffer body = new Buffer();
        if (r.body() != null) r.body().writeTo(body);
        calls.add(method + " " + body.readUtf8());
        threads.add(method + " " + Thread.currentThread().getName());
        Deque<Answer> queued = answers.get(method);
        Answer a = queued == null || queued.isEmpty()
                ? otherwise.getOrDefault(method, Answer.ok("true")) : queued.poll();
        return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(a.code()).message("x")
                .body(ResponseBody.create(a.body(), MediaType.get("application/json"))).build();
    }).build();
}
