package app.training.garmin;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the garmin-sync sidecar on a local port: answers each path with a scripted
 * status and JSON body and records what it received. Only obviously fake values are used.
 */
public final class FakeSidecar implements AutoCloseable {
    public record Received(String path, String secret, String body) {}

    public record Reply(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    public final List<Received> received = new CopyOnWriteArrayList<>();

    public FakeSidecar() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            received.add(new Received(path, ex.getRequestHeaders().getFirst(SidecarClient.SECRET_HEADER), body));
            Reply r = replies.getOrDefault(path, new Reply(404, "{\"error\":\"NOT_FOUND\"}"));
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeSidecar reply(String path, int status, String body) {
        replies.put(path, new Reply(status, body));
        return this;
    }

    public void reset() {
        replies.clear();
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
