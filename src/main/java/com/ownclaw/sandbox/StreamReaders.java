package com.ownclaw.sandbox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The threads that read a skill run's output: a thread of its own for each stream, for as long as
 * the stream is open. They block on the stream for the run's whole length, so they are not the
 * common pool's, which has a thread fewer than the machine has cores and adds none for one that
 * blocks: two runs side by side -- tasks of different chats -- held all of its threads, and the
 * second run's output was not read until the first had ended. One writing more than a pipe holds
 * was killed as stalled, its output empty.
 */
final class StreamReaders {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sandbox-stream-" + COUNT.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private StreamReaders() {}

    /** Read a stream of a run, on a thread of its own. */
    static <T> CompletableFuture<T> read(Supplier<T> reading) {
        return CompletableFuture.supplyAsync(reading, POOL);
    }
}
