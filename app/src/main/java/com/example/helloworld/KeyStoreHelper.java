package com.example.liefantidia2;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;

public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";
    private static final String KEY_ALIAS = "BiometricKeyAlias";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
                                               + KeyProperties.BLOCK_MODE_GCM + "/"
                                               + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final int GCM_TAG_LENGTH = 128;

    private final KeyStore keyStore;

    public KeyStoreHelper(Context context) {
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateNewKey();
            }
        } catch (Exception e) {
            throw new RuntimeException("KeyStore初期化エラー", e);
        }
    }

    private SecretKey getSecretKey() 
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyPermanentlyInvalidatedException {
        try {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (KeyStoreException e) {
            if (e.getMessage() != null && e.getMessage().contains("Key user not authenticated")) {
                throw new KeyPermanentlyInvalidatedException("Key permanently invalidated", e);
            }
            throw e;
        }
    }

    private void generateNewKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);

        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1) 
                .build();

        keyGenerator.init(keyGenParameterSpec);
        keyGenerator.generateKey();
    }

    public PreferencesHelper.EncryptedData encryptData(String plainText) throws Exception {
        SecretKey secretKey = getSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] dataToEncrypt = plainText.getBytes("UTF-8");
        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt);
        byte[] iv = cipher.getIV();

        return new PreferencesHelper.EncryptedData(encryptedBytes, iv);
    }

    public String decryptData(PreferencesHelper.EncryptedData encryptedData, Cipher authenticatedCipher) throws Exception {
        // GCMParameterSpecを使ってIVを指定して再初期化しないと復号できない
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.getIv());
        authenticatedCipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);
        
        byte[] decryptedBytes = authenticatedCipher.doFinal(encryptedData.getEncryptedBytes());
        return new String(decryptedBytes, "UTF-8");
    }

    // 【修正点】IVを引数で受け取り、初期化時にセットする
    public Cipher getDecryptCipher(byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKey secretKey = getSecretKey();
        
        // IVを使って初期化。これがないと "IV required" エラーになる
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
        return cipher;
    }

    public void deleteKeyAlias() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
                generateNewKey(); 
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete key alias", e);
        }
    }

    public boolean isKeyAliasExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            return false;
        }
    }
}