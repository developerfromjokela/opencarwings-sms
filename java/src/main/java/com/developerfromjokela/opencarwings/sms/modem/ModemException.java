package com.developerfromjokela.opencarwings.sms.modem;

/**
 * Raised for any AT-command / modem-level failure (rejected command,
 * timeout waiting for a response, +CMS/+CME error, etc).
 */
public class ModemException extends Exception {

    /** Raw buffer captured from the serial port at the time of failure, if any. */
    private final String raw;

    public ModemException(String message) {
        this(message, "");
    }

    public ModemException(String message, String raw) {
        super(message);
        this.raw = raw == null ? "" : raw;
    }

    public String getRaw() {
        return raw;
    }
}
