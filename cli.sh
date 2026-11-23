#!/usr/bin/env bash
# Interactive client (like redis-cli). Usage: bash cli.sh [host] [port]
cd "$(dirname "$0")"
[ -d out ] || ./build.sh
exec java -cp out com.mohit.kv.Cli "$@"
