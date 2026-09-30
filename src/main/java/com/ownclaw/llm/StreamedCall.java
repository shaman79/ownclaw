package com.ownclaw.llm;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * How a provider sends a streamed request: the one place the call is made, so that its caller's
 * hook can end it at any moment ({@link LlmProgress#calling}) -- before the first event too.
 */
final class StreamedCall {

    private StreamedCall() {}

    /** What the provider does with the response: check its status, then read the stream. */
    @FunctionalInterface
    interface Reader<T> {
        T read(Response response) throws IOException;
    }

    /**
     * Send {@code request} and read its response with {@code reader}. A connection that fails is
     * an {@link LlmException} -- unless the caller ended the call with the cancel it was handed:
     * then the hook is asked once more, and what it throws (a stopped task's hook throws its
     * stop) reaches the caller unchanged, as a stop heard on an event of the stream does.
     */
    static <T> T send(OkHttpClient http, Request request, String provider, LlmProgress progress,
                      Reader<T> reader) {
        Call call = http.newCall(request);
        progress.calling(call::cancel);
        try (Response response = call.execute()) {
            return reader.read(response);
        } catch (IOException e) {
            if (call.isCanceled()) progress.onProgress();
            throw new LlmException(provider, "Connection failed: " + e.getMessage(), 0, e);
        } finally {
            progress.calling(null);
        }
    }
}
