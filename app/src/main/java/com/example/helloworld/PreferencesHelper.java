package com.example.liefantidia;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 暗号化されたAPIキーと初期化ベクトル(IV)をSharedPreferencesに保存・読み出しするヘルパークラス
 */
public class PreferencesHelper {

    // SharedPreferences ファイル名
    private static final String PREFS_NAME = "ai_recipe_prefs";

    // 保存キー：暗号化されたAPIキー
    private static final String KEY_ENCRYPTED_DATA = "encrypted_api_data";

    // 保存キー：初期化ベクトル（IV）
    private static final String KEY_IV = "initialization_vector";

    private final SharedPreferences sharedPreferences;

    public PreferencesHelper(@NonNull Context context) {
        // プライベートモードでSharedPreferencesを初期化
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 暗号化されたデータ（APIキー）とIVを保存する
     * @param encryptedDataString Base64でエンコードされた暗号化データ
     * @param ivString Base64でエンコードされたIV
     */
    public void saveEncryptedData(String encryptedDataString, String ivString) {
        sharedPreferences.edit()
                .putString(KEY_ENCRYPTED_DATA, encryptedDataString)
                .putString(KEY_IV, ivString)
                .apply(); // 非同期で保存を実行
    }

    /**
     * 保存されている暗号化されたデータ（APIキー）を読み出す
     * @return 暗号化されたAPIキーの文字列
     */
    public String getEncryptedKey() {
        // キーが存在しない場合は空文字列を返す
        return sharedPreferences.getString(KEY_ENCRYPTED_DATA, "");
    }

    /**
     * 保存されている初期化ベクトル（IV）を読み出す
     * @return IVの文字列
     */
    public String getIv() {
        // キーが存在しない場合は空文字列を返す
        return sharedPreferences.getString(KEY_IV, "");
    }

    /**
     * 暗号化されたキーがSharedPreferencesに存在するかどうかを確認する
     * @return 存在すれば true
     */
    public boolean hasEncryptedKey() {
        // キーが存在し、かつ空文字列でなければ true
        return sharedPreferences.contains(KEY_ENCRYPTED_DATA) && !getEncryptedKey().isEmpty();
    }
    
    /**
     * 保存されている暗号化データとIVを削除する（例：ログアウト時、設定リセット時）
     */
    public void clearEncryptedData() {
        sharedPreferences.edit()
                .remove(KEY_ENCRYPTED_DATA)
                .remove(KEY_IV)
                .apply();
    }
}
