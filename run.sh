#!/usr/bin/env bash
# Env: PORT=6380 BIND=127.0.0.1 AOF_FILE=data/appendonly.aof (or "none") FSYNC=everysec|always|no MAX_CLIENTS=1000
set -e
cd "$(dirname "$0")"
./build.sh
exec java -cp out com.mohit.kv.Main
