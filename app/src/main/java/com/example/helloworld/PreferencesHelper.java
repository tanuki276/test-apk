package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

// 暗号化されたデータとそのIV (Initialization Vector) をSharedPreferencesで管理するヘルパークラス
public class PreferencesHelper {
    private static final String TAG = "PreferencesHelper";
    private static final String PREFS_NAME = "ApiKeyPrefs";
    private static final String PREF_IV = "initializationVector";
    private static final String PREF_ENCRYPTED_KEY = "encryptedApiKey";

    private final SharedPreferences prefs;

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
            // Android Base64を使用
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT);
        }

        public String getIvBase64() {
            // Android Base64を使用
            return Base64.encodeToString(iv, Base64.DEFAULT);
        }
    }

    public PreferencesHelper(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
    @Nullable
    public EncryptedData getEncryptedData() {
        String encryptedBase64 = prefs.getString(PREF_ENCRYPTED_KEY, null);
        String ivBase64 = prefs.getString(PREF_IV, null);

        if (encryptedBase64 == null || ivBase64 == null) {
            return null;
        }

        try {
            // Base64文字列をバイト配列にデコード (Android Base64を使用)
            byte[] encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT);
            byte[] iv = Base64.decode(ivBase64, Base64.DEFAULT);
            return new EncryptedData(encryptedBytes, iv);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode Base64 data: " + e.getMessage());
            return null;
        }
    }

    // APIキーが保存されているかチェック
    public boolean hasEncryptedKey() {
        return prefs.contains(PREF_ENCRYPTED_KEY) && prefs.contains(PREF_IV);
    }

    // 保存されたキーを削除する
    public void deleteEncryptedKey() {
        prefs.edit().remove(PREF_ENCRYPTED_KEY).remove(PREF_IV).apply();
        Log.d(TAG, "API Key data cleared from SharedPreferences.");
    }
}