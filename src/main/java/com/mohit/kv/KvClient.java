package com.mohit.kv;

import com.mohit.kv.Resp.Err;

import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

/** Minimal blocking client. call() returns the raw reply (including Err); the helpers throw on errors. */
public class KvClient implements Closeable {
    public static final class KvException extends RuntimeException {
        public KvException(String m) { super(m); }
    }

    private final Socket socket;
    private final BufferedInputStream in;
    private final BufferedOutputStream out;

    public KvClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    public Object call(String... args) throws IOException {
        Resp.writeCommand(out, Arrays.asList(args));
        out.flush();
        return Resp.readReply(in);
    }

    private Object ok(String... args) throws IOException {
        Object r = call(args);
        if (r instanceof Err e) throw new KvException(e.message());
        return r;
    }

    public String ping() throws IOException { return ((Resp.Simple) ok("PING")).text(); }
    public void set(String k, String v) throws IOException { ok("SET", k, v); }
    public void set(String k, String v, long ttlMs) throws IOException { ok("SET", k, v, "PX", Long.toString(ttlMs)); }
    public String get(String k) throws IOException { return (String) ok("GET", k); }
    public long del(String... keys) throws IOException {
        String[] a = new String[keys.length + 1];
        a[0] = "DEL";
        System.arraycopy(keys, 0, a, 1, keys.length);
        return (Long) ok(a);
    }
    public long incr(String k) throws IOException { return (Long) ok("INCR", k); }
    public long ttl(String k) throws IOException { return (Long) ok("TTL", k); }
    @SuppressWarnings("unchecked")
    public List<String> keys(String glob) throws IOException { return (List<String>) ok("KEYS", glob); }

    @Override
    public void close() throws IOException { socket.close(); }
}
