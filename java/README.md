# OpenCARWINGS SMS Gateway using Java

Use any AT-compatible modem for sending SMS messages

Built JAR-packages are available in releases

## Build

Requires JDK 11+ and Maven

```bash
mvn package
```

## Run — GUI mode (default)

```bash
java -jar opencarwings-sms.jar
```

## Run — headless / CLI mode

Pass `--nogui` to use CLI Mode instead:

```bash
java -jar opencarwings-sms.jar --nogui \
  --port /dev/ttyUSB0 \
  --baud 115200
```

Flags can also be supplied via environment variables instead:

| Flag                 | Env var             | Default                   |
|-----------------------|----------------------|---------------------------|
| `--port`              | `SERIAL_PORT`        | `/dev/ttyUSB2`             |
| `--baud`              | `BAUD_RATE`          | `115200`                   |
| `--command-timeout`   | `COMMAND_TIMEOUT_MS` | `5000`                      |
| `--cmgs-timeout`      | `CMGS_TIMEOUT_MS`    | `20000`                     |
| `--ws-url`            | `WS_URL`             | `wss://opencarwings.viaaq.eu/ws/smsgateway/`  |
| `--reconnect-delay`   | `RECONNECT_DELAY_MS` | `5000`                      |
| `--max-reconnect-delay` | `MAX_RECONNECT_DELAY_MS` | `60000`                |
| `--ping-interval`     | `PING_INTERVAL_S`    | `30` (0 disables pings)     |
| `--no-reconnect`      | —                    | reconnect enabled          |

The CLI process blocks (waiting on incoming WebSocket messages) until you
send SIGINT/SIGTERM (Ctrl-C), at which point it closes the socket and the
serial port cleanly.

## Run with systemd

```
[Unit]
Description=SMS Gateway for OpenCARWINGS              
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=10

[Service]
Type=simple
User=dfj      
WorkingDirectory=/path/to/ocwsms
ExecStart=java -jar /path/to/ocwsms/opencarwings-sms.jar --nogui
Environment="SERIAL_PORT=/dev/ttyUSB0"
Environment="BAUD_RATE=115200"
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
KillSignal=SIGTERM
TimeoutStopSec=10

[Install]
WantedBy=multi-user.target
```
