package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferencesHelper {
    private static final String PREFS_NAME = "api_key_prefs";
    private static final String KEY_ENCRYPTED_API_KEY = "encrypted_api_key";
    private static final String KEY_IV = "iv_for_decryption"; // IVを保存するキー

    private final SharedPreferences sharedPreferences;

    public PreferencesHelper(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 暗号化されたAPIキーとIVを保存する
     */
    public void saveEncryptedData(String encryptedKey, String iv) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_ENCRYPTED_API_KEY, encryptedKey);
        editor.putString(KEY_IV, iv);
        editor.apply();
    }

    /**
     * 暗号化されたAPIキーを取得する
     */
    public String getEncryptedKey() {
        return sharedPreferences.getString(KEY_ENCRYPTED_API_KEY, null);
    }
    
    /**
     * IVを取得する
     */
    public String getIv() {
        return sharedPreferences.getString(KEY_IV, null);
    }
    
    /**
     * 暗号化キーとIVの両方が存在するか確認する
     */
    public boolean hasEncryptedKey() {
        // キーとIVの両方が存在する場合にのみtrueを返す
        return sharedPreferences.contains(KEY_ENCRYPTED_API_KEY) && sharedPreferences.contains(KEY_IV);
    }

    /**
     * 保存されているキーデータをすべてクリアする
     */
    public void clearEncryptedData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(KEY_ENCRYPTED_API_KEY);
        editor.remove(KEY_IV); // ★修正点★: IVも同時にクリアする
        editor.apply();
    }
}