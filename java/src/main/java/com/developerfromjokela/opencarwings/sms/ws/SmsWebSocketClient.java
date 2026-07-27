package com.developerfromjokela.opencarwings.sms.ws;

import com.developerfromjokela.opencarwings.sms.encryption.DataEncryption;
import com.developerfromjokela.opencarwings.sms.modem.Modem;
import com.developerfromjokela.opencarwings.sms.modem.ModemException;
import com.developerfromjokela.opencarwings.sms.utils.URLUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public class SmsWebSocketClient extends WebSocketClient {

    private final Modem modem;
    private final Consumer<String> logger;
    private final boolean autoReconnect;
    private final int initialReconnectDelayMs;
    private final int maxReconnectDelayMs;

    private final byte[] encryptionKey;

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sms-send-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean intentionallyClosed = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);

    public SmsWebSocketClient(URI serverUri, Modem modem, boolean autoReconnect, int reconnectDelayMs, Consumer<String> logger) {
        this(serverUri, modem, autoReconnect, reconnectDelayMs, 60_000, 30, logger);
    }

    public SmsWebSocketClient(URI serverUri, Modem modem, boolean autoReconnect,
                               int initialReconnectDelayMs, int maxReconnectDelayMs,
                               int pingIntervalSeconds, Consumer<String> logger) {
        super(serverUri);
        this.modem = modem;
        this.autoReconnect = autoReconnect;
        this.initialReconnectDelayMs = Math.max(initialReconnectDelayMs, 500);
        this.maxReconnectDelayMs = Math.max(maxReconnectDelayMs, this.initialReconnectDelayMs);
        this.logger = logger != null ? logger : (s -> {});
        this.encryptionKey = DeviceIdentifiers.getEncryptionKey();
        try {
            serverUri = URLUtils.appendUri(serverUri, "device_id="+DeviceIdentifiers.getDeviceId());
            this.uri = serverUri;
        } catch (URISyntaxException ignored) {}

        setConnectionLostTimeout(Math.max(pingIntervalSeconds, 0));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        reconnectAttempt.set(0);
        reconnecting.set(false);
    }

    @Override
    public void onMessage(String message) {
        logger.accept("[ws] received message: " + message);
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        byte[] decryptedMsg;
        try {
            decryptedMsg = DataEncryption.decrypt(bytes.array(), this.encryptionKey);
        } catch (Exception e) {
            logger.accept("[ws] could not decrypt: " + e.getMessage());
            return;
        }
        workers.submit(() -> handleMessage(new String(decryptedMsg, StandardCharsets.UTF_8)));
    }

    private void handleMessage(String message) {
        try {
            JSONObject request = new JSONObject(message);

            String type = request.optString("type");

            switch (type) {
                case "connect": {
                    logger.accept("[ws] connected!");
                    break;
                }
                case "pdu": {
                    String pdu = request.getString("pdu");
                    Integer tpduLength = request.has("length") && !request.isNull("length")
                            ? request.getInt("length")
                            : null;

                    logger.accept("[ws] received PDU: "+pdu);

                    Modem.SendResult result = modem.sendPdu(pdu, tpduLength);

                    logger.accept("[sms] sent, " + result.raw);
                    break;
                }
                case "sms": {
                    String sms_message = request.getString("sms");
                    String phone = request.getString("phone");
                    logger.accept("[ws] received SMS message: "+sms_message+", to "+phone);

                    Modem.SendResult result = modem.sendTextMessage(phone, sms_message);

                    logger.accept("[sms] sent, " + result.raw);
                    break;
                }
                default:
                    logger.accept("[ws] unknown request from server: "+type);
                    break;
            }

        } catch (ModemException e) {
            logger.accept("[ws] Modem error: " + e);
        } catch (Exception e) {
            logger.accept("[ws] unexpected error handling message: " + e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.accept("[ws] closed (code=" + code + ", reason=" + reason + ", remote=" + remote + ")");
        triggerReconnect();
    }

    @Override
    public void onError(Exception ex) {
        logger.accept("[ws] error: " + ex.getMessage());
    }

    private void triggerReconnect() {
        if (!autoReconnect || intentionallyClosed.get()) {
            return;
        }
        if (!reconnecting.compareAndSet(false, true)) {
            return; // a reconnect loop is already running
        }
        scheduleNextAttempt();
    }

    private void scheduleNextAttempt() {
        int attempt = reconnectAttempt.getAndIncrement();
        long delay = Math.min((long) initialReconnectDelayMs * (1L << Math.min(attempt, 10)), maxReconnectDelayMs);
        logger.accept("[ws] reconnecting in " + delay + " ms (attempt " + (attempt + 1) + ")");

        reconnectScheduler.schedule(() -> {
            if (intentionallyClosed.get()) {
                reconnecting.set(false);
                return;
            }
            logger.accept("[ws] attempting reconnect...");
            try {
                boolean connected = reconnectBlocking();
                if (!connected) {
                    scheduleNextAttempt();
                }
                // On success, onOpen() resets reconnectAttempt and reconnecting.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnecting.set(false);
            } catch (Exception e) {
                logger.accept("[ws] reconnect attempt failed: " + e.getMessage());
                scheduleNextAttempt();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Close the socket and stop any future auto-reconnect attempts. */
    public void shutdown() {
        intentionallyClosed.set(true);
        reconnecting.set(false);
        workers.shutdown();
        reconnectScheduler.shutdownNow();
        this.close();
    }
}
