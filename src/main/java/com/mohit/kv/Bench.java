package com.mohit.kv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/** Load test: java -cp out com.mohit.kv.Bench [clients=20] [requestsPerClient=5000] [valueBytes=64] [port=6380] */
public class Bench {
    public record Result(int clients, int requests, int valueBytes, double opsPerSec, double seconds,
                         double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs, long errors) { }

    /** Every client does SET then GET on its own 100 keys; the keys are deleted afterwards. */
    public static Result run(String host, int port, int clients, int perClient, int valueBytes) throws Exception {
        String value = "x".repeat(valueBytes);
        long[][] lat = new long[clients][perClient * 2];
        CountDownLatch ready = new CountDownLatch(clients), go = new CountDownLatch(1), done = new CountDownLatch(clients);
        AtomicLong errors = new AtomicLong();

        for (int c = 0; c < clients; c++) {
            final int id = c;
            Thread t = new Thread(() -> {
                try (KvClient cl = new KvClient(host, port)) {
                    ready.countDown();
                    go.await();
                    int idx = 0;
                    for (int i = 0; i < perClient; i++) {
                        String key = "bench:" + id + ":" + (i % 100);
                        long t0 = System.nanoTime();
                        cl.set(key, value);
                        lat[id][idx++] = System.nanoTime() - t0;
                        t0 = System.nanoTime();
                        String got = cl.get(key);
                        lat[id][idx++] = System.nanoTime() - t0;
                        if (!value.equals(got)) errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    ready.countDown();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        done.await();
        double secs = (System.nanoTime() - start) / 1e9;

        long[] all = Arrays.stream(lat).flatMapToLong(Arrays::stream).filter(x -> x > 0).sorted().toArray();
        cleanup(host, port);
        if (all.length == 0) return new Result(clients, 0, valueBytes, 0, secs, 0, 0, 0, 0, 0, errors.get());
        return new Result(clients, all.length, valueBytes, all.length / secs, secs,
                Arrays.stream(all).average().orElse(0) / 1e6, p(all, .50), p(all, .95), p(all, .99),
                all[all.length - 1] / 1e6, errors.get());
    }

    private static void cleanup(String host, int port) {
        try (KvClient c = new KvClient(host, port)) {
            List<String> keys = c.keys("bench:*");
            for (int i = 0; i < keys.size(); i += 500) c.del(keys.subList(i, Math.min(keys.size(), i + 500)).toArray(new String[0]));
        } catch (Exception ignored) { /* best effort */ }
    }

    private static double p(long[] sorted, double q) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(sorted.length * q) - 1)] / 1e6;
    }

    public static void main(String[] a) throws Exception {
        int clients = a.length > 0 ? Integer.parseInt(a[0]) : 20;
        int perClient = a.length > 1 ? Integer.parseInt(a[1]) : 5000;
        int valueBytes = a.length > 2 ? Integer.parseInt(a[2]) : 64;
        int port = a.length > 3 ? Integer.parseInt(a[3]) : 6380;
        Result r = run("127.0.0.1", port, clients, perClient, valueBytes);
        System.out.printf("clients=%d requests=%d (SET+GET) value=%dB%n", r.clients(), r.requests(), r.valueBytes());
        System.out.printf("throughput : %,.0f ops/sec in %.2fs%n", r.opsPerSec(), r.seconds());
        System.out.printf("latency ms : avg %.3f  p50 %.3f  p95 %.3f  p99 %.3f  max %.3f%n",
                r.avgMs(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs());
        System.out.println("errors     : " + r.errors());
    }
}
