package example.consumer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.*;

/** 随机本地端口的真实 HTTP 服务，验证 Feign 编解码、Header 和超时。 */
final class FakeUserServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "fake-user-http");
        thread.setDaemon(true);
        return thread;
    });
    private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();

    FakeUserServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/", this::respond);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void enqueue(int status, String body, long delayMillis, String location) {
        replies.add(new Reply(status, body, delayMillis, location));
    }
    Call take() throws InterruptedException { return calls.poll(2, TimeUnit.SECONDS); }
    int remainingCalls() { return calls.size(); }

    private void respond(HttpExchange exchange) throws IOException {
        Headers headers = new Headers();
        headers.putAll(exchange.getRequestHeaders());
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI(), headers));
        Reply reply = replies.poll();
        if (reply == null) { reply = new Reply(200, "{\"success\":true,\"data\":[]}", 0, null); }
        try {
            if (reply.delayMillis > 0) { Thread.sleep(reply.delayMillis); }
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.location != null) { exchange.getResponseHeaders().set("Location", reply.location); }
            exchange.sendResponseHeaders(reply.status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Override public void close() { server.stop(0); threads.shutdownNow(); }

    static final class Call {
        final String method;
        final URI uri;
        final Headers headers;
        Call(String method, URI uri, Headers headers) { this.method = method; this.uri = uri; this.headers = headers; }
    }
    private static final class Reply {
        final int status;
        final String body;
        final long delayMillis;
        final String location;
        Reply(int status, String body, long delayMillis, String location) {
            this.status = status; this.body = body; this.delayMillis = delayMillis; this.location = location;
        }
    }
}
