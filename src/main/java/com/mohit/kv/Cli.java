package com.mohit.kv;

import java.io.*;
import java.net.ConnectException;
import java.util.*;

/** Interactive client, like redis-cli. Usage: java -cp out com.mohit.kv.Cli [host] [port] */
public class Cli {
    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6380;
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        try (KvClient c = new KvClient(host, port)) {
            while (true) {
                System.out.print(host + ":" + port + "> ");
                System.out.flush();
                String line = stdin.readLine();
                if (line == null) break;
                List<String> parts = Resp.inline(line.trim());
                if (parts.isEmpty()) continue;
                String first = parts.get(0).toLowerCase(Locale.ROOT);
                if (first.equals("exit") || first.equals("quit")) break;
                try {
                    System.out.println(format(c.call(parts.toArray(new String[0])), ""));
                } catch (IOException e) {
                    System.out.println("(connection lost: " + e.getMessage() + ")");
                    break;
                }
            }
        } catch (ConnectException e) {
            System.err.println("Could not connect to " + host + ":" + port + ". Is the server running? (bash run.sh)");
        }
    }

    static String format(Object o, String indent) {
        if (o == null) return "(nil)";
        if (o instanceof Resp.Simple s) return s.text();
        if (o instanceof Resp.Err e) return "(error) " + e.message();
        if (o instanceof Long l) return "(integer) " + l;
        if (o instanceof String s) return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
        List<?> l = (List<?>) o;
        if (l.isEmpty()) return "(empty array)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append('\n').append(indent);
            sb.append(i + 1).append(") ").append(format(l.get(i), indent + "   "));
        }
        return sb.toString();
    }

}
