package com.example.liefantidia2;

import android.os.Bundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.example.liefantidia2.PreferencesHelper.EncryptedData;

import java.util.concurrent.Executor;

import javax.crypto.Cipher;

// APIキーの設定と生体認証による暗号化/復号化を管理するアクティビティ
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private EditText apiKeyInput;
    private Button saveButton;
    private TextView keySavedPlaceholder;
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // APIキー暗号化ヘルパーとプリファレンスヘルパーの初期化 (Contextを渡す)
        try {
            keyStoreHelper = new KeyStoreHelper(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "KeyStoreHelper initialization failed: " + e.getMessage());
            Toast.makeText(this, "セキュリティシステム初期化エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // 初期化失敗時はhelperをnullにして以降の処理をガード
            keyStoreHelper = null;
        }
        preferencesHelper = new PreferencesHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        saveButton = findViewById(R.id.button_save_key);
        keySavedPlaceholder = findViewById(R.id.text_key_saved_placeholder);

        // 初期状態でキーが保存されているかチェックし、UIを更新
        updateUiForSavedKey();

        // 戻るボタンのクリックリスナー
        View backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // APIキー保存ボタンのクリックリスナー
        saveButton.setOnClickListener(v -> saveApiKey());
    }

    // APIキーの保存処理
    private void saveApiKey() {
        String inputKey = apiKeyInput.getText().toString().trim();

        if (inputKey.isEmpty()) {
            Toast.makeText(this, "APIキーを入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        if (keyStoreHelper == null) {
            Toast.makeText(this, "セキュリティシステムの準備ができていません。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 1. 入力されたキーを暗号化 (KeyStoreのキーを使用)
            EncryptedData encryptedData = keyStoreHelper.encryptData(inputKey);
            // 2. 暗号化されたデータとIVをSharedPreferencesに保存
            preferencesHelper.saveEncryptedData(encryptedData);

            // 3. 保存が成功したら、キーを復号化して認証を試みる (保存確認のため)
            // ここでIVを渡す必要があります！
            promptBiometricForDecryption(encryptedData);

        } catch (KeyPermanentlyInvalidatedException e) {
            Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
            Toast.makeText(this, "生体認証情報が変更されました。キーを再作成します。", Toast.LENGTH_LONG).show();
            // クリーンアップ
            if (keyStoreHelper != null) {
                keyStoreHelper.deleteKeyAlias();
            }
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
        } catch (Exception e) {
            Log.e(TAG, "Error during encryption/saving: " + e.getMessage());
            Toast.makeText(this, "キーの暗号化に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 復号化のために生体認証を要求する
    private void promptBiometricForDecryption(EncryptedData encryptedData) {
        Executor executor = ContextCompat.getMainExecutor(this);

        if (keyStoreHelper == null || !keyStoreHelper.isKeyAliasExist()) {
            Toast.makeText(this, "セキュリティキーが利用できません。", Toast.LENGTH_LONG).show();
            // 認証不可なので、保存データも削除して再入力を促す
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
            return;
        }

        try {
            // 1. 復号化に必要なCipherをKeystoreから取得 (CryptoObjectに渡す)
            // ここでIVを渡してCipherを初期化します
            Cipher cipher = keyStoreHelper.getDecryptCipher(encryptedData.getIv());

            BiometricPrompt biometricPrompt = new BiometricPrompt(SettingsActivity.this,
                    executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Toast.makeText(getApplicationContext(), "認証エラー: " + errString, Toast.LENGTH_SHORT).show();
                    // 認証失敗時は、保存したデータを削除する（セキュリティ上の配慮）
                    preferencesHelper.deleteEncryptedKey();
                    updateUiForSavedKey();
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    try {
                        // 認証済みのCipherをKeyStoreHelperに渡して復号化を実行
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        String decryptedKey = keyStoreHelper.decryptData(encryptedData, authenticatedCipher);

                        if (decryptedKey != null && !decryptedKey.isEmpty()) {
                            Toast.makeText(getApplicationContext(), "APIキーが正常に保存され、認証されました。", Toast.LENGTH_LONG).show();
                            updateUiForSavedKey();
                            // 成功したらアクティビティを終了してMainActivityに戻る
                            finish(); 
                        } else {
                            Toast.makeText(getApplicationContext(), "キーの復号化に失敗しました。", Toast.LENGTH_LONG).show();
                            preferencesHelper.deleteEncryptedKey();
                            updateUiForSavedKey();
                        }
                    } catch (KeyPermanentlyInvalidatedException e) {
                        Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
                        Toast.makeText(getApplicationContext(), "セキュリティキーが無効化されました。キーを再入力してください。", Toast.LENGTH_LONG).show();
                        if (keyStoreHelper != null) {
                            keyStoreHelper.deleteKeyAlias();
                        }
                        preferencesHelper.deleteEncryptedKey();
                        updateUiForSavedKey();
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption error: " + e.getMessage());
                        Toast.makeText(getApplicationContext(), "復号化エラーが発生しました。", Toast.LENGTH_LONG).show();
                        preferencesHelper.deleteEncryptedKey();
                        updateUiForSavedKey();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(getApplicationContext(), "認証失敗。APIキーを使用できません。", Toast.LENGTH_SHORT).show();
                    // 認証失敗時はデータを削除して再入力を促す
                    preferencesHelper.deleteEncryptedKey();
                    updateUiForSavedKey();
                }
            });

            // 認証ダイアログの表示
            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("APIキーの保存と認証")
                    .setSubtitle("指紋認証またはPINでキーの利用を許可してください")
                    .setAllowedAuthenticators(BiometricProperties.REQUIRED_AUTHENTICATORS)
                    .build();

            // BiometricPromptにCipherをCryptoObjectとして渡す
            biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));

        } catch (Exception e) {
            Log.e(TAG, "Failed to get Cipher for CryptoObject: " + e.getMessage());
            Toast.makeText(this, "キー認証システムの準備に失敗しました。", Toast.LENGTH_LONG).show();
            // 認証プロセス開始前のエラーもデータを削除
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
        }
    }

    // UI要素の状態を更新する
    private void updateUiForSavedKey() {
        if (preferencesHelper.hasEncryptedKey()) {
            keySavedPlaceholder.setVisibility(View.VISIBLE);
            apiKeyInput.setVisibility(View.GONE);
            saveButton.setText("APIキーを再設定する");
            saveButton.setOnClickListener(v -> {
                // 再設定ボタンが押されたら暗号化データを削除し、入力フィールドを表示
                preferencesHelper.deleteEncryptedKey();
                // KeyStoreのキーは保持する（頻繁な再生成を防ぐため）
                updateUiForSavedKey();
            });
        } else {
            keySavedPlaceholder.setVisibility(View.GONE);
            apiKeyInput.setVisibility(View.VISIBLE);
            saveButton.setText(R.string.button_save_key);
            // リスナーを再設定（再設定モードから保存モードに戻すため）
            saveButton.setOnClickListener(v -> saveApiKey());
            apiKeyInput.setText("");
        }
    }

    // 内部クラスとして定義
    private static class BiometricProperties {
        public static final int REQUIRED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }
}