package com.example.liefantidia2;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    // UIコンポーネント
    private EditText apiKeyInput;
    private Button saveButton;
    
    // ヘルパークラス
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // activity_settings.xml (レイアウトファイル) を読み込む
        setContentView(R.layout.activity_settings);

        // タイトルバーに「設定」と表示
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("APIキー設定");
        }

        // ヘルパーの初期化
        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this);

        // UIコンポーネントの関連付け（R.id.〇〇はactivity_settings.xmlで定義されます）
        apiKeyInput = findViewById(R.id.edit_text_api_key);
        saveButton = findViewById(R.id.button_save_key);

        // 保存ボタンのクリックリスナーを設定
        saveButton.setOnClickListener(v -> saveApiKey());

        // 既存の暗号化されたキーが存在する場合、入力欄に「(保存済み)」と表示
        if (preferencesHelper.hasEncryptedKey()) {
            apiKeyInput.setHint("キーは安全に保存されています。変更する場合は入力してください。");
        }
    }

    /**
     * APIキーを暗号化し、ローカルに保存する
     */
    private void saveApiKey() {
        String apiKey = apiKeyInput.getText().toString().trim();

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "APIキーを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        // 非同期処理で暗号化と保存を実行する（※ここではデモのため同期的に記述）
        try {
            // 1. Keystoreに鍵が存在しない場合、生体認証必須の鍵を生成
            if (!keyStoreHelper.isKeyExist()) {
                keyStoreHelper.generateNewKey();
                Log.i(TAG, "New Keystore key generated.");
            }

            // 2. 鍵を使ってAPIキーを暗号化し、IVを取得
            KeyStoreHelper.EncryptedData encryptedData = keyStoreHelper.encryptData(apiKey);
            
            // 3. 暗号化されたデータとIVをSharedPreferencesに保存
            preferencesHelper.saveEncryptedData(encryptedData.encryptedData, encryptedData.iv);

            Toast.makeText(this, "APIキーを安全に保存しました。", Toast.LENGTH_LONG).show();
            
            // 成功したらこの画面を閉じて、前のMainActivityに戻る
            finish(); 

        } catch (Exception e) {
            Log.e(TAG, "Failed to save API Key securely: " + e.getMessage(), e);
            Toast.makeText(this, "保存エラー: " + e.getMessage() + "\n端末のセキュリティ設定を確認してください。", Toast.LENGTH_LONG).show();
        }
    }
}
