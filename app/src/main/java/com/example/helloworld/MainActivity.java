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
    private EditText minPriceInput; // ★新規追加★
    private EditText maxPriceInput; // ★新規追加★
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    private ImageButton cameraButton;
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
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        minPriceInput = findViewById(R.id.edit_text_min_price); // ★新規追加★
        maxPriceInput = findViewById(R.id.edit_text_max_price); // ★新規追加★
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        loadingIndicator.setVisibility(View.GONE);
        cameraButton = findViewById(R.id.button_camera);

        // Spinnerの初期化
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
        // generateRecipeButtonを初期状態で無効化し、認証成功後に有効にする
        generateRecipeButton.setEnabled(false);
        loadApiKey();
    }

    /**
     * SpinnerにArrayAdapterを設定し、res/values/arrays.xmlの項目をロードする
     */
    private void loadSpinnerAdapters() {
        // 難易度
        ArrayAdapter<CharSequence> difficultyAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.difficulty_options, 
                android.R.layout.simple_spinner_item);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDifficulty.setAdapter(difficultyAdapter);

        // ジャンル
        ArrayAdapter<CharSequence> genreAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.genre_options, 
                android.R.layout.simple_spinner_item);
        genreAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGenre.setAdapter(genreAdapter);

        // 調理時間
        ArrayAdapter<CharSequence> timeAdapter = ArrayAdapter.createFromResource(
                this, 
                R.array.time_options, 
                android.R.layout.simple_spinner_item);
        timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTime.setAdapter(timeAdapter);

        // 食事制限
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
        } else if (preferencesHelper.hasEncryptedKey() && apiKey != null) {
             // キーが保存されており、既にロードされている場合（設定画面から戻ったが、キーはそのまま）
             generateRecipeButton.setEnabled(true);
        } else if (!preferencesHelper.hasEncryptedKey()) {
             // キーデータがPreferencesから消えている場合（設定画面でキーがクリアされたなど）
             apiKey = null;
             generateRecipeButton.setEnabled(false);
             Toast.makeText(this, "APIキーを設定してください。", Toast.LENGTH_LONG).show();
        }
    }


    /**
     * APIキーを安全なストレージからロードし、必要に応じて生体認証を要求する
     */
    private void loadApiKey() {
        try {
            // 1. キーが存在しない場合は設定画面へ誘導
            if (!keyStoreHelper.isKeyExist() || !preferencesHelper.hasEncryptedKey()) {
                Log.i(TAG, "API Key not configured or missing data. Opening settings.");
                Toast.makeText(this, "APIキーを設定してください。", Toast.LENGTH_LONG).show();
                openSettings(); 
                return;
            }

            // 2. 復号化に必要なCipherをKeystoreから取得
            Cipher cipher = keyStoreHelper.getDecryptCipher();
            showBiometricPrompt(cipher);

        } catch (Exception e) {
            Log.e(TAG, "Error loading API Key components: " + e.getMessage());
            // ループ対策として、エラーが発生した場合に設定画面へ誘導する処理を削除し、
            // 認証ボタンを無効化するのみにする。
            Toast.makeText(this, "セキュリティ設定エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            generateRecipeButton.setEnabled(false);
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

                        // 認証済みのCipherをdecryptDataに渡す
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        apiKey = keyStoreHelper.decryptData(encryptedKey, ivString, authenticatedCipher);

                        Log.d(TAG, "API Key successfully decrypted and loaded.");
                        Toast.makeText(MainActivity.this, "APIキーの認証に成功しました", Toast.LENGTH_SHORT).show();
                        generateRecipeButton.setEnabled(true);
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed: " + e.getMessage());
                        // 復号化失敗時: キーはクリアせず、無効化してユーザーに再認証を促す
                        Toast.makeText(MainActivity.this, "キーの復号化に失敗しました。原因: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    // 認証エラー時: ループ対策として設定画面に戻る処理は行わない
                    Toast.makeText(MainActivity.this, "認証エラー: " + errString, Toast.LENGTH_LONG).show();
                    generateRecipeButton.setEnabled(false);
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("セキュリティ認証")
            .setSubtitle("APIキーを使用するため、指紋またはPINが必要です。")
            .setNegativeButtonText("キャンセル") 
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
            Toast.makeText(this, "APIキーがロードされていません。認証が必要です。", Toast.LENGTH_LONG).show();
            // 再度認証プロセスを開始
            loadApiKey();
            return;
        }
        
        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, "食材を入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // ★新規追加★: 価格帯の取得
        String minPrice = minPriceInput.getText().toString().trim();
        String maxPrice = maxPriceInput.getText().toString().trim();
        String priceConstraint = "";
        
        if (!minPrice.isEmpty() || !maxPrice.isEmpty()) {
            // 入力が不正な場合のバリデーション
            try {
                int min = minPrice.isEmpty() ? 0 : Integer.parseInt(minPrice);
                // maxPriceが空の場合は「制限なし」として扱う
                if (maxPrice.isEmpty()) {
                     priceConstraint = String.format("価格帯: %d円〜制限なし", min);
                } else {
                     int max = Integer.parseInt(maxPrice);
                     if (min > max) {
                         Toast.makeText(this, "最低価格が最高価格を超えています。", Toast.LENGTH_LONG).show();
                         return; // 実行を中断
                     }
                     priceConstraint = String.format("価格帯: %d円〜%d円", min, max);
                }
            } catch (NumberFormatException e) {
                 Toast.makeText(this, "価格帯には有効な数値を入力してください。", Toast.LENGTH_LONG).show();
                 return; // 実行を中断
            }
        }


        // Spinnerから実際の選択値を取得する
        String difficulty = spinnerDifficulty.getSelectedItem().toString();
        String genre = spinnerGenre.getSelectedItem().toString();
        String timeConstraint = spinnerTime.getSelectedItem().toString();
        String dietConstraint = spinnerDiet.getSelectedItem().toString();

        // すべての制約を結合
        StringBuilder allConstraintsBuilder = new StringBuilder();
        allConstraintsBuilder.append(String.format("難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s", 
            difficulty, genre, timeConstraint, dietConstraint));
            
        // ★新規追加★: 価格制約を追加
        if (!priceConstraint.isEmpty()) {
             allConstraintsBuilder.append(", ").append(priceConstraint);
        }

        String allConstraints = allConstraintsBuilder.toString();


        // UI操作の準備
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
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