package com.mohit.kv;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-over-HTTP window into the server for the web UI (browsers cannot speak raw TCP).
 * Binds to the same host as the Redis port (127.0.0.1 by default). /api/exec can run any command, so do not
 * expose this port to a network you do not trust.
 */
public class HttpGateway implements Closeable {
    private final Server server;
    private final String host;
    private final int port;
    private HttpServer http;
    private final AtomicBoolean benchRunning = new AtomicBoolean();

    public HttpGateway(Server server, String host, int port) {
        this.server = server;
        this.host = host;
        this.port = port;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        http.createContext("/api/", this::route);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "http-gateway");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }

    @Override
    public void close() { if (http != null) http.stop(0); }

    private void route(HttpExchange ex) throws IOException {
        try {
            String m = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if (m.equals("GET") && path.equals("/api/health")) send(ex, 200, Map.of("status", "UP"));
            else if (m.equals("GET") && path.equals("/api/info")) send(ex, 200, info());
            else if (m.equals("GET") && path.equals("/api/keys")) send(ex, 200, keys(q));
            else if (m.equals("GET") && path.equals("/api/key")) key(ex, q.get("name"));
            else if (m.equals("POST") && path.equals("/api/exec")) exec(ex);
            else if (m.equals("POST") && path.equals("/api/bench")) bench(ex);
            else send(ex, 404, Map.of("error", "Not found"));
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            send(ex, 500, Map.of("error", "Internal error: " + e));
        }
    }

    private Map<String, Object> info() {
        Stats st = server.stats();
        Store store = server.store();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("now", System.currentTimeMillis());
        m.put("uptimeSeconds", (System.currentTimeMillis() - st.startedAt) / 1000);
        m.put("redisPort", server.port());
        m.put("httpPort", port());
        m.put("connectedClients", st.connectedClients.get());
        m.put("totalConnections", st.connectionsTotal.get());
        m.put("totalCommands", st.commands.get());
        m.put("keys", store.dbsize());
        m.put("rawEntries", store.rawSize());
        m.put("aofEnabled", server.aof() != null);
        m.put("aofSizeBytes", server.aof() != null ? server.aof().sizeBytes() : 0);
        m.put("fsync", server.config().fsync().name().toLowerCase());
        m.put("maxClients", server.config().maxClients());
        m.put("commandCounts", st.commandCounts());
        List<Object> recent = new ArrayList<>();
        for (Stats.Recent r : st.recent(12)) recent.add(Map.of("at", r.at(), "text", r.text()));
        m.put("recent", recent);
        return m;
    }

    private Map<String, Object> keys(Map<String, String> q) {
        String pattern = q.getOrDefault("pattern", "*");
        int limit = Math.max(1, Math.min(1000, parseInt(q.get("limit"), 300)));
        List<String> all = server.store().keys(pattern.isEmpty() ? "*" : pattern);
        List<Object> out = new ArrayList<>();
        for (String k : all) {
            if (out.size() >= limit) break;
            Store.KeyInfo ki = server.store().info(k);
            if (ki == null) continue; // expired between KEYS and INFO
            out.add(keyMap(ki));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("shown", out.size());
        m.put("keys", out);
        return m;
    }

    private static Map<String, Object> keyMap(Store.KeyInfo ki) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", ki.key());
        m.put("type", ki.type());
        m.put("ttlMs", ki.ttlMs());
        m.put("size", ki.size());
        return m;
    }

    @SuppressWarnings("unchecked")
    private void key(HttpExchange ex, String name) throws IOException {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("query parameter 'name' is required");
        Store.KeyInfo ki = server.store().info(name);
        Object value = ki == null ? null : server.store().value(name);
        if (ki == null || value == null) { send(ex, 404, Map.of("error", "Key not found (it may have expired)")); return; }
        Map<String, Object> m = keyMap(ki);
        if (ki.type().equals("hash")) {
            List<String> flat = (List<String>) value;
            List<Object> pairs = new ArrayList<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) pairs.add(Map.of("field", flat.get(i), "value", flat.get(i + 1)));
            m.put("value", pairs);
        } else {
            m.put("value", value);
        }
        send(ex, 200, m);
    }

    @SuppressWarnings("unchecked")
    private void exec(HttpExchange ex) throws IOException {
        Object body = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!(body instanceof Map<?, ?> m)) throw new IllegalArgumentException("Body must be a JSON object");
        List<String> args;
        if (m.get("args") instanceof List<?> l) {
            args = new ArrayList<>();
            for (Object o : l) args.add(String.valueOf(o));
        } else if (m.get("command") instanceof String c) {
            try { args = Resp.inline(c.trim()); } catch (IOException e) { throw new IllegalArgumentException(e.getMessage()); }
        } else throw new IllegalArgumentException("Send {\"command\": \"SET a 1\"} or {\"args\": [\"SET\",\"a\",\"1\"]}");
        if (args.isEmpty()) throw new IllegalArgumentException("Empty command");

        long t0 = System.nanoTime();
        Object reply = server.commands().execute(args, true);
        long micros = (System.nanoTime() - t0) / 1000;

        String kind = reply == null ? "nil" : reply instanceof Resp.Err ? "error" : reply instanceof Resp.Simple ? "simple"
                : reply instanceof Long ? "integer" : reply instanceof String ? "bulk" : "array";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("display", Cli.format(reply, ""));
        out.put("micros", micros);
        send(ex, 200, out);
    }

    private void bench(HttpExchange ex) throws Exception {
        Object body = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!(body instanceof Map<?, ?> m)) throw new IllegalArgumentException("Body must be a JSON object");
        int clients = clamp(m.get("clients"), 1, 50, 10);
        int perClient = clamp(m.get("requests"), 10, 20000, 2000);
        int valueBytes = clamp(m.get("valueBytes"), 1, 4096, 64);
        if (!benchRunning.compareAndSet(false, true)) { send(ex, 409, Map.of("error", "A benchmark is already running")); return; }
        try {
            Bench.Result r = Bench.run("127.0.0.1", server.port(), clients, perClient, valueBytes);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("clients", r.clients());
            out.put("requests", r.requests());
            out.put("valueBytes", r.valueBytes());
            out.put("opsPerSec", Math.round(r.opsPerSec()));
            out.put("seconds", round(r.seconds()));
            out.put("avgMs", round(r.avgMs()));
            out.put("p50Ms", round(r.p50Ms()));
            out.put("p95Ms", round(r.p95Ms()));
            out.put("p99Ms", round(r.p99Ms()));
            out.put("maxMs", round(r.maxMs()));
            out.put("errors", r.errors());
            send(ex, 200, out);
        } finally {
            benchRunning.set(false);
        }
    }

    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
    private static int clamp(Object o, int lo, int hi, int def) { return o instanceof Number n ? Math.max(lo, Math.min(hi, n.intValue())) : def; }
    private static int parseInt(String s, int def) { try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = Json.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }
}
