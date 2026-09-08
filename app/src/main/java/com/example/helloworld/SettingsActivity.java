package com.example.liefantidia2;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** APIキー設定画面。キーはPreferencesHelper経由でAndroid Keystore暗号化保存する。 */
public class SettingsActivity extends AppCompatActivity {

    private EditText apiKeyInput;
    private Button saveButton;
    private TextView keySavedPlaceholder;
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        preferencesHelper = new PreferencesHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        saveButton = findViewById(R.id.button_save_key);
        keySavedPlaceholder = findViewById(R.id.text_key_saved_placeholder);

        if (apiKeyInput != null) {
            apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        updateUiForSavedKey();

        View backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
    }

    private void saveApiKey() {
        String inputKey = apiKeyInput == null ? "" : apiKeyInput.getText().toString().trim();

        if (inputKey.isEmpty()) {
            Toast.makeText(this, R.string.toast_set_api_key, Toast.LENGTH_SHORT).show();
            return;
        }

        // Gemini APIキーの一般的な形式を軽く検証し、明らかな入力ミスを防止。
        if (inputKey.length() < 20 || inputKey.length() > 256) {
            Toast.makeText(this, "APIキーの長さが不正です。キーを確認してください。", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            preferencesHelper.savePlainKey(inputKey);
            Toast.makeText(this, "APIキーを安全に保存しました。", Toast.LENGTH_LONG).show();
            updateUiForSavedKey();
        } catch (Exception e) {
            Toast.makeText(this, "APIキーの安全な保存に失敗しました。", Toast.LENGTH_LONG).show();
        }
    }

    private void updateUiForSavedKey() {
        boolean saved = preferencesHelper.hasSavedKey();

        if (keySavedPlaceholder != null) {
            keySavedPlaceholder.setVisibility(saved ? View.VISIBLE : View.GONE);
        }

        if (apiKeyInput != null) {
            apiKeyInput.setVisibility(saved ? View.GONE : View.VISIBLE);
            if (!saved) {
                apiKeyInput.setText("");
            }
        }

        if (saveButton == null) return;

        if (saved) {
            saveButton.setText("APIキーを削除して再設定");
            saveButton.setOnClickListener(v -> {
                preferencesHelper.deleteAllKeys();
                Toast.makeText(this, "保存済みAPIキーを削除しました。", Toast.LENGTH_SHORT).show();
                updateUiForSavedKey();
            });
        } else {
            saveButton.setText(R.string.button_save_key);
            saveButton.setOnClickListener(v -> saveApiKey());
        }
    }
}
