# Mini Redis

A lightweight in-memory key-value store written in Java using only the JDK. It supports the Redis RESP protocol, so you can connect to it using `redis-cli` or clients like `redis-py`.

It also includes a web console built with React, React Router and Bootstrap 5.

## Run

```bash
bash test.sh        # Build and run 34 tests
bash run.sh         # Start server on 127.0.0.1:6380
bash cli.sh         # Interactive CLI
bash bench.sh       # Throughput and latency benchmark
python3 compat_test.py  # Test redis-py compatibility
```

You can also connect using:

```bash
redis-cli -p 6380
```

Requires JDK 17+. The optional compatibility test requires `pip install redis`.

### Configuration

| Variable | Default | Description |
|---|---|---|
| `PORT` | 6380 | TCP server port |
| `BIND` | 127.0.0.1 | Server bind address |
| `HTTP_PORT` | 8081 | Web gateway port (`none` to disable) |
| `AOF_FILE` | AOF file | Persistence file (`none` to disable) |
| `FSYNC` | everysec | `always`, `everysec` or `no` |
| `MAX_CLIENTS` | — | Maximum concurrent clients |

## Web Console

Start the backend and frontend separately:

```bash
bash run.sh
bash run-ui.sh
```

The UI runs at [http://localhost:5173](http://localhost:5173). Node.js 18+ is required. Dependencies are installed on the first run.

### Pages

- **Dashboard** – Key count, operations/sec, connected clients, AOF size, keys by type, top commands, expiring keys and recent commands.
- **Keys** – Search keys using glob patterns, filter by type, view TTLs, edit strings, increment/decrement numeric values, work with lists and hashes, manage expiry and delete keys.
- **Console** – Browser-based Redis CLI with command history, arrow-key navigation, tab completion and command reference.
- **Scenarios** – Examples of common Redis use cases including cache with expiry, counters, job queues, user profiles, sessions and rate limiters.
- **Benchmark** – Run SET/GET workloads over TCP clients and view throughput, p50/p95/p99 latency and benchmark history.
- **Architecture** – System overview, RESP examples, command lifecycle and interview-related notes.

The web console communicates with the server through a JSON API because browsers cannot connect directly over raw TCP.

API endpoints:

`/api/info`, `/api/keys`, `/api/key`, `/api/exec`, `/api/bench`

Vite proxies `/api` requests to the backend. Set `BACKEND_URL` to change the target.

The server binds to `127.0.0.1` by default. `/api/exec` allows executing commands, so don't expose the HTTP gateway to untrusted networks.

## Supported Commands

| Category | Commands |
|---|---|
| Strings | `SET` (EX, PX, NX, XX), `GET`, `MSET`, `MGET`, `APPEND`, `STRLEN`, `INCR`, `DECR`, `INCRBY`, `DECRBY |
| Keys | `DEL`, `EXISTS`, `TYPE`, `KEYS` (glob patterns), `DBSIZE`, `FLUSHALL` |
| Expiry | `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `TTL`, `PTTL`, `PERSIST` |
| Lists | `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE` |
| Hashes | `HSET`, `HGET`, `HDEL`, `HGETALL`, `HLEN` |
| Server | `PING`, `ECHO`, `INFO`, `BGREWRITEAOF`, `QUIT`, `HELLO`, `CLIENT`, `COMMAND`, `SELECT 0` |

## Design

```text
Client
  |
  | TCP / RESP
  v
Server
  |
  +-- Accept loop (thread per connection, client limit)
  |
  +-- Command handler
  |      |
  |      v
  |    Store (ConcurrentHashMap)
  |      |
  |      +-- Entry: value, expireAt
  |      +-- Lazy expiry
  |      +-- Background expiry sweeper (200ms)
  |
  +-- AOF persistence
         +-- Append write commands
         +-- Replay on startup
```

### Concurrency

- Mutations use `ConcurrentHashMap.compute()` to update individual keys atomically.
- Operations on the same key are serialized, while operations on different keys can run concurrently.
- Multiple clients performing `INCR` on the same key produce the correct total.

### Expiration

Each key stores an absolute expiration timestamp. Expired keys are removed when accessed and by a background sweeper that runs every 200ms.

### Persistence

Writes are stored in an append-only file (AOF) and replayed at startup.

- Relative TTLs are recorded as absolute `PEXPIREAT` timestamps.
- `FSYNC=always` syncs after every write.
- `FSYNC=everysec` syncs approximately once per second.
- `FSYNC=no` leaves syncing to the operating system.

### AOF Rewrite

`BGREWRITEAOF` rewrites the current store into a temporary file and replaces the existing AOF.

A write lock is used during the rewrite to prevent commands from modifying the store while the snapshot is being written.

### Crash Recovery

If the last AOF command is incomplete due to a crash, the incomplete data is removed during startup.

### Pipelining

The server handles multiple commands sent together and flushes replies when the input buffer has no more pending data.

## Tests

The project currently has 34 tests covering:

- Store operations
- RESP parsing and encoding
- TCP server
- HTTP gateway
- Concurrent operations
- AOF recovery, including incomplete files
- AOF rewrite while the server is under load

Also tested with `redis-cli` and `redis-py`. Restarting the server after `kill -9` restored stored data and TTLs.

The React console was tested in a browser, including key operations, console commands, scenarios, benchmarks, dark mode and mobile layout.

## Possible Improvements

- Add `MULTI` / `EXEC` transactions and `SUBSCRIBE` / `PUBLISH`.
- Replace thread-per-connection with an NIO `Selector` event loop.
- Add sorted sets using a skip list and support `LPOP key count`.
- Add RDB-style snapshots alongside AOF persistence.
