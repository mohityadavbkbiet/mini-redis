#!/usr/bin/env bash
# Start the web console (http://localhost:5173). The server must be running: bash run.sh
set -e
cd "$(dirname "$0")/frontend"
# Install when node_modules is missing OR package.json changed since the last install
if [ ! -d node_modules ] || [ package.json -nt node_modules/.package-lock.json ]; then
  npm install
fi
exec npm run dev
