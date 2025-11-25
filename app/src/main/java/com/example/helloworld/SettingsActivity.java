package com.example.liefantidia2;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.security.KeyStoreException;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";

    private EditText apiKeyInput;
    private Button saveButton;

    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        saveButton = findViewById(R.id.button_save_api_key);

        // 現在のキーの状態を表示
        displayCurrentKeyStatus();

        saveButton.setOnClickListener(v -> saveApiKey());
    }

    /**
     * 現在のキーの状態（保存されているか）をUIに反映する
     */
    private void displayCurrentKeyStatus() {
        if (preferencesHelper.hasEncryptedKey()) {
            apiKeyInput.setText(getString(R.string.key_saved_placeholder)); // "キーは安全に保存されています"
            apiKeyInput.setEnabled(true); // 変更できるようにしておく
            saveButton.setText("APIキーを変更して認証する");
        } else {
            apiKeyInput.setText("");
            saveButton.setText("APIキーを保存して認証する");
        }
    }

    /**
     * APIキーの保存と暗号化処理
     */
    private void saveApiKey() {
        String inputKey = apiKeyInput.getText().toString().trim();

        // プレースホルダーのテキストであれば、何もしないで終了
        if (inputKey.equals(getString(R.string.key_saved_placeholder))) {
            Toast.makeText(this, "APIキーは既に保存されています。", Toast.LENGTH_SHORT).show();
            return;
        }

        if (inputKey.isEmpty()) {
            Toast.makeText(this, "APIキーを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 1. 既存のキーがある場合は削除し、新規作成する（IVを確実にリセットするため）
            if (keyStoreHelper.isKeyExist()) {
                keyStoreHelper.deleteKey();
                Log.d(TAG, "Existing Keystore key deleted for fresh setup.");
            }
            
            // 2. 新しいキーを生成する（新しい鍵とIVのために）
            keyStoreHelper.generateNewKey();
            
            // 3. APIキーを暗号化
            KeyStoreHelper.EncryptedData encryptedData = keyStoreHelper.encryptData(inputKey);

            // 4. 暗号化データとIVをPreferencesに保存 (★IVの保存を確実にするため、明示的にチェック)
            if (encryptedData.iv != null && !encryptedData.iv.isEmpty()) {
                preferencesHelper.saveEncryptedData(encryptedData.encryptedData, encryptedData.iv);
                Log.d(TAG, "Encrypted key and IV successfully saved to SharedPreferences.");
                
                // 5. 成功トーストを表示し、画面を閉じる
                Toast.makeText(this, "APIキーを安全に保存しました。", Toast.LENGTH_LONG).show();
                displayCurrentKeyStatus(); // UIを更新
                finish(); // MainActivityに戻ることで、再認証プロセスが開始される
            } else {
                 // IVが取得できなかった場合のエラー処理
                throw new Exception("Encryption failed to generate a valid IV (Initialization Vector).");
            }

        } catch (Exception e) {
            Log.e(TAG, "Save Error: " + e.getMessage(), e);
            // 鍵の存在チェックエラーは特に処理が必要。鍵の生成に失敗した場合も含む。
            String errorMessage;
            if (e.getMessage() != null && e.getMessage().contains("User authentication required")) {
                // デバイスに指紋やPINロックが設定されていない場合のヒント
                errorMessage = "保存エラー: 生体認証キーの生成に失敗しました。端末の画面ロック（PIN/指紋/顔認証など）が有効になっていることを確認してください。";
            } else if (e instanceof KeyStoreException) {
                errorMessage = "保存エラー: KeyStoreに問題が発生しました。";
            } else {
                errorMessage = "保存エラー: " + e.getMessage();
            }
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }
}