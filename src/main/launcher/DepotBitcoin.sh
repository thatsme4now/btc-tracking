#!/bin/bash

# Depot Bitcoin Portfolio Launcher (Linux / Mac)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JRE="$SCRIPT_DIR/jre/bin/java"
JAR="$SCRIPT_DIR/app/depot.jar"

if [ ! -f "$JRE" ]; then
    echo "ERROR: Bundled JRE not found at $JRE"
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo "ERROR: depot.jar not found at $JAR"
    exit 1
fi

chmod +x "$JRE"

echo "======================================"
echo " Depot Bitcoin Portfolio"
echo " Starting server on port 8080..."
echo "======================================"

# Start Spring Boot in background
"$JRE" -Xmx256m -Dfile.encoding=UTF-8 \
    -jar "$JAR" \
    --depot.db=inmemory &
APP_PID=$!

echo "Waiting for server to start..."
sleep 5

# Open browser
if [[ "$OSTYPE" == "darwin"* ]]; then
    open "http://localhost:8080/depot"
else
    xdg-open "http://localhost:8080/depot" 2>/dev/null || \
    sensible-browser "http://localhost:8080/depot" 2>/dev/null || \
    echo "Please open http://localhost:8080/depot in your browser"
fi

echo "Server running (PID: $APP_PID). Press Ctrl+C to stop."
wait $APP_PID