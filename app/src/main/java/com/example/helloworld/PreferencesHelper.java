package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * APIキーをAndroid Keystore管理のAES-GCM鍵で暗号化して永続化するヘルパー。
 * 平文保存は行わず、旧バージョンで保存された平文キーは読み出し時に暗号化へ移行する。
 */
public class PreferencesHelper {
    private static final String TAG = "PreferencesHelper";
    private static final String PREFS_NAME = "ApiPrefs";

    // 旧バージョン互換。存在した場合は読み出し時に安全な保存形式へ移行する。
    private static final String KEY_PLAIN_DATA = "plain_api_key";

    private static final String KEY_ENCRYPTED_DATA = "encrypted_api_key";
    private static final String KEY_IV = "initialization_vector";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "GeminiApiKeyEncryptionKeyV2";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;

    private final SharedPreferences sharedPreferences;

    public PreferencesHelper(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * APIキーをAndroid Keystoreで暗号化して保存する。
     * メソッド名は既存コードとの互換性のため維持しているが、平文保存はしない。
     */
    public void savePlainKey(String plainKey) {
        if (plainKey == null || plainKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key is empty");
        }

        try {
            EncryptedData encryptedData = encrypt(plainKey.trim());
            saveEncryptedData(encryptedData);
            // 旧形式の平文キーを確実に削除
            sharedPreferences.edit().remove(KEY_PLAIN_DATA).apply();
            Log.i(TAG, "API key saved using Android Keystore encryption.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to securely save API key", e);
            throw new IllegalStateException("APIキーを安全に保存できませんでした", e);
        }
    }

    /**
     * 保存済みAPIキーを取得する。旧版の平文キーが残っている場合は、
     * その場で暗号化してから返す。
     */
    public String getPlainKey() {
        String encryptedKey = getEncryptedPlainKey();
        if (encryptedKey != null) {
            return encryptedKey;
        }

        // 旧バージョンからの移行
        String legacyPlainKey = sharedPreferences.getString(KEY_PLAIN_DATA, null);
        if (legacyPlainKey != null && !legacyPlainKey.trim().isEmpty()) {
            try {
                savePlainKey(legacyPlainKey);
                return legacyPlainKey.trim();
            } catch (Exception e) {
                Log.e(TAG, "Failed to migrate legacy API key", e);
            }
        }

        return null;
    }

    private String getEncryptedPlainKey() {
        EncryptedData data = getEncryptedData();
        if (data == null) {
            return null;
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(GCM_TAG_LENGTH, data.getIv()));
            byte[] decrypted = cipher.doFinal(data.getEncryptedBytes());
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 鍵が無効になった、またはデータが破損した場合は復旧不能な秘密情報を残さない。
            Log.w(TAG, "Stored API key could not be decrypted; clearing it.");
            deleteAllKeys();
            return null;
        }
    }

    private EncryptedData encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return new EncryptedData(encryptedBytes, cipher.getIV());
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            keyGenerator.init(spec);
            keyGenerator.generateKey();
        }

        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    public boolean hasSavedKey() {
        return hasEncryptedKey() || sharedPreferences.contains(KEY_PLAIN_DATA);
    }

    public void deleteAllKeys() {
        sharedPreferences.edit()
                .remove(KEY_PLAIN_DATA)
                .remove(KEY_ENCRYPTED_DATA)
                .remove(KEY_IV)
                .apply();
        Log.i(TAG, "Stored API key data deleted.");
    }

    public void saveEncryptedData(EncryptedData encryptedData) {
        if (encryptedData == null || encryptedData.getEncryptedBytes() == null || encryptedData.getIv() == null) {
            throw new IllegalArgumentException("Encrypted data is invalid");
        }

        sharedPreferences.edit()
                .putString(KEY_ENCRYPTED_DATA, Base64.encodeToString(encryptedData.getEncryptedBytes(), Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(encryptedData.getIv(), Base64.NO_WRAP))
                .remove(KEY_PLAIN_DATA)
                .apply();
    }

    public EncryptedData getEncryptedData() {
        String encodedData = sharedPreferences.getString(KEY_ENCRYPTED_DATA, null);
        String encodedIv = sharedPreferences.getString(KEY_IV, null);

        if (encodedData == null || encodedIv == null) {
            return null;
        }

        try {
            return new EncryptedData(
                    Base64.decode(encodedData, Base64.NO_WRAP),
                    Base64.decode(encodedIv, Base64.NO_WRAP));
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Stored encrypted API key data is invalid; clearing it.");
            deleteEncryptedKey();
            return null;
        }
    }

    public void deleteEncryptedKey() {
        sharedPreferences.edit()
                .remove(KEY_ENCRYPTED_DATA)
                .remove(KEY_IV)
                .apply();
    }

    public boolean hasEncryptedKey() {
        return sharedPreferences.contains(KEY_ENCRYPTED_DATA)
                && sharedPreferences.contains(KEY_IV);
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
