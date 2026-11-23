package com.mohit.kv;

import com.mohit.kv.Resp.Err;
import com.mohit.kv.Resp.Simple;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free tests: store logic, protocol, TCP server, persistence. Run with ./test.sh */
public class TestRunner {
    static int passed, failed;

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        try { t.run(); passed++; System.out.println("  PASS  " + name); }
        catch (Throwable e) { failed++; System.out.println("  FAIL  " + name + " -> " + e); }
    }

    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static void eq(Object exp, Object act) { if (!Objects.equals(exp, act)) throw new AssertionError("expected <" + exp + "> but was <" + act + ">"); }

    interface Thrower { void run() throws Exception; }
    static <E extends Throwable> void throwsA(Class<E> type, Thrower t) {
        try { t.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("expected " + type.getSimpleName() + " but got " + e); }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    static Server start(Path aof, int maxClients) throws IOException {
        Server s = new Server(new Server.Config("127.0.0.1", 0, aof, Aof.Fsync.NO, maxClients));
        s.start();
        return s;
    }

    static List<String> cmd(String... a) { return Arrays.asList(a); }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("kv-test");

        System.out.println("Store");
        test("set, get, del, exists", () -> {
            Store s = new Store();
            check(s.set("a", "1", -1, false, false), "set");
            eq("1", s.get("a"));
            check(s.exists("a"), "exists");
            eq(1, s.del(List.of("a", "missing")));
            eq(null, s.get("a"));
        });
        test("SET NX and XX", () -> {
            Store s = new Store();
            check(s.set("k", "v1", -1, true, false), "NX on missing key sets");
            check(!s.set("k", "v2", -1, true, false), "NX on existing key does nothing");
            check(s.set("k", "v3", -1, false, true), "XX on existing key sets");
            check(!s.set("nope", "x", -1, false, true), "XX on missing key does nothing");
            eq("v3", s.get("k"));
        });
        test("TTL: key expires lazily (fake clock)", () -> {
            long[] now = {1000};
            Store s = new Store(() -> now[0]);
            s.set("k", "v", 1500, false, false);
            eq("v", s.get("k"));
            eq(500L, s.pttl("k"));
            now[0] = 1500;
            eq(null, s.get("k"));
            eq(-2L, s.pttl("k"));
        });
        test("TTL: -1 without expiry, PERSIST removes expiry, SET clears TTL", () -> {
            long[] now = {0};
            Store s = new Store(() -> now[0]);
            s.set("k", "v", -1, false, false);
            eq(-1L, s.pttl("k"));
            check(s.pexpireAt("k", 100), "expire");
            check(s.persist("k"), "persist");
            eq(-1L, s.pttl("k"));
            s.pexpireAt("k", 100);
            s.set("k", "new", -1, false, false);
            eq(-1L, s.pttl("k"));
            check(!s.pexpireAt("missing", 5), "expire on missing key");
        });
        test("background sweep removes expired keys nobody reads", () -> {
            long[] now = {0};
            Store s = new Store(() -> now[0]);
            for (int i = 0; i < 100; i++) s.set("k" + i, "v", 50, false, false);
            s.set("keep", "v", -1, false, false);
            eq(101, s.rawSize());
            now[0] = 60;
            eq(100, s.sweep());
            eq(1, s.rawSize());
        });
        test("INCR is atomic under 8 threads x 1000", () -> {
            Store s = new Store();
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) fs.add(ex.submit(() -> { for (int i = 0; i < 1000; i++) s.incrBy("n", 1); }));
            for (Future<?> f : fs) f.get();
            ex.shutdown();
            eq("8000", s.get("n"));
        });
        test("INCR on text fails, overflow fails", () -> {
            Store s = new Store();
            s.set("t", "abc", -1, false, false);
            throwsA(Store.NotIntegerException.class, () -> s.incrBy("t", 1));
            s.set("big", Long.toString(Long.MAX_VALUE), -1, false, false);
            throwsA(Store.NotIntegerException.class, () -> s.incrBy("big", 1));
        });
        test("wrong type is rejected", () -> {
            Store s = new Store();
            s.push("l", List.of("a"), false);
            throwsA(Store.WrongTypeException.class, () -> s.get("l"));
            s.set("str", "x", -1, false, false);
            throwsA(Store.WrongTypeException.class, () -> s.push("str", List.of("a"), true));
            throwsA(Store.WrongTypeException.class, () -> s.hset("str", List.of("f", "v")));
        });
        test("lists: push order, pop, range with negative index, empty list is deleted", () -> {
            Store s = new Store();
            eq(3, s.push("l", List.of("a", "b", "c"), true)); // LPUSH a b c => c b a
            eq(List.of("c", "b", "a"), s.lrange("l", 0, -1));
            s.push("l", List.of("x"), false);
            eq(List.of("b", "a", "x"), s.lrange("l", -3, -1));
            eq(List.of(), s.lrange("l", 10, 20));
            eq("c", s.pop("l", true));
            eq("x", s.pop("l", false));
            s.pop("l", true); s.pop("l", true);
            eq("none", s.type("l"));
            eq(null, s.pop("l", true));
        });
        test("hashes: set, get, delete, getall", () -> {
            Store s = new Store();
            eq(2, s.hset("h", List.of("name", "mohit", "city", "delhi")));
            eq(0, s.hset("h", List.of("name", "m")));
            eq("m", s.hget("h", "name"));
            eq(2, s.hlen("h"));
            eq(List.of("name", "m", "city", "delhi"), s.hgetall("h"));
            eq(1, s.hdel("h", List.of("name", "zzz")));
            s.hdel("h", List.of("city"));
            eq("none", s.type("h"));
        });
        test("KEYS glob patterns", () -> {
            Store s = new Store();
            for (String k : List.of("user:1", "user:2", "order:1", "us")) s.set(k, "v", -1, false, false);
            eq(List.of("user:1", "user:2"), s.keys("user:*"));
            eq(List.of("user:1", "order:1"), s.keys("*:1").stream().sorted(Comparator.comparing(x -> x.startsWith("o") ? 1 : 0)).toList());
            eq(List.of("user:1", "user:2"), s.keys("user:[12]"));
            eq(List.of("us"), s.keys("u?"));
        });
        test("APPEND keeps TTL", () -> {
            long[] now = {0};
            Store s = new Store(() -> now[0]);
            s.set("k", "ab", 100, false, false);
            eq(4L, s.append("k", "cd"));
            eq("abcd", s.get("k"));
            eq(100L, s.pttl("k"));
        });

        System.out.println("RESP protocol");
        test("parses an array command", () -> {
            var in = new ByteArrayInputStream("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n".getBytes());
            eq(List.of("SET", "k", "hello"), Resp.readCommand(in));
            eq(null, Resp.readCommand(in));
        });
        test("binary-safe length: value containing CRLF", () -> {
            var in = new ByteArrayInputStream("*2\r\n$3\r\nGET\r\n$4\r\na\r\nb\r\n".getBytes());
            eq(List.of("GET", "a\r\nb"), Resp.readCommand(in));
        });
        test("parses inline commands with quotes", () -> {
            var in = new ByteArrayInputStream("SET name \"Mohit Yadav\"\r\nPING\n".getBytes());
            eq(List.of("SET", "name", "Mohit Yadav"), Resp.readCommand(in));
            eq(List.of("PING"), Resp.readCommand(in));
        });
        test("bad protocol throws ProtocolException", () -> {
            throwsA(Resp.ProtocolException.class, () -> Resp.readCommand(new ByteArrayInputStream("*2\r\nGET\r\n".getBytes())));
            throwsA(Resp.ProtocolException.class, () -> Resp.readCommand(new ByteArrayInputStream("*abc\r\n".getBytes())));
            throwsA(Resp.ProtocolException.class, () -> Resp.readCommand(new ByteArrayInputStream("*1\r\n$-5\r\n".getBytes())));
        });
        test("truncated command throws IOException (EOF)", () -> {
            throwsA(EOFException.class, () -> Resp.readCommand(new ByteArrayInputStream("*2\r\n$3\r\nGET\r\n$5\r\nab".getBytes())));
        });
        test("encoder and parser round trip", () -> {
            var bo = new ByteArrayOutputStream();
            Object[] vals = {new Simple("OK"), new Err("ERR x"), 42L, "hello", null, List.of("a", "b"), "caf\u00e9 \u4e2d"};
            for (Object v : vals) Resp.write(bo, v);
            var in = new ByteArrayInputStream(bo.toByteArray());
            for (Object v : vals) eq(v, Resp.readReply(in));
        });

        System.out.println("Server over TCP");
        Server srv = start(null, 100);
        int port = srv.port();
        test("PING, SET, GET, DEL through the real protocol", () -> {
            try (KvClient c = new KvClient("127.0.0.1", port)) {
                eq("PONG", c.ping());
                c.set("name", "mohit");
                eq("mohit", c.get("name"));
                eq(1L, c.del("name"));
                eq(null, c.get("name"));
            }
        });
        test("unknown command and wrong arity give errors, connection survives", () -> {
            try (KvClient c = new KvClient("127.0.0.1", port)) {
                check(c.call("FOO") instanceof Err e && e.message().contains("unknown command"), "unknown");
                check(c.call("GET") instanceof Err e && e.message().contains("wrong number of arguments"), "arity");
                eq("PONG", c.ping());
            }
        });
        test("TTL over TCP: key disappears after expiry", () -> {
            try (KvClient c = new KvClient("127.0.0.1", port)) {
                c.set("temp", "v", 150);
                check(c.ttl("temp") >= 0, "ttl set");
                Thread.sleep(300);
                eq(null, c.get("temp"));
                eq(-2L, c.ttl("temp"));
            }
        });
        test("pipelining: 3 commands in one packet, 3 replies in order", () -> {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.getOutputStream().write("*1\r\n$4\r\nPING\r\n*3\r\n$3\r\nSET\r\n$2\r\np1\r\n$1\r\nx\r\n*2\r\n$3\r\nGET\r\n$2\r\np1\r\n".getBytes());
                s.getOutputStream().flush();
                var in = new BufferedInputStream(s.getInputStream());
                eq(new Simple("PONG"), Resp.readReply(in));
                eq(new Simple("OK"), Resp.readReply(in));
                eq("x", Resp.readReply(in));
            }
        });
        test("inline commands work like in telnet", () -> {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.getOutputStream().write("SET inl 123\r\nINCR inl\r\n".getBytes());
                s.getOutputStream().flush();
                var in = new BufferedInputStream(s.getInputStream());
                eq(new Simple("OK"), Resp.readReply(in));
                eq(124L, Resp.readReply(in));
            }
        });
        test("protocol error closes the connection with an error message", () -> {
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.getOutputStream().write("*2\r\nGET\r\n".getBytes());
                s.getOutputStream().flush();
                var in = new BufferedInputStream(s.getInputStream());
                check(Resp.readReply(in) instanceof Err e && e.message().contains("Protocol error"), "protocol error reply");
                eq(-1, in.read());
            }
        });
        test("20 concurrent clients x 200 INCR on one key = exactly 4000", () -> {
            ExecutorService ex = Executors.newFixedThreadPool(20);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 20; i++) fs.add(ex.submit(() -> {
                try (KvClient c = new KvClient("127.0.0.1", port)) { for (int j = 0; j < 200; j++) c.incr("counter"); }
                return null;
            }));
            for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
            ex.shutdown();
            try (KvClient c = new KvClient("127.0.0.1", port)) { eq("4000", c.get("counter")); }
        });
        test("HELLO handshake: RESP2 accepted, RESP3 politely refused", () -> {
            try (KvClient c = new KvClient("127.0.0.1", port)) {
                check(c.call("HELLO", "2") instanceof List<?> l && l.contains("mini-redis"), "hello 2");
                check(c.call("HELLO", "3") instanceof Err e && e.message().startsWith("NOPROTO"), "hello 3");
                eq("PONG", c.ping());
            }
        });
        test("lists, hashes and INFO over TCP", () -> {
            try (KvClient c = new KvClient("127.0.0.1", port)) {
                eq(2L, c.call("RPUSH", "q", "a", "b"));
                eq(List.of("a", "b"), c.call("LRANGE", "q", "0", "-1"));
                eq(2L, c.call("HSET", "h", "f1", "v1", "f2", "v2"));
                eq(List.of("f1", "v1", "f2", "v2"), c.call("HGETALL", "h"));
                check(c.call("GET", "q") instanceof Err e && e.message().startsWith("WRONGTYPE"), "wrongtype");
                check(((String) c.call("INFO")).contains("total_commands_processed"), "info");
            }
        });
        srv.close();

        System.out.println("HTTP gateway (for the web UI)");
        test("info, keys, key detail, exec, errors and bench over HTTP", () -> {
            Server g = new Server(new Server.Config("127.0.0.1", 0, null, Aof.Fsync.NO, 100, 0));
            g.start();
            try {
                HttpClient h = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + g.httpPort();
                java.util.function.BiFunction<String, String, HttpResponse<String>> call = (path, body) -> {
                    try {
                        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
                        b = body == null ? b.GET() : b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                        return h.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) { throw new RuntimeException(e); }
                };
                eq(200, call.apply("/api/health", null).statusCode());

                var r = call.apply("/api/exec", "{\"command\":\"SET user:1 \\\"Mohit Yadav\\\" EX 100\"}");
                eq(200, r.statusCode());
                @SuppressWarnings("unchecked") Map<String, Object> reply = (Map<String, Object>) Json.parse(r.body());
                eq("simple", reply.get("kind"));
                eq("OK", reply.get("display"));
                call.apply("/api/exec", "{\"args\":[\"RPUSH\",\"queue\",\"a\",\"b\"]}");
                call.apply("/api/exec", "{\"command\":\"HSET profile name mohit city delhi\"}");

                @SuppressWarnings("unchecked") Map<String, Object> keys = (Map<String, Object>) Json.parse(call.apply("/api/keys?pattern=*", null).body());
                eq(3L, keys.get("total"));
                @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) keys.get("keys");
                eq("profile", list.get(0).get("key"));
                eq("hash", list.get(0).get("type"));
                check(((Number) list.get(2).get("ttlMs")).longValue() > 90_000, "ttl of user:1 is listed");

                @SuppressWarnings("unchecked") Map<String, Object> det = (Map<String, Object>) Json.parse(call.apply("/api/key?name=user%3A1", null).body());
                eq("Mohit Yadav", det.get("value"));
                @SuppressWarnings("unchecked") Map<String, Object> hd = (Map<String, Object>) Json.parse(call.apply("/api/key?name=profile", null).body());
                eq(2, ((List<?>) hd.get("value")).size());
                eq(404, call.apply("/api/key?name=nope", null).statusCode());

                eq(400, call.apply("/api/exec", "{}").statusCode());
                eq(400, call.apply("/api/exec", "{oops").statusCode());
                @SuppressWarnings("unchecked") Map<String, Object> bad = (Map<String, Object>) Json.parse(call.apply("/api/exec", "{\"command\":\"GET queue\"}").body());
                eq("error", bad.get("kind"));
                check(String.valueOf(bad.get("display")).contains("WRONGTYPE"), "wrongtype shown");

                @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) Json.parse(call.apply("/api/info", null).body());
                check(((Number) info.get("totalCommands")).longValue() >= 4, "commands counted: " + info.get("totalCommands"));
                check(((Map<?, ?>) info.get("commandCounts")).containsKey("SET"), "per-command counts");
                check(!((List<?>) info.get("recent")).isEmpty(), "recent commands listed");

                @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) Json.parse(call.apply("/api/bench", "{\"clients\":4,\"requests\":200,\"valueBytes\":16}").body());
                eq(1600L, b.get("requests") instanceof Number n ? n.longValue() : -1L);
                eq(0L, b.get("errors") instanceof Number n ? n.longValue() : -1L);
                check(((Number) b.get("opsPerSec")).longValue() > 0, "ops/sec reported");
                eq(3L, ((Map<?, ?>) Json.parse(call.apply("/api/keys?pattern=*", null).body())).get("total")); // bench keys were cleaned up
            } finally { g.close(); }
        });

        System.out.println("Limits");
        test("max clients: extra connection is refused with an error", () -> {
            Server small = start(null, 2);
            try (Socket a = new Socket("127.0.0.1", small.port()); Socket b = new Socket("127.0.0.1", small.port())) {
                Thread.sleep(150);
                try (Socket c = new Socket("127.0.0.1", small.port())) {
                    Object r = Resp.readReply(new BufferedInputStream(c.getInputStream()));
                    check(r instanceof Err e && e.message().contains("max number of clients"), "got " + r);
                }
            } finally { small.close(); }
        });

        System.out.println("Persistence (AOF)");
        test("data survives a restart, including lists, hashes and TTL", () -> {
            Path f = tmp.resolve("a.aof");
            Server s1 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s1.port())) {
                c.set("name", "mohit");
                c.incr("visits"); c.incr("visits");
                c.call("RPUSH", "q", "1", "2", "3");
                c.call("HSET", "h", "a", "1");
                c.set("session", "tok", 60_000);
                c.call("DEL", "gone");
                c.set("gone", "x");
                c.del("gone");
            }
            s1.close();
            Server s2 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s2.port())) {
                eq("mohit", c.get("name"));
                eq("2", c.get("visits"));
                eq(List.of("1", "2", "3"), c.call("LRANGE", "q", "0", "-1"));
                eq("1", c.call("HGET", "h", "a"));
                check(c.ttl("session") > 50, "TTL kept across restart: " + c.ttl("session"));
                eq(null, c.get("gone"));
            }
            s2.close();
        });
        test("keys that expired while the server was down are gone after restart", () -> {
            Path f = tmp.resolve("b.aof");
            Server s1 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s1.port())) { c.set("short", "v", 100); c.set("long", "v"); }
            s1.close();
            Thread.sleep(250);
            Server s2 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s2.port())) {
                eq(null, c.get("short"));
                eq("v", c.get("long"));
            }
            s2.close();
        });
        test("BGREWRITEAOF shrinks the file and keeps the data", () -> {
            Path f = tmp.resolve("c.aof");
            Server s1 = start(f, 10);
            long before;
            try (KvClient c = new KvClient("127.0.0.1", s1.port())) {
                for (int i = 0; i < 500; i++) c.incr("n");
                c.set("keep", "me");
                before = Files.size(f);
                eq(new Simple("OK"), c.call("BGREWRITEAOF"));
                c.incr("n"); // writes after a rewrite must still be logged
            }
            long after = Files.size(f);
            check(after < before / 5, "file should shrink a lot: " + before + " -> " + after);
            s1.close();
            Server s2 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s2.port())) {
                eq("501", c.get("n"));
                eq("me", c.get("keep"));
            }
            s2.close();
        });
        test("torn last command (crash mid-write) is cut off, server still starts and keeps logging", () -> {
            Path f = tmp.resolve("d.aof");
            Files.write(f, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n*3\r\n$3\r\nSET\r\n$1\r\nz\r\n$1".getBytes(StandardCharsets.UTF_8));
            Server s1 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s1.port())) {
                eq("v", c.get("k"));
                eq(null, c.get("z"));
                c.set("after", "crash");
            }
            s1.close();
            Server s2 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s2.port())) {
                eq("v", c.get("k"));
                eq("crash", c.get("after"));
            }
            s2.close();
        });
        test("rewrite is safe while 8 clients keep incrementing (no lost or doubled updates)", () -> {
            Path f = tmp.resolve("e.aof");
            Server s1 = start(f, 50);
            ExecutorService ex = Executors.newFixedThreadPool(9);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) fs.add(ex.submit(() -> {
                try (KvClient c = new KvClient("127.0.0.1", s1.port())) { for (int j = 0; j < 300; j++) c.incr("hot"); }
                return null;
            }));
            fs.add(ex.submit(() -> {
                try (KvClient c = new KvClient("127.0.0.1", s1.port())) { for (int j = 0; j < 5; j++) { c.call("BGREWRITEAOF"); Thread.sleep(20); } }
                return null;
            }));
            for (Future<?> fu : fs) fu.get(30, TimeUnit.SECONDS);
            ex.shutdown();
            try (KvClient c = new KvClient("127.0.0.1", s1.port())) { eq("2400", c.get("hot")); }
            s1.close();
            Server s2 = start(f, 10);
            try (KvClient c = new KvClient("127.0.0.1", s2.port())) { eq("2400", c.get("hot")); }
            s2.close();
        });

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
