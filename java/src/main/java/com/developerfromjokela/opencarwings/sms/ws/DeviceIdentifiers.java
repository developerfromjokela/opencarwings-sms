package com.developerfromjokela.opencarwings.sms.ws;

import com.developerfromjokela.opencarwings.sms.utils.HexUtils;

import java.security.SecureRandom;
import java.util.prefs.Preferences;

public final class DeviceIdentifiers {

    private static final Preferences PREFS = Preferences.userNodeForPackage(DeviceIdentifiers.class);
    private static final String KEY = "uniqueId";
    private static final String ENC_KEY = "encryptionKey";

    private DeviceIdentifiers() {
    }

    public static synchronized String getDeviceId() {
        String existing = PREFS.get(KEY, null);
        if (existing != null && !existing.isEmpty()) {
            return existing.toLowerCase();
        }
        SecureRandom random = new SecureRandom();
        byte[] newID = new byte[8];
        random.nextBytes(newID);
        String generated = HexUtils.bytesToHex(newID, false);
        PREFS.put(KEY, generated);
        return generated.toLowerCase();
    }

    public static synchronized byte[] getEncryptionKey() {
        byte[] existing = PREFS.getByteArray(ENC_KEY, null);
        if (existing != null && existing.length > 0) {
            return existing;
        }
        SecureRandom random = new SecureRandom();
        byte[] newKey = new byte[16];
        random.nextBytes(newKey);
        PREFS.putByteArray(ENC_KEY, newKey);
        return newKey;
    }

}