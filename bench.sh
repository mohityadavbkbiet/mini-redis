#!/usr/bin/env bash
# Load test against a running server. Usage: bash bench.sh [clients] [requestsPerClient] [valueBytes] [port]
cd "$(dirname "$0")"
[ -d out ] || ./build.sh
exec java -cp out com.mohit.kv.Bench "$@"
