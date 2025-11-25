package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// Android KeyStoreを利用してAPIキーを安全に暗号化/復号化するためのヘルパークラス
public class KeyStoreHelper {
    private static final String TAG = "KeyStoreHelper";
    private static final String KEY_ALIAS = "GeminiApiKeyAlias";
    private static final String PREFS_NAME = "ApiKeyPrefs";
    private static final String PREF_IV = "initializationVector";
    private static final String PREF_ENCRYPTED_KEY = "encryptedApiKey";

    private final Context context;
    private final KeyStore keyStore;
    private final SharedPreferences prefs;

    public KeyStoreHelper(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            // Android KeyStoreのインスタンスを取得
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            createKeyIfNotFound(); // キーがなければ作成
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to initialize KeyStoreHelper", e);
        }
    }

    // KeyStoreに暗号化キーが存在しない場合に新規作成する
    private void createKeyIfNotFound() throws KeyStoreException, NoSuchProviderException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // AES/GCM/NoPaddingアルゴリズムのキー生成設定
            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true) // 生体認証が必要
                    .setInvalidatedByBiometricEnrollment(true) // 生体認証情報が変更されたらキーを無効化
                    .build();

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            keyGenerator.init(keyGenParameterSpec);
            keyGenerator.generateKey();
            Log.d(TAG, "New Key created in KeyStore.");
        }
    }

    // 暗号化されたデータとそのIV (Initialization Vector) を保持するデータクラス
    public static class EncryptedData {
        public final byte[] encryptedBytes;
        public final byte[] iv;

        public EncryptedData(byte[] encryptedBytes, byte[] iv) {
            this.encryptedBytes = encryptedBytes;
            this.iv = iv;
        }

        // SharedPreferencesに保存するためにBase64文字列に変換
        public String getEncryptedBase64() {
            return Base64.getEncoder().encodeToString(encryptedBytes);
        }

        public String getIvBase64() {
            return Base64.getEncoder().encodeToString(iv);
        }
    }

    // データの暗号化
    public EncryptedData encryptData(String data) throws Exception {
        // Android KeyStoreからSecretKeyを取得
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        // 暗号化のためのCipherインスタンスの初期化
        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/"
                + KeyProperties.ENCRYPTION_PADDING_NONE);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] encryptedBytes = cipher.doFinal(data.getBytes("UTF-8"));
        byte[] iv = cipher.getIV(); // IV (初期化ベクトル) は復号化に必要なので保存

        return new EncryptedData(encryptedBytes, iv);
    }

    // データの復号化 (暗号化されたデータとIVが必要)
    public String decryptData(EncryptedData encryptedData, SecretKey secretKey) throws Exception {
        // GCMParameterSpecはIVとタグ長（128ビット）で初期化
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, encryptedData.iv);

        // 復号化のためのCipherインスタンスの初期化
        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/"
                + KeyProperties.ENCRYPTION_PADDING_NONE);

        // 秘密鍵とGCMパラメータで復号モードに設定
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

        byte[] decryptedBytes = cipher.doFinal(encryptedData.encryptedBytes);
        return new String(decryptedBytes, "UTF-8");
    }

    // APIキーをSharedPreferencesに保存（暗号化されたバイトとIVをBase64文字列として）
    public void saveEncryptedData(EncryptedData encryptedData) {
        prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, encryptedData.getEncryptedBase64())
                .putString(PREF_IV, encryptedData.getIvBase64())
                .apply();
        Log.d(TAG, "Encrypted data saved to SharedPreferences.");
    }

    // SharedPreferencesから暗号化されたデータを読み込む
    public EncryptedData getEncryptedData() {
        String encryptedBase64 = prefs.getString(PREF_ENCRYPTED_KEY, null);
        String ivBase64 = prefs.getString(PREF_IV, null);

        if (encryptedBase64 == null || ivBase64 == null) {
            return null;
        }

        try {
            // Base64文字列をバイト配列にデコード
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            return new EncryptedData(encryptedBytes, iv);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode Base64 data: " + e.getMessage());
            return null;
        }
    }

    // KeyStoreからSecretKeyを取得
    public SecretKey getSecretKey() throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException, UnrecoverableEntryException {
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    // APIキーが存在するかチェック
    public boolean isApiKeySaved() {
        return prefs.contains(PREF_ENCRYPTED_KEY);
    }

    // 保存されたキーを削除する
    public void deleteApiKey() {
        prefs.edit().clear().apply();
        Log.d(TAG, "API Key cleared from SharedPreferences.");
    }
}