package com.example.liefantidia;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    
    // UIコンポーネント
    private EditText ingredientInput;
    private Button generateRecipeButton;
    private Button settingsButton;

    // APIキー関連
    private String apiKey = null; // 復号化されたAPIキーを保持
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // activity_main.xml (レイアウトファイル) を読み込む
        setContentView(R.layout.activity_main); 
        
        // ヘルパークラスの初期化
        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this);

        // UIコンポーネントの初期化
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());

        // アプリ起動時にキーを読み出す処理を開始
        loadApiKey();
    }

    /**
     * APIキーを安全なストレージからロードし、必要に応じて生体認証を要求する
     */
    private void loadApiKey() {
        try {
            // 鍵が存在するかチェック
            if (!keyStoreHelper.isKeyExist()) {
                Log.i(TAG, "API Key not configured. Opening settings.");
                Toast.makeText(this, "APIキーを設定してください。", Toast.LENGTH_LONG).show();
                openSettings();
                return;
            }

            // 暗号化されたキーが存在するかチェック
            if (!preferencesHelper.hasEncryptedKey()) {
                 Log.i(TAG, "Encrypted API Key not found. Opening settings.");
                 Toast.makeText(this, "APIキーを再設定してください。", Toast.LENGTH_LONG).show();
                 openSettings();
                 return;
            }

            // Keystoreから鍵を取得し、復号化用のCipherを初期化する
            Cipher cipher = keyStoreHelper.getDecryptCipher();
            
            // 生体認証が必要な場合は認証を開始する
            showBiometricPrompt(cipher);

        } catch (Exception e) {
            Log.e(TAG, "Error loading API Key components: " + e.getMessage());
            Toast.makeText(this, "セキュリティ設定エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // 重大なエラーのため、設定画面に誘導
            openSettings();
        }
    }

    /**
     * BiometricPrompt (生体認証) を表示する
     */
    private void showBiometricPrompt(Cipher cipher) {
        Executor executor = ContextCompat.getMainExecutor(this);
        
        BiometricPrompt biometricPrompt = new BiometricPrompt(
            this,
            executor,
            new BiometricPrompt.AuthenticationCallback() {
                // 認証成功
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    // 認証が成功したら、暗号化されたキーを復号化する
                    try {
                        String encryptedKey = preferencesHelper.getEncryptedKey();
                        apiKey = keyStoreHelper.decryptData(encryptedKey, cipher);
                        Log.d(TAG, "API Key successfully decrypted and loaded.");
                        Toast.makeText(MainActivity.this, "APIキーの認証に成功しました", Toast.LENGTH_SHORT).show();
                        // 復号化成功後、ボタンを有効化する
                        generateRecipeButton.setEnabled(true);
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed: " + e.getMessage());
                        Toast.makeText(MainActivity.this, "キーの復号化に失敗しました", Toast.LENGTH_LONG).show();
                    }
                }

                // 認証失敗
                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(MainActivity.this, "認証失敗", Toast.LENGTH_SHORT).show();
                    generateRecipeButton.setEnabled(false);
                }

                // 認証エラー（端末非対応、キャンセルなど）
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    Log.w(TAG, "Authentication error: " + errString);
                    Toast.makeText(MainActivity.this, "認証エラー: " + errString, Toast.LENGTH_LONG).show();
                    // エラー時に設定画面を開くなど、セキュリティ上の対応を検討
                }
            });

        // 認証ダイアログの情報設定
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("セキュリティ認証")
            .setSubtitle("APIキーを使用するため、指紋またはPINが必要です。")
            .setNegativeButtonText("設定画面へ") // 認証エラー時のフォールバック先
            .build();

        // 認証開始
        biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
        generateRecipeButton.setEnabled(false); // 認証が終わるまでボタンは無効化
    }


    /**
     * APIキー設定画面を開く
     */
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    /**
     * レシピ生成処理を開始する (API通信ロジックはここに追加)
     */
    private void startRecipeGeneration() {
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "APIキーがロードされていません。認証してください。", Toast.LENGTH_LONG).show();
            // 未認証なら再度ロードを試みる
            loadApiKey();
            return;
        }

        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, "食材を入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        // ★高度なロジック（プロンプト生成、API通信、ストリーミング処理など）をこの後に追加する
        Toast.makeText(this, "レシピ生成を開始します...", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Starting generation with key: " + apiKey.substring(0, 5) + "..."); // キーの一部を表示（デバッグ用）
    }

    // SettingsActivityから戻ってきた時にキーの再ロードを試みる
    @Override
    protected void onResume() {
        super.onResume();
        // 既にロードされていない、またはキーがクリアされている場合に再認証を試みる
        if (apiKey == null && preferencesHelper.hasEncryptedKey()) {
             loadApiKey();
        }
    }
}
