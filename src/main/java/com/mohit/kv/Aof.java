package com.mohit.kv;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Append-only file: every write command is appended (in RESP form) and replayed on startup.
 * Fsync policy: ALWAYS = fsync after every write (safest, slowest), EVERYSEC = background fsync each second
 * (lose at most ~1s on power loss), NO = leave it to the OS. A process crash loses nothing in any mode
 * because each append is flushed to the OS immediately.
 */
public class Aof implements Closeable {
    public enum Fsync { ALWAYS, EVERYSEC, NO }

    private final Path path;
    private final Fsync policy;
    private final Object lock = new Object();
    private FileOutputStream fos;
    private BufferedOutputStream out;
    private ScheduledExecutorService syncer;

    public Aof(Path path, Fsync policy) {
        this.path = path;
        this.policy = policy;
    }

    /** Counts bytes read so we know where the last complete command ended. */
    private static final class CountingInput extends FilterInputStream {
        long count;
        CountingInput(InputStream in) { super(in); }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) count += n; return n; }
    }

    /** Replays the file through the command executor. A torn last command (crash mid-write) is cut off. Returns commands replayed. */
    public int replay(Commands commands) throws IOException {
        if (!Files.exists(path)) return 0;
        int n = 0;
        long goodEnd = 0;
        boolean torn = false;
        try (CountingInput in = new CountingInput(new BufferedInputStream(Files.newInputStream(path)))) {
            while (true) {
                List<String> cmd;
                try {
                    cmd = Resp.readCommand(in);
                } catch (IOException e) { // truncated or corrupt tail
                    torn = true;
                    break;
                }
                if (cmd == null) break;
                goodEnd = in.count;
                if (cmd.isEmpty()) continue;
                commands.execute(cmd, false);
                n++;
            }
        }
        if (torn) {
            System.err.println("WARN: AOF has an incomplete last command; truncating to " + goodEnd + " bytes");
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) { ch.truncate(goodEnd); }
        }
        return n;
    }

    public void open() throws IOException {
        synchronized (lock) {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            fos = new FileOutputStream(path.toFile(), true);
            out = new BufferedOutputStream(fos, 1 << 16);
        }
        if (policy == Fsync.EVERYSEC && syncer == null) {
            syncer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aof-fsync");
                t.setDaemon(true);
                return t;
            });
            syncer.scheduleAtFixedRate(this::syncQuietly, 1, 1, TimeUnit.SECONDS);
        }
    }

    public void append(List<String> args) throws IOException {
        synchronized (lock) {
            if (out == null) return;
            Resp.writeCommand(out, args);
            out.flush(); // to the OS, so a process crash cannot lose it
            if (policy == Fsync.ALWAYS) fos.getFD().sync();
        }
    }

    private void syncQuietly() {
        synchronized (lock) {
            try { if (fos != null && fos.getFD().valid()) { out.flush(); fos.getFD().sync(); } }
            catch (IOException ignored) { /* next tick retries */ }
        }
    }

    /** Replaces the file with a compact snapshot (one command per key). Caller must block concurrent writes. */
    public void rewrite(List<List<String>> snapshot) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".rewrite");
        try (FileOutputStream f = new FileOutputStream(tmp.toFile()); BufferedOutputStream o = new BufferedOutputStream(f)) {
            for (List<String> c : snapshot) Resp.writeCommand(o, c);
            o.flush();
            f.getFD().sync();
        }
        synchronized (lock) {
            if (out != null) out.close();
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            fos = new FileOutputStream(path.toFile(), true);
            out = new BufferedOutputStream(fos, 1 << 16);
        }
    }

    public long sizeBytes() {
        try { return Files.size(path); } catch (IOException e) { return -1; }
    }

    @Override
    public void close() {
        if (syncer != null) syncer.shutdownNow();
        synchronized (lock) {
            try {
                if (out != null) { out.flush(); fos.getFD().sync(); out.close(); }
            } catch (IOException ignored) { /* closing */ }
            out = null;
        }
    }
}
