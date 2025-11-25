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

    /**
     * コンストラクタ。
     * @param context アプリケーションコンテキスト
     */
    public PreferencesHelper(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 暗号化されたAPIキーデータとIVを保存します。
     * byte[]はBase64エンコードして保存されます。
     * @param encryptedData 暗号化されたデータとIVを含むオブジェクト
     */
    public void saveEncryptedData(EncryptedData encryptedData) {
        if (encryptedData == null || encryptedData.getEncryptedBytes() == null || encryptedData.getIv() == null) {
            Log.e(TAG, "Attempted to save null encrypted data or IV.");
            return;
        }
        
        SharedPreferences.Editor editor = sharedPreferences.edit();
        
        // Base64エンコードして保存
        String encodedData = Base64.encodeToString(encryptedData.getEncryptedBytes(), Base64.DEFAULT);
        String encodedIv = Base64.encodeToString(encryptedData.getIv(), Base64.DEFAULT);

        editor.putString(KEY_ENCRYPTED_DATA, encodedData);
        editor.putString(KEY_IV, encodedIv);
        editor.apply();
        
        Log.i(TAG, "Encrypted data and IV saved successfully.");
    }

    /**
     * 保存された暗号化データとIVをロードします。
     * @return EncryptedDataオブジェクト、データが存在しない場合はnull
     */
    public EncryptedData getEncryptedData() {
        String encodedData = sharedPreferences.getString(KEY_ENCRYPTED_DATA, null);
        String encodedIv = sharedPreferences.getString(KEY_IV, null);

        if (encodedData == null || encodedIv == null) {
            Log.w(TAG, "No encrypted data or IV found in preferences.");
            return null;
        }

        try {
            // Base64デコードしてbyte[]に戻す
            byte[] encryptedBytes = Base64.decode(encodedData, Base64.DEFAULT);
            byte[] iv = Base64.decode(encodedIv, Base64.DEFAULT);
            
            return new EncryptedData(encryptedBytes, iv);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to decode Base64 data: " + e.getMessage());
            // データが壊れている場合は削除して再入力を促す
            deleteEncryptedKey();
            return null;
        }
    }

    /**
     * 暗号化されたAPIキーデータとIVを削除します。
     */
    public void deleteEncryptedKey() {
        sharedPreferences.edit()
            .remove(KEY_ENCRYPTED_DATA)
            .remove(KEY_IV)
            .apply();
        Log.w(TAG, "Encrypted key and IV deleted from preferences.");
    }

    /**
     * 暗号化されたキーが保存されているかチェックします。
     * @return 保存されていればtrue
     */
    public boolean hasEncryptedKey() {
        return sharedPreferences.contains(KEY_ENCRYPTED_DATA) && sharedPreferences.contains(KEY_IV);
    }

    /**
     * 暗号文とそのIVを保持するためのデータ構造。
     * KeyStoreHelperとPreferencesHelper間で共有されます。
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