package com.mohit.kv;

import com.mohit.kv.Resp.Err;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** TCP server: one thread per connection (bounded by maxClients), a background expiry sweeper, optional AOF. */
public class Server implements Closeable {
    /** @param aofPath null disables persistence */
    public record Config(String host, int port, Path aofPath, Aof.Fsync fsync, int maxClients, int httpPort) {
        /** Without the HTTP gateway (used by tests and the plain TCP setup). */
        public Config(String host, int port, Path aofPath, Aof.Fsync fsync, int maxClients) {
            this(host, port, aofPath, fsync, maxClients, -1);
        }
    }

    private final Config cfg;
    private final Store store = new Store();
    private final Stats stats = new Stats();
    private final Commands commands = new Commands(store, stats);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "client-handler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledExecutorService sweeper;
    private ServerSocket serverSocket;
    private Aof aof;
    private HttpGateway gateway;
    private volatile boolean stopped;

    public Server(Config cfg) { this.cfg = cfg; }

    public Store store() { return store; }
    public Stats stats() { return stats; }
    public int port() { return serverSocket.getLocalPort(); }
    public Commands commands() { return commands; }
    public Aof aof() { return aof; }
    public Config config() { return cfg; }
    public int httpPort() { return gateway == null ? -1 : gateway.port(); }

    public void start() throws IOException {
        if (cfg.aofPath() != null) {
            aof = new Aof(cfg.aofPath(), cfg.fsync());
            int n = aof.replay(commands);
            if (n > 0) System.out.println("Loaded " + n + " commands from " + cfg.aofPath());
            aof.open();
            commands.setAof(aof);
        }
        serverSocket = new ServerSocket(cfg.port(), 128, InetAddress.getByName(cfg.host()));

        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expiry-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(store::sweep, 200, 200, TimeUnit.MILLISECONDS);

        Thread acceptor = new Thread(this::acceptLoop, "acceptor");
        acceptor.setDaemon(true);
        acceptor.start();

        if (cfg.httpPort() >= 0) {
            gateway = new HttpGateway(this, cfg.host(), cfg.httpPort());
            gateway.start();
        }
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                stats.connectionsTotal.incrementAndGet();
                if (stats.connectedClients.get() >= cfg.maxClients()) {
                    try (s) {
                        Resp.write(s.getOutputStream(), new Err("ERR max number of clients reached"));
                        s.getOutputStream().flush();
                    } catch (IOException ignored) { /* client already gone */ }
                    continue;
                }
                stats.connectedClients.incrementAndGet();
                sockets.add(s);
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (stopped) return;
            }
        }
    }

    private void handle(Socket s) {
        try (s;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 8192);
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream(), 8192)) {
            while (!stopped) {
                List<String> cmd;
                try {
                    cmd = Resp.readCommand(in);
                } catch (Resp.ProtocolException e) {
                    Resp.write(out, new Err("ERR Protocol error: " + e.getMessage()));
                    out.flush();
                    return;
                }
                if (cmd == null) return;       // client closed
                if (cmd.isEmpty()) continue;
                Object reply = commands.execute(cmd, true);
                Resp.write(out, reply);
                // Pipelining: if more requests are already buffered, answer them all before flushing once.
                if (in.available() == 0) out.flush();
                if (cmd.get(0).equalsIgnoreCase("QUIT")) return;
            }
        } catch (IOException ignored) {
            // client disconnected or server is closing
        } finally {
            sockets.remove(s);
            stats.connectedClients.decrementAndGet();
        }
    }

    @Override
    public void close() {
        stopped = true;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (Socket s : sockets) { try { s.close(); } catch (IOException ignored) { } }
        pool.shutdownNow();
        if (sweeper != null) sweeper.shutdownNow();
        if (gateway != null) gateway.close();
        if (aof != null) aof.close();
    }
}
