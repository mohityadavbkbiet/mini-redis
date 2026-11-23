package com.mohit.kv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RESP (REdis Serialization Protocol) v2 reader and writer. Because we speak the real protocol,
 * redis-cli and client libraries such as redis-py can talk to this server.
 *
 * Replies are plain Java objects: null = nil, Long = integer, String = bulk string,
 * List = array, Simple = status line (+OK), Err = error line (-ERR ...).
 */
public final class Resp {
    public record Simple(String text) { }
    public record Err(String message) { }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String m) { super(m); }
    }

    static final int MAX_BULK = 64 * 1024 * 1024, MAX_ARGS = 1_000_000, MAX_LINE = 64 * 1024;
    public static final Simple OK = new Simple("OK");

    private Resp() { }

    /** Reads one line without its CRLF. Returns null on clean EOF (nothing read). */
    static String readLine(InputStream in) throws IOException {
        byte[] buf = new byte[64];
        int n = 0, c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (n > 0 && buf[n - 1] == '\r') n--;
                return new String(buf, 0, n, StandardCharsets.UTF_8);
            }
            if (n == MAX_LINE) throw new ProtocolException("line too long");
            if (n == buf.length) buf = Arrays.copyOf(buf, n * 2);
            buf[n++] = (byte) c;
        }
        if (n == 0) return null;
        throw new EOFException("truncated line");
    }

    private static int parseLen(String s) throws ProtocolException {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { throw new ProtocolException("invalid length '" + s + "'"); }
    }

    /**
     * Reads one command: either a RESP array of bulk strings (what clients send) or an inline
     * command such as "SET a 1" (what you type in telnet/nc). Returns null at EOF, an empty list for a blank line.
     */
    public static List<String> readCommand(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        if (line.isEmpty()) return List.of();
        if (line.charAt(0) != '*') return inline(line);

        int n = parseLen(line.substring(1));
        if (n <= 0) return List.of();
        if (n > MAX_ARGS) throw new ProtocolException("too many arguments");
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String h = readLine(in);
            if (h == null) throw new EOFException("truncated command");
            if (h.isEmpty() || h.charAt(0) != '$') throw new ProtocolException("expected '$', got '" + (h.isEmpty() ? "" : h.charAt(0)) + "'");
            int len = parseLen(h.substring(1));
            if (len < 0 || len > MAX_BULK) throw new ProtocolException("invalid bulk length");
            byte[] data = in.readNBytes(len);
            if (data.length < len) throw new EOFException("truncated bulk string");
            int cr = in.read(), lf = in.read();
            if (cr == -1 || lf == -1) throw new EOFException("truncated bulk terminator");
            if (cr != '\r' || lf != '\n') throw new ProtocolException("bad bulk terminator");
            args.add(new String(data, StandardCharsets.UTF_8));
        }
        return args;
    }

    /** Splits an inline command on whitespace; double quotes group words. */
    static List<String> inline(String line) throws ProtocolException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '\\' && i + 1 < line.length()) cur.append(line.charAt(++i));
                else if (ch == '"') quoted = false;
                else cur.append(ch);
            } else if (ch == '"') { quoted = true; any = true; }
            else if (Character.isWhitespace(ch)) {
                if (cur.length() > 0 || any) { out.add(cur.toString()); cur.setLength(0); any = false; }
            } else cur.append(ch);
        }
        if (quoted) throw new ProtocolException("unbalanced quotes in request");
        if (cur.length() > 0 || any) out.add(cur.toString());
        return out;
    }

    /** Parses one reply (used by the client). */
    public static Object readReply(InputStream in) throws IOException {
        int t = in.read();
        if (t == -1) throw new EOFException("connection closed");
        String line = readLine(in);
        if (line == null) throw new EOFException("connection closed");
        switch (t) {
            case '+': return new Simple(line);
            case '-': return new Err(line);
            case ':': return Long.parseLong(line);
            case '$': {
                int len = parseLen(line);
                if (len < 0) return null;
                byte[] data = in.readNBytes(len);
                if (data.length < len) throw new EOFException("truncated reply");
                in.read(); in.read(); // CRLF
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = parseLen(line);
                if (n < 0) return null;
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) items.add(readReply(in));
                return items;
            }
            default: throw new ProtocolException("unknown reply type '" + (char) t + "'");
        }
    }

    public static void write(OutputStream out, Object o) throws IOException {
        if (o == null) out.write(ascii("$-1\r\n"));
        else if (o instanceof Simple s) out.write(ascii("+" + s.text() + "\r\n"));
        else if (o instanceof Err e) out.write(ascii("-" + e.message() + "\r\n"));
        else if (o instanceof Long || o instanceof Integer) out.write(ascii(":" + o + "\r\n"));
        else if (o instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(ascii("$" + b.length + "\r\n"));
            out.write(b);
            out.write(ascii("\r\n"));
        } else if (o instanceof List<?> l) {
            out.write(ascii("*" + l.size() + "\r\n"));
            for (Object x : l) write(out, x);
        } else throw new IllegalArgumentException("cannot encode " + o.getClass());
    }

    /** Encodes a command the way clients send it: an array of bulk strings. */
    public static void writeCommand(OutputStream out, List<String> args) throws IOException {
        write(out, args);
    }

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
}
