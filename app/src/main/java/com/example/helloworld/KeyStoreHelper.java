package com.example.helloworld;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

public class KeyStoreHelper {

    private static final String TAG = "KeyStoreHelper";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "GeminiApiKeyAlias";

    private KeyStore keyStore;

    public KeyStoreHelper(Context context) {
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            createSecretKey(context);
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException |
                 IOException | InvalidAlgorithmParameterException | NoSuchProviderException e) {
            Log.e(TAG, "KeyStore initialization failed: " + e.getMessage());
        }
    }

    private void createSecretKey(Context context) throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException, KeyStoreException {

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);

            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setRandomizedEncryptionRequired(true)
                    .build();

            keyGenerator.init(keyGenParameterSpec);
            keyGenerator.generateKey();
            Log.d(TAG, "New SecretKey generated and stored.");
        } else {
            Log.d(TAG, "SecretKey already exists.");
        }
    }

    private SecretKey getSecretKey() throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
        // KeyStoreからSecretKeyを取得する
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    public EncryptedData encrypt(String data) {
        try {
            Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);

            // getSecretKey()でKeyStoreから鍵を取得して初期化
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());

            byte[] encryptedBytes = cipher.doFinal(data.getBytes());
            byte[] iv = cipher.getIV();

            return new EncryptedData(encryptedBytes, iv);

        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 UnrecoverableEntryException | KeyStoreException |
                 Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return null;
        }
    }

    public String decrypt(EncryptedData encryptedData) {
        try {
            Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);

            // getSecretKey()でKeyStoreから鍵を取得し、IVと共に初期化
            SecretKey secretKey = getSecretKey();
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(encryptedData.getIv()));

            byte[] decryptedBytes = cipher.doFinal(encryptedData.getEncryptedBytes());
            return new String(decryptedBytes);

        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 UnrecoverableEntryException | KeyStoreException |
                 InvalidAlgorithmParameterException | Exception e) {
            Log.e(TAG, "Decryption failed: " + e.getMessage());
            return null;
        }
    }

    public static class EncryptedData {
        private final byte[] encryptedBytes;
        private final byte[] iv;

        public EncryptedData(byte[] encryptedBytes, byte[] iv) {
            this.encryptedBytes = encryptedBytes;
            this.iv = iv;
        }

        public byte[] getEncryptedBytes() {
            return encryptedBytes;
        }

        public byte[] getIv() {
            return iv;
        }
    }
}