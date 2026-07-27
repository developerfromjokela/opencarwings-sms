package com.developerfromjokela.opencarwings.sms.utils;

public final class HexUtils {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private HexUtils() {
    }

    public static String bytesToHex(byte[] bytes, boolean chunked) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return (chunked ? splitIntoChunks(new String(hexChars)) : new String(hexChars));
    }

    public static String splitIntoChunks(String hex) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (result.length() > 0) {
                result.append(" ");
            }
            result.append(hex, i, Math.min(i + 4, hex.length()));
        }
        return result.toString();
    }
}