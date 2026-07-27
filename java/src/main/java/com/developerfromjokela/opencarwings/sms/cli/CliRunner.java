package com.developerfromjokela.opencarwings.sms.cli;

import com.developerfromjokela.opencarwings.sms.ConnectionConfig;
import com.developerfromjokela.opencarwings.sms.modem.Modem;
import com.developerfromjokela.opencarwings.sms.utils.HexUtils;
import com.developerfromjokela.opencarwings.sms.ws.DeviceIdentifiers;
import com.developerfromjokela.opencarwings.sms.ws.SmsWebSocketClient;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;


public final class CliRunner {

    private CliRunner() {
    }

    public static void run(String[] args) {
        ConnectionConfig config = fromEnv();
        applyArgs(config, args);

        System.out.println("[cli] serial port     = " + config.serialPort);
        System.out.println("[cli] baud rate       = " + config.baudRate);
        System.out.println("[cli] command timeout = " + config.commandTimeoutMs + " ms");
        System.out.println("[cli] cmgs timeout    = " + config.cmgsTimeoutMs + " ms");
        System.out.println("[cli] ws url          = " + config.wsUrl);
        System.out.println("[cli] auto-reconnect  = " + config.autoReconnect);
        System.out.println("[cli] reconnect delay = " + config.reconnectDelayMs + " ms (cap " + config.maxReconnectDelayMs + " ms)");
        System.out.println("[cli] ping interval   = " + config.pingIntervalSeconds + " s");

        Modem modem;
        try {
            modem = new Modem(config.serialPort, config.baudRate, config.commandTimeoutMs, config.cmgsTimeoutMs);
        } catch (Exception e) {
            System.err.println("[cli] failed to open modem on " + config.serialPort + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("[cli] modem opened on " + config.serialPort);

        SmsWebSocketClient client;
        try {
            client = new SmsWebSocketClient(
                    URI.create(config.wsUrl),
                    modem,
                    config.autoReconnect,
                    config.reconnectDelayMs,
                    config.maxReconnectDelayMs,
                    config.pingIntervalSeconds,
                    System.out::println
            );
        } catch (Exception e) {
            System.err.println("[cli] invalid ws url " + config.wsUrl + ": " + e.getMessage());
            modem.close();
            System.exit(1);
            return;
        }

        System.out.println("[device info] Device ID  = " + DeviceIdentifiers.getDeviceId());
        System.out.println("[device info] Encryption Key  = " + HexUtils.bytesToHex(DeviceIdentifiers.getEncryptionKey(), true).toUpperCase(Locale.ROOT));


        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[cli] shutting down...");
            client.shutdown();
            modem.close();
            shutdownLatch.countDown();
        }));

        client.connect();

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ConnectionConfig fromEnv() {
        ConnectionConfig c = new ConnectionConfig();
        c.serialPort = System.getenv().getOrDefault("SERIAL_PORT", c.serialPort);
        c.baudRate = parseIntOr(System.getenv("BAUD_RATE"), c.baudRate);
        c.commandTimeoutMs = parseIntOr(System.getenv("COMMAND_TIMEOUT_MS"), c.commandTimeoutMs);
        c.cmgsTimeoutMs = parseIntOr(System.getenv("CMGS_TIMEOUT_MS"), c.cmgsTimeoutMs);
        c.wsUrl = System.getenv().getOrDefault("WS_URL", c.wsUrl);
        c.reconnectDelayMs = parseIntOr(System.getenv("RECONNECT_DELAY_MS"), c.reconnectDelayMs);
        c.maxReconnectDelayMs = parseIntOr(System.getenv("MAX_RECONNECT_DELAY_MS"), c.maxReconnectDelayMs);
        c.pingIntervalSeconds = parseIntOr(System.getenv("PING_INTERVAL_S"), c.pingIntervalSeconds);
        return c;
    }

    private static void applyArgs(ConnectionConfig c, String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--port":
                    c.serialPort = args[++i];
                    break;
                case "--baud":
                    c.baudRate = Integer.parseInt(args[++i]);
                    break;
                case "--command-timeout":
                    c.commandTimeoutMs = Integer.parseInt(args[++i]);
                    break;
                case "--cmgs-timeout":
                    c.cmgsTimeoutMs = Integer.parseInt(args[++i]);
                    break;
                case "--ws-url":
                    c.wsUrl = args[++i];
                    break;
                case "--no-reconnect":
                    c.autoReconnect = false;
                    break;
                case "--reconnect-delay":
                    c.reconnectDelayMs = Integer.parseInt(args[++i]);
                    break;
                case "--max-reconnect-delay":
                    c.maxReconnectDelayMs = Integer.parseInt(args[++i]);
                    break;
                case "--ping-interval":
                    c.pingIntervalSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--nogui":
                    break;
                default:
                    System.err.println("[cli] ignoring unknown argument: " + arg);
            }
        }
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
