package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント (R.id.〇〇はactivity_main.xmlで定義されていることを前提とします)
    private EditText ingredientInput;
    private TextView recipeOutputText; // レシピ出力用TextView
    private Button generateRecipeButton;
    private Button settingsButton;
    private ProgressBar loadingIndicator; // ローディング表示用

    // APIキー関連
    private String apiKey = null; // 復号化されたAPIキーを保持
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // activity_main.xmlを読み込みます

        // ヘルパークラスとAPIクライアントの初期化
        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient(); // GeminiApiClientを初期化

        // UIコンポーネントの初期化
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        // ★新規追加: レシピ表示とローディング
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        loadingIndicator.setVisibility(View.GONE); // 初期状態では非表示

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());

        // アプリ起動時にキーを読み出す処理を開始
        loadApiKey();
    }

    /**
     * SettingsActivityから戻ってきた時にキーの再ロードを試みる
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 既にロードされていない、またはキーがクリアされている場合に再認証を試みる
        if (apiKey == null && preferencesHelper.hasEncryptedKey()) {
             loadApiKey();
        }
    }


    /**
     * APIキーを安全なストレージからロードし、必要に応じて生体認証を要求する
     */
    private void loadApiKey() {
        try {
            // 鍵が存在しない、または暗号化データがない場合は設定画面へ誘導
            if (!keyStoreHelper.isKeyExist() || !preferencesHelper.hasEncryptedKey()) {
                Log.i(TAG, "API Key not configured or missing data. Opening settings.");
                Toast.makeText(this, "APIキーを設定してください。", Toast.LENGTH_LONG).show();
                openSettings(); 
                return;
            }

            // Keystoreから鍵を取得し、復号化用のCipherを初期化する
            // 認証必須のため、このCipherはまだ使えない
            Cipher cipher = keyStoreHelper.getDecryptCipher();

            // 生体認証が必要な場合は認証を開始する
            showBiometricPrompt(cipher);

        } catch (Exception e) {
            Log.e(TAG, "Error loading API Key components: " + e.getMessage());
            Toast.makeText(this, "セキュリティ設定エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    try {
                        String encryptedKey = preferencesHelper.getEncryptedKey();
                        String ivString = preferencesHelper.getIv(); 

                        // 認証成功したCipherで復号化を実行
                        apiKey = keyStoreHelper.decryptData(encryptedKey, ivString, result.getCryptoObject().getCipher());

                        Log.d(TAG, "API Key successfully decrypted and loaded.");
                        Toast.makeText(MainActivity.this, "APIキーの認証に成功しました", Toast.LENGTH_SHORT).show();
                        generateRecipeButton.setEnabled(true);
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed: " + e.getMessage());
                        Toast.makeText(MainActivity.this, "キーの復号化に失敗しました", Toast.LENGTH_LONG).show();
                        generateRecipeButton.setEnabled(false);
                        apiKey = null;
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
                    generateRecipeButton.setEnabled(false);
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("セキュリティ認証")
            .setSubtitle("APIキーを使用するため、指紋またはPINが必要です。")
            .setNegativeButtonText("設定画面へ")
            .build();

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
     * レシピ生成処理を開始する (API通信ロジックを実装)
     */
    private void startRecipeGeneration() {
        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "APIキーがロードされていません。認証してください。", Toast.LENGTH_LONG).show();
            loadApiKey();
            return;
        }

        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, "食材を入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ★デモのため、難易度とジャンルはハードコードまたは仮のUIから取得した値を想定
        // 実際のアプリではSpinnerなどから取得する必要があります
        String difficulty = "難易度: 中級者"; // 仮の値
        String genre = "ジャンル: イタリアン"; // 仮の値
        
        // UI操作の準備
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        // APIクライアントの呼び出し
        apiClient.generateRecipe(apiKey, ingredients, difficulty, genre, new GeminiApiClient.RecipeCallback() {
            
            // onNewChunk はワンショット通信では全テキストを一度に受け取る
            @Override
            public void onNewChunk(String chunk) {
                // UIスレッドで実行される
                recipeOutputText.setText(chunk);
            }

            @Override
            public void onComplete() {
                // UIスレッドで実行される
                generateRecipeButton.setEnabled(true);
                loadingIndicator.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String error) {
                // UIスレッドで実行される
                generateRecipeButton.setEnabled(true);
                loadingIndicator.setVisibility(View.GONE);
                recipeOutputText.setText("エラーが発生しました:\n" + error);
                Toast.makeText(MainActivity.this, "API呼び出しに失敗: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }
}