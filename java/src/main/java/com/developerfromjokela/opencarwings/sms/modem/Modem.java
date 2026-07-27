package com.developerfromjokela.opencarwings.sms.modem;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Minimal GSM modem wrapper for sending a pre-encoded SMS PDU via AT+CMGS.
 *
 * This class does NOT encode PDUs. You supply the full PDU hex string
 * (SMSC field + TPDU) exactly as you'd type it after the modem's '&gt;' prompt.
 *
 * Port of the Python reference implementation. All AT-command exchanges are
 * serialized with a single lock so concurrent callers (e.g. multiple
 * WebSocket messages arriving back to back) can't interleave on the one
 * serial connection.
 */
public class Modem implements AutoCloseable {

    private static final char CTRL_Z = 0x1a;

    private static final Pattern OK = Pattern.compile("\\r\\nOK\\r\\n");
    private static final Pattern ERROR = Pattern.compile("\\r\\nERROR\\r\\n");
    private static final Pattern CMS_ERROR = Pattern.compile("\\+CMS ERROR");
    private static final Pattern CME_ERROR = Pattern.compile("\\+CME ERROR");
    private static final Pattern CMGS_LINE = Pattern.compile("\\+CMGS:\\s*(\\d+)");
    private static final Pattern HEX_PDU = Pattern.compile("[0-9A-F]+");

    private final SerialPort serialPort;
    private final InputStream in;
    private final OutputStream out;
    private final int commandTimeoutMs;
    private final int cmgsTimeoutMs;
    private final boolean pduSpaceCommand;
    private final Object lock = new Object();

    public Modem(String portDescriptor, int baudRate, int commandTimeoutMs, int cmgsTimeoutMs, boolean pduSpaceCommand) throws ModemException {
        this.commandTimeoutMs = commandTimeoutMs;
        this.cmgsTimeoutMs = cmgsTimeoutMs;
        this.pduSpaceCommand = pduSpaceCommand;

        this.serialPort = SerialPort.getCommPort(portDescriptor);
        this.serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // Non-blocking-ish reads: we poll ourselves, mirroring the Python
        // timeout=0.1 read poll interval; overall waits are our own loops.
        this.serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

        if (!serialPort.openPort()) {
            throw new ModemException("Could not open serial port " + portDescriptor);
        }
        this.in = serialPort.getInputStream();
        this.out = serialPort.getOutputStream();
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
        try {
            out.close();
        } catch (IOException ignored) {
        }
        serialPort.closePort();
    }

    private void write(String text) throws ModemException {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ModemException("Serial write failed: " + e.getMessage());
        }
    }

    private void resetInputBuffer() {
        serialPort.flushIOBuffers();
    }


    private String readUntil(Predicate<String> predicate, int timeoutMs) throws ModemException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];

        while (System.currentTimeMillis() < deadline) {
            int available = in.available() > 0 ? Math.min(in.available(), chunk.length) : 0;
            int n = 0;
            try {
                n = available > 0 ? in.read(chunk, 0, available) : 0;
            } catch (IOException e) {
                throw new ModemException("Serial read failed: " + e.getMessage());
            }
            if (n > 0) {
                buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                if (predicate.test(buffer.toString())) {
                    return buffer.toString();
                }
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ModemException("Interrupted while waiting for modem response", buffer.toString().trim());
                }
            }
        }
        throw new ModemException("Timed out waiting for modem response", buffer.toString().trim());
    }

    private static boolean hasTerminator(String buffer) {
        return OK.matcher(buffer).find()
                || ERROR.matcher(buffer).find()
                || CMS_ERROR.matcher(buffer).find()
                || CME_ERROR.matcher(buffer).find();
    }

    private static boolean hasCmgsResult(String buffer) {
        boolean gotCmgsAndOk = CMGS_LINE.matcher(buffer).find() && OK.matcher(buffer).find();
        return gotCmgsAndOk
                || CMS_ERROR.matcher(buffer).find()
                || CME_ERROR.matcher(buffer).find()
                || ERROR.matcher(buffer).find();
    }

    /** Send a plain AT command and wait for OK/ERROR/+CMS ERROR/+CME ERROR. */
    private String sendCommand(String command, Integer timeoutOverrideMs) throws ModemException, IOException {
        int timeout = timeoutOverrideMs != null ? timeoutOverrideMs : commandTimeoutMs;
        resetInputBuffer();
        write(command + "\r");
        String buffer = readUntil(Modem::hasTerminator, timeout);
        if (!buffer.contains("\r\nOK\r\n")) {
            throw new ModemException("Modem rejected '" + command + "': " + buffer.trim(), buffer.trim());
        }
        return buffer;
    }


    public static class SendResult {
        public final Integer messageReference;
        public final String raw;

        SendResult(Integer messageReference, String raw) {
            this.messageReference = messageReference;
            this.raw = raw;
        }
    }

    public SendResult sendPdu(String pduHex, Integer tpduLength) throws ModemException, IOException {
        synchronized (lock) {
            String clean = pduHex == null ? "" : pduHex.trim().toUpperCase();
            if (clean.isEmpty() || !HEX_PDU.matcher(clean).matches() || clean.length() % 2 != 0) {
                throw new ModemException("PDU must be a hex string with an even number of characters");
            }

            int length = tpduLength != null ? tpduLength : computeTpduLength(clean);
            if (length <= 0) {
                throw new ModemException("Could not determine a valid tpduLength; pass it explicitly");
            }

            // Ensure PDU mode.
            sendCommand("AT+CMGF=0", null);

            // Send AT+CMGS=<length> and wait for the '>' prompt (not OK/ERROR).
            resetInputBuffer();
            write("AT+CMGS=" + length + "\r");
            String promptBuffer = readUntil(b -> b.contains(">") || b.contains("ERROR"), commandTimeoutMs);
            if (!promptBuffer.contains(">")) {
                throw new ModemException("Modem rejected AT+CMGS=" + length + ": " + promptBuffer.trim(), promptBuffer.trim());
            }

            // Write the PDU followed by enter, Ctrl-Z, then wait for +CMGS:/OK or an error.
            if (pduSpaceCommand)
                clean += "\r";
            write(clean + CTRL_Z);
            String buffer = readUntil(Modem::hasCmgsResult, cmgsTimeoutMs);

            if (!buffer.contains("\r\nOK\r\n") || !buffer.contains("+CMGS:")) {
                throw new ModemException("Modem error sending PDU: " + buffer.trim(), buffer.trim());
            }

            java.util.regex.Matcher m = CMGS_LINE.matcher(buffer);
            Integer ref = m.find() ? Integer.parseInt(m.group(1)) : null;
            return new SendResult(ref, buffer.trim());
        }
    }

    public SendResult sendTextMessage(String phoneNumber, String text) throws ModemException, IOException {
        synchronized (lock) {
            if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
                throw new ModemException("phoneNumber must not be empty");
            }
            if (text == null) {
                throw new ModemException("text must not be null");
            }
            String number = phoneNumber.trim();

            // Switch to text mode.
            sendCommand("AT+CMGF=1", null);

            // Send AT+CMGS="<number>" and wait for the '>' prompt (not OK/ERROR).
            resetInputBuffer();
            write("AT+CMGS=\"" + number + "\"\r");
            String promptBuffer = readUntil(b -> b.contains(">") || b.contains("ERROR"), commandTimeoutMs);
            if (!promptBuffer.contains(">")) {
                throw new ModemException("Modem rejected AT+CMGS=\"" + number + "\": " + promptBuffer.trim(), promptBuffer.trim());
            }

            // Write the message body followed by Ctrl-Z, then wait for +CMGS:/OK or an error.
            write(text + CTRL_Z);
            String buffer = readUntil(Modem::hasCmgsResult, cmgsTimeoutMs);

            if (!buffer.contains("\r\nOK\r\n") || !buffer.contains("+CMGS:")) {
                throw new ModemException("Modem error sending text message: " + buffer.trim(), buffer.trim());
            }

            java.util.regex.Matcher m = CMGS_LINE.matcher(buffer);
            Integer ref = m.find() ? Integer.parseInt(m.group(1)) : null;
            return new SendResult(ref, buffer.trim());
        }
    }

    public static int computeTpduLength(String pduHex) {
        int totalBytes = pduHex.length() / 2;
        int smscLenByte = Integer.parseInt(pduHex.substring(0, 2), 16);
        int smscSegmentBytes = 1 + smscLenByte;
        return totalBytes - smscSegmentBytes;
    }
}
