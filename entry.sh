#!/usr/bin/env bash

# Read configurations from options.json
SERIAL_PORT=$(jq --raw-output '.device' /data/options.json)
BAUD_RATE=$(jq --raw-output '.baud' /data/options.json)

echo "Starting OpenCarwings Java SMS service on port: ${SERIAL_PORT} with baud rate: ${BAUD_RATE}"

exec java -Djava.util.prefs.user_root=/data/java_prefs \
          -jar /app/app.jar --nogui \
          --serial-port "${SERIAL_PORT}" \
          --baud-rate "${BAUD_RATE}"
