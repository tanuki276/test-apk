package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

/**
 * SharedPreferencesを使用して、APIキー（平文または暗号化データ）を永続化するヘルパークラス。
 */
public class PreferencesHelper {
    private static final String TAG = "PreferencesHelper";
    private static final String PREFS_NAME = "ApiPrefs"; 
    
    // 平文キー用
    private static final String KEY_PLAIN_DATA = "plain_api_key";
    
    // 暗号化キー用（以前のコードから維持）
    private static final String KEY_ENCRYPTED_DATA = "encrypted_api_key";
    private static final String KEY_IV = "initialization_vector";

    private final SharedPreferences sharedPreferences;

    public PreferencesHelper(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- 🔑 平文キー用メソッド ---

    public void savePlainKey(String plainKey) {
        sharedPreferences.edit()
            .putString(KEY_PLAIN_DATA, plainKey)
            .apply();
        Log.i(TAG, "Plain API key saved successfully.");
        // クリーンアップ：暗号化キーが残っていれば削除
        deleteEncryptedKey();
    }

    public String getPlainKey() {
        return sharedPreferences.getString(KEY_PLAIN_DATA, null);
    }

    public boolean hasSavedKey() {
        // 平文キー、または既存の暗号化キーが存在すればtrue
        return sharedPreferences.contains(KEY_PLAIN_DATA) || hasEncryptedKey();
    }
    
    public void deleteAllKeys() {
        sharedPreferences.edit()
            .remove(KEY_PLAIN_DATA)
            .remove(KEY_ENCRYPTED_DATA)
            .remove(KEY_IV)
            .apply();
        Log.w(TAG, "All API keys deleted from preferences.");
    }

    // --- 🔒 暗号化キー用メソッド (不使用だが維持) ---

    public void saveEncryptedData(EncryptedData encryptedData) {
        if (encryptedData == null || encryptedData.getEncryptedBytes() == null || encryptedData.getIv() == null) {
            Log.e(TAG, "Attempted to save null encrypted data or IV.");
            return;
        }

        SharedPreferences.Editor editor = sharedPreferences.edit();

        String encodedData = Base64.encodeToString(encryptedData.getEncryptedBytes(), Base64.DEFAULT);
        String encodedIv = Base64.encodeToString(encryptedData.getIv(), Base64.DEFAULT);

        editor.putString(KEY_ENCRYPTED_DATA, encodedData);
        editor.putString(KEY_IV, encodedIv);
        editor.apply();

        Log.i(TAG, "Encrypted data and IV saved successfully.");
    }

    public EncryptedData getEncryptedData() {
        String encodedData = sharedPreferences.getString(KEY_ENCRYPTED_DATA, null);
        String encodedIv = sharedPreferences.getString(KEY_IV, null);

        if (encodedData == null || encodedIv == null) {
            Log.w(TAG, "No encrypted data or IV found in preferences.");
            return null;
        }

        try {
            byte[] encryptedBytes = Base64.decode(encodedData, Base64.DEFAULT);
            byte[] iv = Base64.decode(encodedIv, Base64.DEFAULT);

            return new EncryptedData(encryptedBytes, iv);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode Base64 data: " + e.getMessage());
            deleteEncryptedKey();
            return null;
        }
    }

    public void deleteEncryptedKey() {
        sharedPreferences.edit()
            .remove(KEY_ENCRYPTED_DATA)
            .remove(KEY_IV)
            .apply();
        Log.w(TAG, "Encrypted key and IV deleted from preferences.");
    }

    public boolean hasEncryptedKey() {
        return sharedPreferences.contains(KEY_ENCRYPTED_DATA) && sharedPreferences.contains(KEY_IV);
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
