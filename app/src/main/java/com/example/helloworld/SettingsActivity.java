package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

/**
 * SharedPreferencesを使用して、暗号化されたAPIキーとIV (Initialization Vector) を永続化するヘルパークラス。
 */
public class PreferencesHelper {
    private static final String TAG = "PreferencesHelper";
    private static final String PREFS_NAME = "EncryptedApiPrefs";
    private static final String KEY_ENCRYPTED_DATA = "encrypted_api_key";
    private static final String KEY_IV = "initialization_vector";

    private final SharedPreferences sharedPreferences;

    public PreferencesHelper(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

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

    /**
     * 暗号文とそのIVを保持するためのデータ構造。
     */
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
