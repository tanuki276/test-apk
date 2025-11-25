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
        // KeyStoreHelperはコンストラクタ内で鍵の存在チェックと生成を行う
        try {
            keyStoreHelper = new KeyStoreHelper(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "KeyStoreHelper initialization failed: " + e.getMessage());
            Toast.makeText(this, "セキュリティシステム初期化エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // 以降の処理を中止することも検討するが、ここでは続行
        }
        preferencesHelper = new PreferencesHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        // ID修正
        saveButton = findViewById(R.id.button_save_key);
        keySavedPlaceholder = findViewById(R.id.text_key_saved_placeholder);

        // 初期状態でキーが保存されているかチェックし、UIを更新
        updateUiForSavedKey();

        // 戻るボタンのクリックリスナー
        View backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // APIキー保存ボタンのクリックリスナーはupdateUiForSavedKeyで設定される
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
            promptBiometricForDecryption();

        } catch (KeyPermanentlyInvalidatedException e) {
            Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
            Toast.makeText(this, "生体認証情報が変更されました。キーを再作成します。", Toast.LENGTH_LONG).show();
            // KeyStoreのキーを削除し、再生成を促す
            keyStoreHelper.deleteKeyAlias();
            preferencesHelper.deleteEncryptedKey();
            updateUiForSavedKey();
        } catch (Exception e) {
            Log.e(TAG, "Error during encryption/saving: " + e.getMessage());
            Toast.makeText(this, "キーの暗号化に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 復号化のために生体認証を要求する
    private void promptBiometricForDecryption() {
        Executor executor = ContextCompat.getMainExecutor(this);

        if (keyStoreHelper == null || !keyStoreHelper.isKeyAliasExist()) {
            Toast.makeText(this, "セキュリティキーが利用できません。", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            // 1. 復号化に必要なCipherをKeystoreから取得 (CryptoObjectに渡す)
            Cipher cipher = keyStoreHelper.getDecryptCipher();

            BiometricPrompt biometricPrompt = new BiometricPrompt(SettingsActivity.this,
                    executor, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Toast.makeText(getApplicationContext(), "認証エラー: " + errString, Toast.LENGTH_SHORT).show();
                    // 認証失敗時は、再入力を促すために保存したデータを削除する（セキュリティ上の配慮）
                    preferencesHelper.deleteEncryptedKey();
                    updateUiForSavedKey();
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    try {
                        EncryptedData encryptedData = preferencesHelper.getEncryptedData();

                        if (encryptedData == null) {
                            throw new Exception("Encrypted data not found in preferences.");
                        }

                        // 認証済みのCipherをKeyStoreHelperに渡して復号化を実行
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        String decryptedKey = keyStoreHelper.decryptData(encryptedData, authenticatedCipher);

                        if (decryptedKey != null && !decryptedKey.isEmpty()) {
                            Toast.makeText(getApplicationContext(), "APIキーが正常に保存され、認証されました。", Toast.LENGTH_LONG).show();
                            updateUiForSavedKey();
                        } else {
                            Toast.makeText(getApplicationContext(), "キーの復号化に失敗しました。", Toast.LENGTH_LONG).show();
                            preferencesHelper.deleteEncryptedKey(); // 失敗した場合は削除して再入力を促す
                            updateUiForSavedKey();
                        }
                    } catch (KeyPermanentlyInvalidatedException e) {
                        // 生体認証情報変更などでキーが無効化された場合
                        Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
                        Toast.makeText(getApplicationContext(), "セキュリティキーが無効化されました。キーを再入力してください。", Toast.LENGTH_LONG).show();
                        keyStoreHelper.deleteKeyAlias(); // KeyStoreのキーも削除
                        preferencesHelper.deleteEncryptedKey();
                        updateUiForSavedKey();
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption error: " + e.getMessage());
                        Toast.makeText(getApplicationContext(), "復号化エラーが発生しました。", Toast.LENGTH_LONG).show();
                        preferencesHelper.deleteEncryptedKey(); // エラー時はデータ削除
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
        }
    }

    // UI要素の状態を更新する
    private void updateUiForSavedKey() {
        if (preferencesHelper.hasEncryptedKey()) { // PreferencesHelperでチェック
            keySavedPlaceholder.setVisibility(View.VISIBLE);
            apiKeyInput.setVisibility(View.GONE);
            saveButton.setText("APIキーを再設定する");
            saveButton.setOnClickListener(v -> {
                // 再設定ボタンが押されたら暗号化データを削除し、入力フィールドを表示
                preferencesHelper.deleteEncryptedKey();
                updateUiForSavedKey();
            });
        } else {
            keySavedPlaceholder.setVisibility(View.GONE);
            apiKeyInput.setVisibility(View.VISIBLE);
            saveButton.setText(R.string.button_save_key);
            saveButton.setOnClickListener(v -> saveApiKey());
            apiKeyInput.setText(""); // 入力フィールドをクリア
        }
    }

    // BiometricPropertiesクラスは、必要な認証方式を定義するために使用されます。
    // 外部ファイルがないため、内部クラスとして定義します。
    private static class BiometricProperties {
        // 互換性のために必要な認証方式を定義します (生体認証またはデバイス認証)
        public static final int REQUIRED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }
}