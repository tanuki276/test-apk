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

import java.security.InvalidAlgorithmParameterException;
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

        try {
            keyStoreHelper = new KeyStoreHelper(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "KeyStoreHelper initialization failed: " + Log.getStackTraceString(e));
            
            // 👈 発生源Aのトーストメッセージを改善（ユーザーフレンドリーに）
            String userMessage = "セキュリティシステム初期化エラーが発生しました。端末を再起動してください。";
            if (e.getCause() instanceof InvalidAlgorithmParameterException) {
                 userMessage = "エラー: 画面ロック（PIN/パスワード）と指紋認証が設定されているか確認してください。";
            }
            
            Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show();
            keyStoreHelper = null;
        }
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

            // 暗号化結果の防御的チェック
            if (encryptedData == null || encryptedData.getIv() == null) {
                Toast.makeText(this, "暗号化結果が不正です。データは保存されませんでした。", Toast.LENGTH_LONG).show();
                return;
            }

            // 2. 暗号化されたデータとIVをSharedPreferencesに保存
            preferencesHelper.saveEncryptedData(encryptedData);

            // 3. 保存が成功したら、キーを復号化して認証を試みる (保存確認のため)
            promptBiometricForDecryption(encryptedData);

        } catch (KeyPermanentlyInvalidatedException e) {
            Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
            Toast.makeText(this, "生体認証情報が変更されました。キーを再作成します。", Toast.LENGTH_LONG).show();
            if (keyStoreHelper != null) {
                keyStoreHelper.deleteKeyAlias();
            }
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
        } catch (Exception e) {
            // エラーメッセージが null の場合の代替メッセージ
            Log.e(TAG, "Error during encryption/saving: " + Log.getStackTraceString(e));
            String errorMessage = e.getMessage() != null ? e.getMessage() : "原因不明のシステムエラー";
            Toast.makeText(this, "キーの暗号化に失敗しました: " + errorMessage, Toast.LENGTH_LONG).show();

            // 暗号化エラーが発生した場合、不正なデータが保存されるのを防ぐために削除
            preferencesHelper.deleteEncryptedKey();
        }
    }

    // 復号化のために生体認証を要求する
    private void promptBiometricForDecryption(EncryptedData encryptedData) {
        Executor executor = ContextCompat.getMainExecutor(this);

        if (keyStoreHelper == null || !keyStoreHelper.isKeyAliasExist()) {
            Toast.makeText(this, "セキュリティキーが利用できません。", Toast.LENGTH_LONG).show();
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
            return;
        }

        try {
            Cipher cipher = keyStoreHelper.getDecryptCipher(encryptedData.getIv());

            BiometricPrompt biometricPrompt = new BiometricPrompt(SettingsActivity.this,
                    executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    // 認証エラー時はデータ削除せず、ユーザーの再試行を許可するほうが親切な場合が多い
                    Toast.makeText(getApplicationContext(), "認証エラー: " + errString, Toast.LENGTH_SHORT).show();
                    // preferencesHelper.deleteEncryptedKey(); // 👈 削除をコメントアウト（再試行可能に）
                    updateUiForSavedKey();
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    try {
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        String decryptedKey = keyStoreHelper.decryptData(encryptedData, authenticatedCipher);

                        // KeyStoreHelperの修正により、メッセージもSession-basedに合わせる
                        if (decryptedKey != null && !decryptedKey.isEmpty()) {
                            Toast.makeText(getApplicationContext(), "APIキーが正常に保存され、認証されました。（セッション中再認証不要）", Toast.LENGTH_LONG).show();
                            updateUiForSavedKey();
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
                    // 認証失敗時はデータ削除せず、ユーザーの再試行を許可するほうが親切な場合が多い
                    Toast.makeText(getApplicationContext(), "認証失敗。再試行してください。", Toast.LENGTH_SHORT).show();
                    // preferencesHelper.deleteEncryptedKey(); // 👈 削除をコメントアウト（再試行可能に）
                    updateUiForSavedKey();
                }
            });

            // 👈 BiometricPromptにNegativeButtonTextを追加 (BIOMETRIC_STRONG使用時の必須要件)
            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("APIキーの保存と認証")
                    .setSubtitle("指紋認証でキーの利用を許可してください")
                    .setAllowedAuthenticators(BiometricProperties.REQUIRED_AUTHENTICATORS)
                    .setNegativeButtonText("キャンセル") // 👈 必須設定の追加
                    .build();

            biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));

        } catch (Exception e) {
            Log.e(TAG, "Failed to get Cipher for CryptoObject: " + Log.getStackTraceString(e));
            // 発生源Bのトーストメッセージ
            Toast.makeText(this, "キー認証システムの準備に失敗しました。", Toast.LENGTH_LONG).show();
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
                preferencesHelper.deleteEncryptedKey();
                // deleteKeyAliasも実行することで、確実に鍵をリセット
                if (keyStoreHelper != null) {
                    keyStoreHelper.deleteKeyAlias();
                }
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

    private static class BiometricProperties {
        // BIOMETRIC_STRONG (指紋などの生体認証のみ) を使用
        public static final int REQUIRED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;
    }
}
