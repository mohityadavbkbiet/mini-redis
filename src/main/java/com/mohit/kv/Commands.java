package com.mohit.kv;

import com.mohit.kv.Resp.Err;
import com.mohit.kv.Resp.Simple;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Executes parsed commands against the Store and logs writes to the AOF.
 *
 * Locking: normal commands take the read lock (so they run in parallel; per-key atomicity comes from the Store).
 * BGREWRITEAOF takes the write lock, so no command can be half-applied while the snapshot is taken.
 * Without that, an INCR executed but not yet logged could end up counted twice after a rewrite.
 */
public class Commands {
    private static final class Arity extends RuntimeException { }

    private final Store store;
    private final Stats stats;
    private Aof aof;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public Commands(Store store, Stats stats) {
        this.store = store;
        this.stats = stats;
    }

    public void setAof(Aof aof) { this.aof = aof; }

    /** @param log false while replaying the AOF (do not log what we are replaying) */
    public Object execute(List<String> a, boolean log) {
        String cmd = a.get(0).toUpperCase(Locale.ROOT);
        if (log) stats.record(cmd, a.size() > 1 ? a.get(1) : "");
        try {
            if (cmd.equals("BGREWRITEAOF") || cmd.equals("REWRITEAOF")) return rewriteAof();
            lock.readLock().lock();
            try {
                return dispatch(cmd, a, log);
            } finally {
                lock.readLock().unlock();
            }
        } catch (Arity e) {
            return new Err("ERR wrong number of arguments for '" + cmd.toLowerCase(Locale.ROOT) + "' command");
        } catch (Store.WrongTypeException e) {
            return new Err("WRONGTYPE Operation against a key holding the wrong kind of value");
        } catch (Store.NotIntegerException | NumberFormatException e) {
            return new Err("ERR value is not an integer or out of range");
        } catch (RuntimeException e) {
            return new Err("ERR internal error: " + e);
        }
    }

    private Object rewriteAof() {
        if (aof == null) return new Err("ERR AOF is disabled");
        lock.writeLock().lock();
        try {
            aof.rewrite(store.snapshot());
            return Resp.OK;
        } catch (IOException e) {
            return new Err("ERR AOF rewrite failed: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void arity(List<String> a, int min, int max) {
        if (a.size() < min || a.size() > max) throw new Arity();
    }

    private void log(boolean on, String... args) {
        if (!on || aof == null) return;
        try { aof.append(List.of(args)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static long num(String s) { return Long.parseLong(s); }

    private Object dispatch(String cmd, List<String> a, boolean on) {
        switch (cmd) {
            case "PING" -> { arity(a, 1, 2); return a.size() == 2 ? a.get(1) : new Simple("PONG"); }
            case "ECHO" -> { arity(a, 2, 2); return a.get(1); }
            case "QUIT" -> { return Resp.OK; }
            case "SELECT" -> { arity(a, 2, 2); return a.get(1).equals("0") ? Resp.OK : new Err("ERR DB index is out of range"); }
            case "CLIENT" -> { return Resp.OK; }              // redis-py sends CLIENT SETINFO on connect
            case "COMMAND", "CONFIG" -> { return List.of(); } // redis-cli probes these on connect
            case "INFO" -> { return info(); }
            case "HELLO" -> {
                // Newer clients negotiate the protocol on connect. We only speak RESP2.
                if (a.size() > 1 && !a.get(1).equals("2")) return new Err("NOPROTO unsupported protocol version");
                return List.of("server", "mini-redis", "version", "1.0.0", "proto", 2L, "id", 1L,
                        "mode", "standalone", "role", "master", "modules", List.of());
            }

            case "SET" -> {
                arity(a, 3, 8);
                String k = a.get(1), v = a.get(2);
                boolean nx = false, xx = false;
                long exp = -1;
                for (int i = 3; i < a.size(); i++) {
                    String o = a.get(i).toUpperCase(Locale.ROOT);
                    switch (o) {
                        case "NX" -> nx = true;
                        case "XX" -> xx = true;
                        case "EX", "PX" -> {
                            if (i + 1 >= a.size()) return new Err("ERR syntax error");
                            long n = num(a.get(++i));
                            if (n <= 0) return new Err("ERR invalid expire time in 'set' command");
                            exp = store.now() + (o.equals("EX") ? n * 1000 : n);
                        }
                        default -> { return new Err("ERR syntax error"); }
                    }
                }
                if (nx && xx) return new Err("ERR syntax error");
                if (!store.set(k, v, exp, nx, xx)) return null;
                log(on, "SET", k, v);
                if (exp >= 0) log(on, "PEXPIREAT", k, Long.toString(exp)); // absolute time, so replay is exact
                return Resp.OK;
            }
            case "GET" -> { arity(a, 2, 2); return store.get(a.get(1)); }
            case "MSET" -> {
                if (a.size() < 3 || a.size() % 2 == 0) throw new Arity();
                for (int i = 1; i < a.size(); i += 2) {
                    store.set(a.get(i), a.get(i + 1), -1, false, false);
                    log(on, "SET", a.get(i), a.get(i + 1));
                }
                return Resp.OK;
            }
            case "MGET" -> {
                arity(a, 2, Integer.MAX_VALUE);
                List<String> out = new ArrayList<>();
                for (int i = 1; i < a.size(); i++) {
                    try { out.add(store.get(a.get(i))); } catch (Store.WrongTypeException e) { out.add(null); }
                }
                return out;
            }
            case "DEL" -> {
                arity(a, 2, Integer.MAX_VALUE);
                int n = store.del(a.subList(1, a.size()));
                if (n > 0) log(on, a.toArray(new String[0]));
                return (long) n;
            }
            case "EXISTS" -> {
                arity(a, 2, Integer.MAX_VALUE);
                long n = 0;
                for (int i = 1; i < a.size(); i++) if (store.exists(a.get(i))) n++;
                return n;
            }
            case "TYPE" -> { arity(a, 2, 2); return new Simple(store.type(a.get(1))); }
            case "KEYS" -> { arity(a, 2, 2); return store.keys(a.get(1)); }
            case "DBSIZE" -> { arity(a, 1, 1); return (long) store.dbsize(); }
            case "FLUSHALL", "FLUSHDB" -> { store.flush(); log(on, "FLUSHALL"); return Resp.OK; }

            case "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT" -> {
                arity(a, 3, 3);
                long n = num(a.get(2));
                long at = switch (cmd) {
                    case "EXPIRE" -> store.now() + n * 1000;
                    case "PEXPIRE" -> store.now() + n;
                    case "EXPIREAT" -> n * 1000;
                    default -> n;
                };
                boolean ok = store.pexpireAt(a.get(1), at);
                if (ok) log(on, "PEXPIREAT", a.get(1), Long.toString(at));
                return ok ? 1L : 0L;
            }
            case "TTL" -> { arity(a, 2, 2); long p = store.pttl(a.get(1)); return p < 0 ? p : (p + 500) / 1000; }
            case "PTTL" -> { arity(a, 2, 2); return store.pttl(a.get(1)); }
            case "PERSIST" -> {
                arity(a, 2, 2);
                boolean ok = store.persist(a.get(1));
                if (ok) log(on, "PERSIST", a.get(1));
                return ok ? 1L : 0L;
            }

            case "INCR", "DECR" -> {
                arity(a, 2, 2);
                long r = store.incrBy(a.get(1), cmd.equals("INCR") ? 1 : -1);
                log(on, a.toArray(new String[0]));
                return r;
            }
            case "INCRBY", "DECRBY" -> {
                arity(a, 3, 3);
                long d = num(a.get(2));
                long r = store.incrBy(a.get(1), cmd.equals("INCRBY") ? d : -d);
                log(on, a.toArray(new String[0]));
                return r;
            }
            case "APPEND" -> {
                arity(a, 3, 3);
                long r = store.append(a.get(1), a.get(2));
                log(on, a.toArray(new String[0]));
                return r;
            }
            case "STRLEN" -> {
                arity(a, 2, 2);
                String s = store.get(a.get(1));
                return s == null ? 0L : (long) s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }

            case "LPUSH", "RPUSH" -> {
                arity(a, 3, Integer.MAX_VALUE);
                long r = store.push(a.get(1), a.subList(2, a.size()), cmd.equals("LPUSH"));
                log(on, a.toArray(new String[0]));
                return r;
            }
            case "LPOP", "RPOP" -> {
                arity(a, 2, 2);
                String r = store.pop(a.get(1), cmd.equals("LPOP"));
                if (r != null) log(on, a.toArray(new String[0]));
                return r;
            }
            case "LLEN" -> { arity(a, 2, 2); return (long) store.llen(a.get(1)); }
            case "LRANGE" -> { arity(a, 4, 4); return store.lrange(a.get(1), num(a.get(2)), num(a.get(3))); }

            case "HSET" -> {
                if (a.size() < 4 || a.size() % 2 != 0) throw new Arity();
                long r = store.hset(a.get(1), a.subList(2, a.size()));
                log(on, a.toArray(new String[0]));
                return r;
            }
            case "HGET" -> { arity(a, 3, 3); return store.hget(a.get(1), a.get(2)); }
            case "HDEL" -> {
                arity(a, 3, Integer.MAX_VALUE);
                long r = store.hdel(a.get(1), a.subList(2, a.size()));
                if (r > 0) log(on, a.toArray(new String[0]));
                return r;
            }
            case "HGETALL" -> { arity(a, 2, 2); return store.hgetall(a.get(1)); }
            case "HLEN" -> { arity(a, 2, 2); return (long) store.hlen(a.get(1)); }

            default -> { return new Err("ERR unknown command '" + a.get(0) + "'"); }
        }
    }

    private String info() {
        long up = (System.currentTimeMillis() - stats.startedAt) / 1000;
        return "# Server\r\nmini_redis_version:1.0\r\nuptime_in_seconds:" + up
                + "\r\n# Clients\r\nconnected_clients:" + stats.connectedClients.get()
                + "\r\n# Stats\r\ntotal_connections_received:" + stats.connectionsTotal.get()
                + "\r\ntotal_commands_processed:" + stats.commands.get()
                + "\r\n# Persistence\r\naof_enabled:" + (aof != null ? 1 : 0)
                + "\r\naof_size_bytes:" + (aof != null ? aof.sizeBytes() : 0)
                + "\r\n# Keyspace\r\ndb0:keys=" + store.dbsize() + ",raw_entries=" + store.rawSize() + "\r\n";
    }
}
