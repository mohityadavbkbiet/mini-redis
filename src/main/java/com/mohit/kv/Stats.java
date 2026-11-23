package com.mohit.kv;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

public class Stats {
    public record Recent(long at, String text) { }

    private static final int RING = 40;

    public final long startedAt = System.currentTimeMillis();
    public final AtomicLong commands = new AtomicLong();
    public final AtomicLong connectionsTotal = new AtomicLong();
    public final AtomicInteger connectedClients = new AtomicInteger();

    private final ConcurrentHashMap<String, LongAdder> byCommand = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<Recent> ring = new AtomicReferenceArray<>(RING);
    private final AtomicLong ringPos = new AtomicLong();

    /** Counts one executed command (name is already upper case) and remembers it for the activity feed. */
    public void record(String cmd, String firstArg) {
        commands.incrementAndGet();
        LongAdder a = byCommand.get(cmd);
        if (a == null && byCommand.size() < 200) a = byCommand.computeIfAbsent(cmd, k -> new LongAdder());
        if (a != null) a.increment();
        String arg = firstArg.length() > 40 ? firstArg.substring(0, 40) + "..." : firstArg;
        ring.set((int) (ringPos.getAndIncrement() % RING), new Recent(System.currentTimeMillis(), arg.isEmpty() ? cmd : cmd + " " + arg));
    }

    public Map<String, Long> commandCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        byCommand.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum()))
                .forEach(e -> out.put(e.getKey(), e.getValue().sum()));
        return out;
    }

    public List<Recent> recent(int limit) {
        List<Recent> all = new ArrayList<>();
        for (int i = 0; i < RING; i++) { Recent r = ring.get(i); if (r != null) all.add(r); }
        all.sort((x, y) -> Long.compare(y.at(), x.at()));
        return all.subList(0, Math.min(limit, all.size()));
    }
}
