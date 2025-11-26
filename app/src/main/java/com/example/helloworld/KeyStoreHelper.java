package com.example.liefantidia2;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystoreを使用して暗号化/復号化を処理するヘルパークラス。
 */
public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";
    private static final String KEY_ALIAS = "BiometricKeyAlias";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = KeyProperties.KEY_ALGORITHM_AES + "/"
                                               + KeyProperties.BLOCK_MODE_GCM + "/"
                                               + KeyProperties.ENCRYPTION_PADDING_NONE;
    private static final int GCM_TAG_LENGTH = 128;
    // 認証の有効期限 (秒): 1時間 = 3600秒 (互換性問題回避のため、鍵生成時に使用しない)
    private static final int AUTH_VALIDITY_SECONDS = 3600; 

    private final KeyStore keyStore;
    private final Context context;

    public KeyStoreHelper(Context context) {
        this.context = context;
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateNewKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "KeyStore initialization failed: " + e.getMessage());
            // キー生成失敗の根本原因を伝えるRuntimeExceptionを再スロー
            throw new RuntimeException("KeyStore初期化エラー", e); 
        }
    }

    private SecretKey getSecretKey()
            throws KeyStoreException, UnrecoverableKeyException, NoSuchAlgorithmException, KeyPermanentlyInvalidatedException {
        try {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        } catch (KeyStoreException e) {
            // 認証変更による永続的な無効化を検出
            if (e.getMessage() != null && e.getMessage().contains("Key user not authenticated")) {
                throw new KeyPermanentlyInvalidatedException("Key permanently invalidated due to authentication change.", e);
            }
            throw e;
        }
    }

    /**
     * 鍵生成パラメータを緩和し、互換性の問題を解消します。
     * AUTH_VALIDITY_SECONDSを削除し、鍵生成の互換性を向上させます。
     */
    private void generateNewKey() throws KeyStoreException, CertificateException, IOException,
                                 NoSuchAlgorithmException, NoSuchProviderException,
                                 InvalidAlgorithmParameterException {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);

        KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 認証必須とするが、有効期限は設定しないことで互換性を向上
                .setUserAuthenticationRequired(true)
                // .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS) // 👈 コメントアウト
                .build();

        keyGenerator.init(keyGenParameterSpec);
        keyGenerator.generateKey();
        Log.i(TAG, "New User-Focused Biometric Key (Session-based) generated successfully.");
    }

    public PreferencesHelper.EncryptedData encryptData(String plainText)
            throws NoSuchAlgorithmException, NoSuchPaddingException,
                   InvalidKeyException, IllegalBlockSizeException, BadPaddingException,
                   UnrecoverableKeyException, KeyStoreException {

        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new RuntimeException("Key is permanently invalid (auth change). Must regenerate key.", e);
        }

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] dataToEncrypt = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cipher.doFinal(dataToEncrypt);
        byte[] iv = cipher.getIV();

        if (iv == null) {
             throw new RuntimeException("Encryption failed: Initialization Vector (IV) is null.");
        }

        return new PreferencesHelper.EncryptedData(encryptedBytes, iv);
    }

    public String decryptData(PreferencesHelper.EncryptedData encryptedData, Cipher authenticatedCipher)
            throws InvalidAlgorithmParameterException, IllegalBlockSizeException,
                   BadPaddingException, KeyPermanentlyInvalidatedException {

        try {
            byte[] decryptedBytes = authenticatedCipher.doFinal(encryptedData.getEncryptedBytes());
            return new String(decryptedBytes, StandardCharsets.UTF_8);

        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Decryption execution failed: " + e.getMessage());
            throw e;
        }
    }

    public Cipher getDecryptCipher(byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        SecretKey secretKey;
        try {
            secretKey = getSecretKey();
        } catch (KeyPermanentlyInvalidatedException e) {
            throw e; 
        }

        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return cipher;
    }

    public void deleteKeyAlias() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
                Log.w(TAG, "Key alias deleted: " + KEY_ALIAS);
                // キー削除後の即時再生成は不要なため、ここでは generateNewKey() を削除しても良い
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete key alias: " + e.getMessage());
        }
    }

    public boolean isKeyAliasExist() {
        try {
            return keyStore.containsAlias(KEY_ALIAS);
        } catch (KeyStoreException e) {
            Log.e(TAG, "KeyStore check failed: " + e.getMessage());
            return false;
        }
    }
}
