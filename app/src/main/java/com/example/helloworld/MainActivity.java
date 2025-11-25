package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
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

    // UIコンポーネント (R.id.〇〇はactivity_main.xmlで定義されています)
    private EditText ingredientInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    private ImageButton cameraButton;
    // IDは元のR.id.progress_bar_loadingのまま保守
    private ProgressBar loadingIndicator;

    // レシピ設定用のSpinner
    private Spinner spinnerDifficulty;
    private Spinner spinnerGenre;
    private Spinner spinnerTime;
    private Spinner spinnerDiet;

    // APIキー関連
    private String apiKey = null; // 復号化されたAPIキーを保持
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 

        // ヘルパークラスとAPIクライアントの初期化
        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();

        // UIコンポーネントの初期化
        // IDは元のまま保守
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        // IDは元のまま保守
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        loadingIndicator.setVisibility(View.GONE);
        cameraButton = findViewById(R.id.button_camera);

        // Spinnerの初期化
        // IDは元のまま保守
        spinnerDifficulty = findViewById(R.id.spinner_difficulty);
        spinnerGenre = findViewById(R.id.spinner_genre);
        spinnerTime = findViewById(R.id.spinner_time);
        spinnerDiet = findViewById(R.id.spinner_diet);

        // Spinnerにアダプタを設定する
        loadSpinnerAdapters();

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        cameraButton.setOnClickListener(v -> showFeatureNotImplemented());

        // アプリ起動時にキーを読み出す処理を開始
        loadApiKey();
    }

    /**
     * SpinnerにArrayAdapterを設定し、res/values/arrays.xmlの項目をロードする
     * ArrayAdapterのレイアウトには標準の`simple_spinner_item`と`simple_spinner_dropdown_item`を使用
     */
    private void loadSpinnerAdapters() {
        // 難易度 - リソース名を R.array.difficulty_options に修正
        ArrayAdapter<CharSequence> difficultyAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.difficulty_options, 
                android.R.layout.simple_spinner_item);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDifficulty.setAdapter(difficultyAdapter);

        // ジャンル - リソース名を R.array.genre_options に修正
        ArrayAdapter<CharSequence> genreAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.genre_options, 
                android.R.layout.simple_spinner_item);
        genreAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGenre.setAdapter(genreAdapter);

        // 調理時間 - リソース名を R.array.time_options に修正
        ArrayAdapter<CharSequence> timeAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.time_options, 
                android.R.layout.simple_spinner_item);
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTime.setAdapter(timeAdapter);

        // 食事制限 - リソース名を R.array.dietary_options に修正
        ArrayAdapter<CharSequence> dietAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.dietary_options, 
                android.R.layout.simple_spinner_item);
        dietAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDiet.setAdapter(dietAdapter);
    }


    /**
     * カメラボタンの機能が未実装であることを示すトースト
     */
    private void showFeatureNotImplemented() {
        Toast.makeText(this, "カメラによる食材認識機能は開発中です。", Toast.LENGTH_SHORT).show();
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
            if (!keyStoreHelper.isKeyExist() || !preferencesHelper.hasEncryptedKey()) {
                Log.i(TAG, "API Key not configured or missing data. Opening settings.");
                Toast.makeText(this, "APIキーを設定してください。", Toast.LENGTH_LONG).show();
                openSettings(); 
                return;
            }

            Cipher cipher = keyStoreHelper.getDecryptCipher();
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
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    try {
                        String encryptedKey = preferencesHelper.getEncryptedKey();
                        String ivString = preferencesHelper.getIv(); 

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

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(MainActivity.this, "認証失敗", Toast.LENGTH_SHORT).show();
                    generateRecipeButton.setEnabled(false);
                }

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
        generateRecipeButton.setEnabled(false);
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

        // Spinnerから実際の選択値を取得する
        String difficulty = spinnerDifficulty.getSelectedItem().toString();
        String genre = spinnerGenre.getSelectedItem().toString();
        // 時間と制約をプロンプトに追加するために結合
        String timeConstraint = spinnerTime.getSelectedItem().toString();
        String dietConstraint = spinnerDiet.getSelectedItem().toString();

        // すべての制約を結合してAPIクライアントに渡す
        // 注意: APIに渡す前に、"難易度: 初心者 (簡単)" のようなSpinnerの表示テキストから、
        // "初心者 (簡単)" のような純粋な制約テキストを抽出するロジックを追加すると、
        // AIへのプロンプトがより明確になります。
        String allConstraints = String.format("難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s", 
            difficulty, genre, timeConstraint, dietConstraint);


        // UI操作の準備
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        // XMLに追加したIDを参照
        loadingIndicator.setVisibility(View.VISIBLE);

        // APIクライアントの呼び出し 
        apiClient.generateRecipe(apiKey, ingredients, difficulty, genre, allConstraints, new GeminiApiClient.RecipeCallback() {

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