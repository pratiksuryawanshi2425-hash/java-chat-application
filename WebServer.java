import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets phones / browsers join the SAME chat room as the Swing clients.
 * Uses only the JDK (com.sun.net.httpserver), no external libraries.
 *
 *   GET  /              the chat web page (web/index.html)
 *   POST /join          body = username        -> {"token": "..."}
 *   GET  /events?token  Server-Sent Events stream (messages, user list)
 *   POST /send?token    body = message text
 *   POST /leave?token
 *
 * Each browser is a WebSession, which implements MessageHandler - so ChatRoom treats
 * it exactly like a socket-based ClientHandler (polymorphism).
 */
public class WebServer {

    private static final long GRACE_MS = 25000;     // keep a dropped phone in the room this long
    private static final int MAX_QUEUED = 300;
    private static final String WAKE = new String("wake");   // compared by identity

    private final int port;
    private final ChatRoom room;
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<String, WebSession>();
    private HttpServer http;
    private ScheduledExecutorService reaper;

    public WebServer(int port, ChatRoom room) {
        this.port = port;
        this.room = room;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(port), 0);   // all network interfaces
        http.createContext("/", this::handlePage);
        http.createContext("/join", this::handleJoin);
        http.createContext("/events", this::handleEvents);
        http.createContext("/send", this::handleSend);
        http.createContext("/leave", this::handleLeave);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "web-worker");
            t.setDaemon(true);
            return t;
        }));
        http.start();

        reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "web-reaper");
            t.setDaemon(true);
            return t;
        });
        reaper.scheduleWithFixedDelay(this::reapIdleSessions, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        if (reaper != null) reaper.shutdownNow();
        if (http != null) http.stop(0);
    }

    // ------------------------------------------------------------------
    //  One browser connected to the room
    // ------------------------------------------------------------------
    private static class WebSession implements MessageHandler {
        final String token = UUID.randomUUID().toString();
        final User user;
        final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<String>();
        final AtomicInteger generation = new AtomicInteger();
        final AtomicInteger openStreams = new AtomicInteger();
        volatile long lastSeen = System.currentTimeMillis();
        volatile boolean closed = false;

        WebSession(User user) {
            this.user = user;
        }

        private void push(String event, String json) {
            while (queue.size() >= MAX_QUEUED) queue.pollFirst();
            queue.offerLast("event: " + event + "\ndata: " + json + "\n\n");
        }

        @Override
        public void handleMessage(Message m) {
            push("message", "{\"type\":\"" + m.getType() + "\",\"sender\":" + quote(m.getSender())
                    + ",\"time\":" + quote(m.getTimestamp().toLocalTime().withNano(0).toString())
                    + ",\"text\":" + quote(m.getContent()) + "}");
        }

        @Override
        public void handleUserList(List<String> names) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quote(names.get(i)));
            }
            push("users", sb.append(']').toString());
        }

        @Override
        public void handleDisconnect(String reason) {
            push("bye", quote(reason));
            closed = true;
        }
    }

    // ------------------------------------------------------------------
    //  HTTP handlers
    // ------------------------------------------------------------------
    private void handlePage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            reply(ex, 404, "text/plain", "Not found");
            return;
        }
        try {
            byte[] page = Files.readAllBytes(Paths.get("web", "index.html"));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            reply(ex, 200, "text/html; charset=utf-8", page);
        } catch (IOException e) {
            reply(ex, 500, "text/plain", "web/index.html not found. Start the server from inside the ChatApplication folder.");
        }
    }

    private void handleJoin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "text/plain", "POST only"); return; }
        String name = readBody(ex).trim();
        String problem = User.describeUsernameProblem(name);
        if (problem != null) { reply(ex, 400, "text/plain", problem); return; }

        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        WebSession session = new WebSession(new User(name, ip));
        if (!room.addUser(session.user, session)) {
            reply(ex, 409, "text/plain", "Username '" + name + "' is already taken. Choose another one.");
            return;
        }
        sessions.put(session.token, session);
        room.logEvent("Web connection accepted from " + ip + " as '" + name + "'");
        room.announce(name + " joined the chat");
        room.broadcastUserList();
        reply(ex, 200, "application/json", "{\"token\":" + quote(session.token) + "}");
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        WebSession s = sessions.get(tokenOf(ex));
        if (s == null) { reply(ex, 410, "text/plain", "Session expired"); return; }

        int myGeneration = s.generation.incrementAndGet();   // a newer stream replaces older ones
        s.queue.offerFirst(WAKE);                            // wakes any older stream so it exits
        s.openStreams.incrementAndGet();
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);                      // 0 = chunked, stays open

        OutputStream out = ex.getResponseBody();
        try {
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!s.closed && s.generation.get() == myGeneration) {
                String event = s.queue.poll(15, TimeUnit.SECONDS);
                if (event == WAKE) continue;
                if (event == null) event = ": ping\n\n";     // heartbeat keeps phones/proxies from timing out
                try {
                    out.write(event.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException dead) {
                    if (!event.startsWith(":")) s.queue.offerFirst(event);   // deliver it on reconnect
                    break;
                }
                s.lastSeen = System.currentTimeMillis();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            s.openStreams.decrementAndGet();
            s.lastSeen = System.currentTimeMillis();
            try { ex.close(); } catch (RuntimeException ignored) { }
        }
    }

    private void handleSend(HttpExchange ex) throws IOException {
        WebSession s = sessions.get(tokenOf(ex));
        if (s == null) { reply(ex, 410, "text/plain", "Session expired"); return; }
        String text = readBody(ex).replace('\n', ' ').replace('\r', ' ').trim();
        if (!text.isEmpty()) {
            if (text.length() > 500) text = text.substring(0, 500);
            room.broadcast(new Message(s.user.getUsername(), text, Message.Type.CHAT));
        }
        s.lastSeen = System.currentTimeMillis();
        reply(ex, 204, "text/plain", "");
    }

    private void handleLeave(HttpExchange ex) throws IOException {
        WebSession s = sessions.get(tokenOf(ex));
        if (s != null) leave(s);
        reply(ex, 204, "text/plain", "");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------
    private void leave(WebSession s) {
        if (sessions.remove(s.token) == null) return;
        s.closed = true;
        s.queue.offerFirst(WAKE);
        if (room.removeUser(s.user.getUsername())) {
            room.announce(s.user.getUsername() + " left the chat");
            room.broadcastUserList();
        }
    }

    /** Removes browsers that have been gone longer than the grace period (tab closed, phone asleep). */
    private void reapIdleSessions() {
        long now = System.currentTimeMillis();
        for (WebSession s : sessions.values()) {
            if (s.openStreams.get() == 0 && now - s.lastSeen > GRACE_MS) {
                leave(s);
            }
        }
    }

    private static String tokenOf(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && q.startsWith("token=")) return q.substring(6);
        return "";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 4096) buf.write(chunk, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void reply(HttpExchange ex, int status, String type, String body) throws IOException {
        reply(ex, status, type + (type.startsWith("text/") || type.startsWith("application/json") ? "; charset=utf-8" : ""),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    /** Minimal JSON string escaping. */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
