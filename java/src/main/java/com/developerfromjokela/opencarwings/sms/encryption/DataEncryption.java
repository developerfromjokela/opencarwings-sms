package com.developerfromjokela.opencarwings.sms.encryption;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

public class DataEncryption {

    public static byte[] decrypt(byte[] encryptedData, byte[] encryptionKey) throws Exception {
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, 16);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, 16, encryptedData.length);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        return cipher.doFinal(ciphertext);
    }

}
