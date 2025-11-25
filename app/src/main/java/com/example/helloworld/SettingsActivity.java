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
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import javax.crypto.SecretKey;

// APIキーの設定と生体認証による暗号化/復号化を管理するアクティビティ
public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private EditText apiKeyInput;
    private Button saveButton;
    private TextView keySavedPlaceholder;
    private KeyStoreHelper keyStoreHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // APIキー暗号化ヘルパーの初期化
        keyStoreHelper = new KeyStoreHelper(this);

        apiKeyInput = findViewById(R.id.edit_text_api_key);
        // エラー修正: activity_settings.xml の ID に合わせる
        saveButton = findViewById(R.id.button_save_key);
        keySavedPlaceholder = findViewById(R.id.text_key_saved_placeholder);

        // 初期状態でキーが保存されているかチェックし、UIを更新
        updateUiForSavedKey();

        // 戻るボタンのクリックリスナー (存在する場合)
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

        try {
            // 入力されたキーを暗号化
            KeyStoreHelper.EncryptedData encryptedData = keyStoreHelper.encryptData(inputKey);
            // 暗号化されたデータとIVをSharedPreferencesに保存
            keyStoreHelper.saveEncryptedData(encryptedData);

            // 保存が成功したら、キーを復号化して認証を試みる
            promptBiometricForDecryption();

        } catch (KeyPermanentlyInvalidatedException e) {
            Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
            Toast.makeText(this, "生体認証情報が変更されました。キーを再作成します。", Toast.LENGTH_LONG).show();
            // キーが無効化された場合、KeyStoreHelperの初期化時に再作成されるはずですが、
            // 念のため、ここではキー入力を求め続ける
        } catch (Exception e) {
            Log.e(TAG, "Error during encryption/saving: " + e.getMessage());
            Toast.makeText(this, "キーの暗号化に失敗しました。", Toast.LENGTH_LONG).show();
        }
    }

    // 復号化のために生体認証を要求する
    private void promptBiometricForDecryption() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(SettingsActivity.this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(getApplicationContext(), "認証エラー: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                try {
                    // 認証成功後、KeyStoreからSecretKeyを取得
                    SecretKey secretKey = keyStoreHelper.getSecretKey();
                    KeyStoreHelper.EncryptedData encryptedData = keyStoreHelper.getEncryptedData();

                    if (encryptedData != null) {
                        // 復号化を試みる (認証成功のため鍵が利用可能)
                        String decryptedKey = keyStoreHelper.decryptData(encryptedData, secretKey);

                        if (decryptedKey != null && !decryptedKey.isEmpty()) {
                            Toast.makeText(getApplicationContext(), "APIキーが正常に保存され、認証されました。", Toast.LENGTH_LONG).show();
                            updateUiForSavedKey();
                        } else {
                            Toast.makeText(getApplicationContext(), "キーの復号化に失敗しました。", Toast.LENGTH_LONG).show();
                            keyStoreHelper.deleteApiKey(); // 失敗した場合は削除して再入力を促す
                            updateUiForSavedKey();
                        }
                    } else {
                        Toast.makeText(getApplicationContext(), "暗号化されたデータが見つかりません。", Toast.LENGTH_LONG).show();
                    }
                } catch (KeyPermanentlyInvalidatedException e) {
                    // 生体認証情報変更などでキーが無効化された場合
                    Log.e(TAG, "Key permanently invalidated: " + e.getMessage());
                    Toast.makeText(getApplicationContext(), "生体認証情報が変更されました。キーを再入力してください。", Toast.LENGTH_LONG).show();
                    keyStoreHelper.deleteApiKey();
                    updateUiForSavedKey();
                } catch (Exception e) {
                    Log.e(TAG, "Decryption error: " + e.getMessage());
                    Toast.makeText(getApplicationContext(), "復号化エラーが発生しました。", Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getApplicationContext(), "認証失敗。APIキーは保存されません。", Toast.LENGTH_SHORT).show();
            }
        });

        // 認証ダイアログの表示
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("APIキーの保存と認証")
                .setSubtitle("指紋認証またはPINでキーの利用を許可してください")
                .setAllowedAuthenticators(BiometricProperties.REQUIRED_AUTHENTICATORS)
                .build();

        // KeyStoreからSecretKeyを取得する処理を開始し、認証ダイアログを表示
        try {
            BiometricPrompt.CryptoObject cryptoObject = new BiometricPrompt.CryptoObject(keyStoreHelper.getSecretKey());
            biometricPrompt.authenticate(promptInfo, cryptoObject);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get CryptoObject: " + e.getMessage());
            Toast.makeText(this, "キー認証システムの準備に失敗しました。", Toast.LENGTH_LONG).show();
        }
    }

    // UI要素の状態を更新する
    private void updateUiForSavedKey() {
        if (keyStoreHelper.isApiKeySaved()) {
            keySavedPlaceholder.setVisibility(View.VISIBLE);
            apiKeyInput.setVisibility(View.GONE);
            saveButton.setText("APIキーを再設定する");
            saveButton.setOnClickListener(v -> {
                // 再設定ボタンが押されたら入力フィールドを表示し、APIキーを削除
                keyStoreHelper.deleteApiKey();
                updateUiForSavedKey();
            });
        } else {
            keySavedPlaceholder.setVisibility(View.GONE);
            apiKeyInput.setVisibility(View.VISIBLE);
            saveButton.setText(R.string.button_save_key); // リソースからテキストを取得
            saveButton.setOnClickListener(v -> saveApiKey());
            apiKeyInput.setText(""); // 入力フィールドをクリア
        }
    }
}

// BiometricPropertiesクラスは、必要な認証方式を定義するために使用されます。
// 実際のアプリでは、BiometricManager.Authenticators 定数を使用します。
class BiometricProperties {
    // 互換性のために必要な認証方式を定義します (生体認証またはデバイス認証)
    public static final int REQUIRED_AUTHENTICATORS = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG |
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
}