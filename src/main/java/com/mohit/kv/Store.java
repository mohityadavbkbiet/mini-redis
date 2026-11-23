package com.mohit.kv;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The data layer: a ConcurrentHashMap from key to Entry. Values are String, ArrayDeque (list) or
 * LinkedHashMap (hash). Every change goes through ConcurrentHashMap.compute(), which locks only that key,
 * so operations on one key are atomic (INCR never loses updates) while different keys run in parallel.
 *
 * Expiry is lazy (checked on access) plus a background sweep (see sweep()).
 */
public class Store {
    public static final class WrongTypeException extends RuntimeException { }
    public static final class NotIntegerException extends RuntimeException { }

    /** expireAt is an absolute time in epoch milliseconds, or -1 for no expiry. */
    record Entry(Object value, long expireAt) { }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Store() { this(System::currentTimeMillis); }
    public Store(LongSupplier clock) { this.clock = clock; }

    public long now() { return clock.getAsLong(); }
    private boolean expired(Entry e, long now) { return e.expireAt >= 0 && e.expireAt <= now; }

    /** Returns the live entry, removing it if it has expired. */
    private Entry live(String key) {
        Entry e = map.get(key);
        if (e == null) return null;
        if (expired(e, now())) { map.remove(key, e); return null; }
        return e;
    }

    /** Atomically rewrites one key. fn receives null if the key is missing or expired; returning null deletes it. */
    private void update(String key, UnaryOperator<Entry> fn) {
        map.compute(key, (k, old) -> {
            if (old != null && expired(old, now())) old = null;
            return fn.apply(old);
        });
    }

    /** Atomically reads a mutable value (list/hash) without letting writers change it meanwhile. */
    @SuppressWarnings("unchecked")
    private <R> R read(String key, Function<Entry, R> fn) {
        Object[] out = new Object[1];
        map.computeIfPresent(key, (k, e) -> {
            if (expired(e, now())) return null;
            out[0] = fn.apply(e);
            return e;
        });
        return (R) out[0];
    }

    // ---------- strings ----------

    public String get(String key) {
        Entry e = live(key);
        if (e == null) return null;
        if (!(e.value instanceof String s)) throw new WrongTypeException();
        return s;
    }

    /** SET. Overwrites any type and clears the old TTL. Returns false when NX/XX prevented it. */
    public boolean set(String key, String value, long expireAt, boolean nx, boolean xx) {
        boolean[] done = {false};
        update(key, old -> {
            if ((nx && old != null) || (xx && old == null)) return old;
            done[0] = true;
            return new Entry(value, expireAt);
        });
        return done[0];
    }

    public long incrBy(String key, long delta) {
        long[] res = new long[1];
        update(key, old -> {
            long cur = 0, exp = -1;
            if (old != null) {
                if (!(old.value instanceof String s)) throw new WrongTypeException();
                try { cur = Long.parseLong(s); } catch (NumberFormatException e) { throw new NotIntegerException(); }
                exp = old.expireAt;
            }
            try { cur = Math.addExact(cur, delta); } catch (ArithmeticException e) { throw new NotIntegerException(); }
            res[0] = cur;
            return new Entry(Long.toString(cur), exp);
        });
        return res[0];
    }

    public long append(String key, String suffix) {
        long[] len = new long[1];
        update(key, old -> {
            String cur = "";
            long exp = -1;
            if (old != null) {
                if (!(old.value instanceof String s)) throw new WrongTypeException();
                cur = s;
                exp = old.expireAt;
            }
            String next = cur + suffix;
            len[0] = next.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return new Entry(next, exp);
        });
        return len[0];
    }

    // ---------- keys ----------

    public int del(List<String> keys) {
        int n = 0;
        for (String k : keys) {
            Entry e = map.remove(k);
            if (e != null && !expired(e, now())) n++;
        }
        return n;
    }

    public boolean exists(String key) { return live(key) != null; }

    public String type(String key) {
        Entry e = live(key);
        if (e == null) return "none";
        return e.value instanceof String ? "string" : e.value instanceof ArrayDeque ? "list" : "hash";
    }

    /** Sets an absolute expiry time. Returns false if the key does not exist. */
    public boolean pexpireAt(String key, long at) {
        boolean[] ok = {false};
        update(key, old -> {
            if (old == null) return null;
            ok[0] = true;
            return new Entry(old.value, at);
        });
        return ok[0];
    }

    /** Remaining milliseconds; -2 if the key is missing, -1 if it has no expiry. */
    public long pttl(String key) {
        Entry e = live(key);
        if (e == null) return -2;
        if (e.expireAt < 0) return -1;
        return Math.max(0, e.expireAt - now());
    }

    public boolean persist(String key) {
        boolean[] ok = {false};
        update(key, old -> {
            if (old == null || old.expireAt < 0) return old;
            ok[0] = true;
            return new Entry(old.value, -1);
        });
        return ok[0];
    }

    public List<String> keys(String glob) {
        Pattern p = Pattern.compile(globToRegex(glob), Pattern.DOTALL);
        long now = now();
        List<String> out = new ArrayList<>();
        for (var en : map.entrySet()) {
            if (!expired(en.getValue(), now) && p.matcher(en.getKey()).matches()) out.add(en.getKey());
        }
        Collections.sort(out);
        return out;
    }

    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '[' -> {
                    int end = glob.indexOf(']', i + 1);
                    if (end < 0) sb.append("\\[");
                    else { sb.append('[').append(glob, i + 1, end).append(']'); i = end; }
                }
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    public int dbsize() {
        long now = now();
        int n = 0;
        for (Entry e : map.values()) if (!expired(e, now)) n++;
        return n;
    }

    /** Raw entry count including expired entries that have not been swept yet (for tests and INFO). */
    public int rawSize() { return map.size(); }

    public void flush() { map.clear(); }

    /**
     * Removes expired entries nobody has touched. Redis samples random keys; a full scan is simpler and
     * exact, and is fine at this scale (cost grows with the number of keys).
     */
    public int sweep() {
        long now = now();
        int removed = 0;
        for (var en : map.entrySet()) {
            if (expired(en.getValue(), now) && map.remove(en.getKey(), en.getValue())) removed++;
        }
        return removed;
    }

    // ---------- lists ----------

    @SuppressWarnings("unchecked")
    private static ArrayDeque<String> asList(Entry e) {
        if (!(e.value instanceof ArrayDeque)) throw new WrongTypeException();
        return (ArrayDeque<String>) e.value;
    }

    public int push(String key, List<String> values, boolean left) {
        int[] size = new int[1];
        update(key, old -> {
            ArrayDeque<String> d = old == null ? new ArrayDeque<>() : asList(old);
            for (String v : values) { if (left) d.addFirst(v); else d.addLast(v); }
            size[0] = d.size();
            return new Entry(d, old == null ? -1 : old.expireAt);
        });
        return size[0];
    }

    public String pop(String key, boolean left) {
        String[] res = new String[1];
        update(key, old -> {
            if (old == null) return null;
            ArrayDeque<String> d = asList(old);
            res[0] = left ? d.pollFirst() : d.pollLast();
            return d.isEmpty() ? null : old; // Redis deletes empty lists
        });
        return res[0];
    }

    public int llen(String key) {
        Integer n = read(key, e -> asList(e).size());
        return n == null ? 0 : n;
    }

    public List<String> lrange(String key, long start, long stop) {
        List<String> res = read(key, e -> {
            List<String> all = new ArrayList<>(asList(e));
            int len = all.size();
            long s = start < 0 ? start + len : start, t = stop < 0 ? stop + len : stop;
            s = Math.max(0, s);
            if (t >= len) t = len - 1;
            if (s > t) return new ArrayList<String>();
            return new ArrayList<>(all.subList((int) s, (int) t + 1));
        });
        return res == null ? List.of() : res;
    }

    // ---------- hashes ----------

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> asHash(Entry e) {
        if (!(e.value instanceof LinkedHashMap)) throw new WrongTypeException();
        return (LinkedHashMap<String, String>) e.value;
    }

    /** pairs = field1, value1, field2, value2... Returns how many fields were new. */
    public int hset(String key, List<String> pairs) {
        int[] added = new int[1];
        update(key, old -> {
            LinkedHashMap<String, String> h = old == null ? new LinkedHashMap<>() : asHash(old);
            for (int i = 0; i + 1 < pairs.size(); i += 2) {
                if (h.put(pairs.get(i), pairs.get(i + 1)) == null) added[0]++;
            }
            return new Entry(h, old == null ? -1 : old.expireAt);
        });
        return added[0];
    }

    public String hget(String key, String field) { return read(key, e -> asHash(e).get(field)); }

    public int hdel(String key, List<String> fields) {
        int[] n = new int[1];
        update(key, old -> {
            if (old == null) return null;
            LinkedHashMap<String, String> h = asHash(old);
            for (String f : fields) if (h.remove(f) != null) n[0]++;
            return h.isEmpty() ? null : old;
        });
        return n[0];
    }

    public List<String> hgetall(String key) {
        List<String> res = read(key, e -> {
            List<String> flat = new ArrayList<>();
            asHash(e).forEach((f, v) -> { flat.add(f); flat.add(v); });
            return flat;
        });
        return res == null ? List.of() : res;
    }

    public int hlen(String key) {
        Integer n = read(key, e -> asHash(e).size());
        return n == null ? 0 : n;
    }

    // ---------- inspection (used by the HTTP gateway / UI) ----------

    public record KeyInfo(String key, String type, long ttlMs, int size) { }

    public KeyInfo info(String key) {
        Entry e = live(key);
        if (e == null) return null;
        int size = e.value instanceof String s ? s.length() : llenOrHlen(key);
        return new KeyInfo(key, type(key), pttl(key), size);
    }

    private int llenOrHlen(String key) {
        Integer n = read(key, e -> e.value instanceof ArrayDeque<?> d ? d.size() : ((LinkedHashMap<?, ?>) e.value).size());
        return n == null ? 0 : n;
    }

    /** String, or the list's items, or the hash as a flat field/value list. Null if the key is missing. */
    public Object value(String key) {
        Entry e = live(key);
        if (e == null) return null;
        if (e.value instanceof String s) return s;
        if (e.value instanceof ArrayDeque) return lrange(key, 0, -1);
        return hgetall(key);
    }

    // ---------- persistence support ----------

    /** The whole dataset as write commands (used by AOF rewrite). Call while no writes are running. */
    @SuppressWarnings("unchecked")
    public List<List<String>> snapshot() {
        long now = now();
        List<List<String>> cmds = new ArrayList<>();
        for (var en : map.entrySet()) {
            Entry e = en.getValue();
            if (expired(e, now)) continue;
            String k = en.getKey();
            if (e.value instanceof String s) cmds.add(List.of("SET", k, s));
            else if (e.value instanceof ArrayDeque<?> d) {
                List<String> c = new ArrayList<>(List.of("RPUSH", k));
                c.addAll((Collection<String>) d);
                cmds.add(c);
            } else {
                List<String> c = new ArrayList<>(List.of("HSET", k));
                ((LinkedHashMap<String, String>) e.value).forEach((f, v) -> { c.add(f); c.add(v); });
                cmds.add(c);
            }
            if (e.expireAt >= 0) cmds.add(List.of("PEXPIREAT", k, Long.toString(e.expireAt)));
        }
        return cmds;
    }
}
