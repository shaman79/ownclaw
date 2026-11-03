package com.ownclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownclaw.config.OwnClawConfig;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * For tests: an Ollama that has hung with its port open -- a loopback server that accepts every
 * connection and never answers -- and the real Ollama provider pointed at it, with its chat
 * client as production builds it. Its startup check's client gives up after {@link #PROBE_MS},
 * where production's gives up after seconds, so a test that waits on the check is quick and one
 * that waits on the chat client's hour is not. Close it to let go of the sockets.
 */
public final class WedgedOllama implements AutoCloseable {

    /** How long the check's client waits for an answer here. */
    public static final long PROBE_MS = 300;

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final LlmProvider provider;

    public WedgedOllama() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while (true) accepted.add(server.accept());
            } catch (IOException closed) {
                // the test is over
            }
        });
        var config = new OwnClawConfig();
        config.getExecutor().setUrl("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getLocalPort());
        config.getExecutor().setModel(OllamaStreamingTest.MODEL);
        var mapper = new ObjectMapper();
        var check = new LocalModelCheck(config, mapper, new OkHttpClient.Builder()
                .readTimeout(PROBE_MS, TimeUnit.MILLISECONDS)
                .build());
        provider = new OllamaProvider(config, mapper, check);
    }

    public LlmProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : accepted) s.close();
    }
}
