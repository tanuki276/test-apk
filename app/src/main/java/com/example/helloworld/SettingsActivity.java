package com.example.liefantidia2;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

// APIキーの設定を管理するアクティビティ (暗号化無効版)
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private EditText apiKeyInput;
    private Button saveButton;
    private TextView keySavedPlaceholder;
    private KeyStoreHelper keyStoreHelper; // 👈 宣言は残すが使用しない
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 👈 修正: KeyStoreHelperの初期化は削除
        keyStoreHelper = null; 
        
        preferencesHelper = new PreferencesHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        saveButton = findViewById(R.id.button_save_key);
        keySavedPlaceholder = findViewById(R.id.text_key_saved_placeholder);

        updateUiForSavedKey();

        View backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        saveButton.setOnClickListener(v -> saveApiKey());
    }

    // APIキーの保存処理 (平文保存)
    private void saveApiKey() {
        String inputKey = apiKeyInput.getText().toString().trim();

        if (inputKey.isEmpty()) {
            Toast.makeText(this, "APIキーを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 👈 修正: 平文のキーを直接SharedPreferencesに保存する
            preferencesHelper.savePlainKey(inputKey);

            Toast.makeText(this, "APIキーが保存されました。", Toast.LENGTH_LONG).show();
            updateUiForSavedKey();
            finish();

        } catch (Exception e) {
            Log.e(TAG, "Error during saving key (Plain): " + e.getMessage());
            Toast.makeText(this, "キーの保存に失敗しました。", Toast.LENGTH_LONG).show();
            preferencesHelper.deleteAllKeys(); 
        }
    }

    // 👈 修正: UI要素の状態を更新する (平文版)
    private void updateUiForSavedKey() {
        if (preferencesHelper.hasSavedKey()) { 
            keySavedPlaceholder.setVisibility(View.VISIBLE);
            apiKeyInput.setVisibility(View.GONE);
            saveButton.setText(R.string.button_reset_key); // リソースIDに合わせて修正
            saveButton.setOnClickListener(v -> {
                preferencesHelper.deleteAllKeys(); 
                // keyStoreHelperのdeleteKeyAliasの呼び出しも不要
                updateUiForSavedKey();
            });
        } else {
            keySavedPlaceholder.setVisibility(View.GONE);
            apiKeyInput.setVisibility(View.VISIBLE);
            saveButton.setText(R.string.button_save_key);
            saveButton.setOnClickListener(v -> saveApiKey());
            apiKeyInput.setText("");
        }
    }
    
    // ⚠ 生体認証関連のメソッド (promptBiometricForDecryption, BiometricProperties) は削除
}
