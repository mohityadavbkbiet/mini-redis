package com.mohit.kv;

import java.nio.file.Path;

/** Env: HTTP_PORT (8081, "none" = no web gateway), PORT (6380), BIND (127.0.0.1), AOF_FILE (data/appendonly.aof, "none" = off), FSYNC (everysec|always|no), MAX_CLIENTS (1000). */
public class Main {
    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        String aofEnv = env.getOrDefault("AOF_FILE", "data/appendonly.aof");
        Server.Config cfg = new Server.Config(
                env.getOrDefault("BIND", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("PORT", "6380")),
                aofEnv.equalsIgnoreCase("none") ? null : Path.of(aofEnv),
                Aof.Fsync.valueOf(env.getOrDefault("FSYNC", "everysec").toUpperCase()),
                Integer.parseInt(env.getOrDefault("MAX_CLIENTS", "1000")),
                env.getOrDefault("HTTP_PORT", "8081").equalsIgnoreCase("none") ? -1 : Integer.parseInt(env.getOrDefault("HTTP_PORT", "8081")));
        Server server = new Server(cfg);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Mini Redis listening on " + cfg.host() + ":" + server.port()
                + "  (aof=" + (cfg.aofPath() == null ? "off" : cfg.aofPath() + ", fsync=" + cfg.fsync()) + ")");
        if (server.httpPort() >= 0) System.out.println("Web gateway (for the UI) on http://" + cfg.host() + ":" + server.httpPort());
        Thread.currentThread().join();
    }
}
