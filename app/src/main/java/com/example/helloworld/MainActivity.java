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

import com.example.liefantidia2.PreferencesHelper.EncryptedData;

import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント (R.id.〇〇はactivity_main.xmlで定義されています)
    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
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
    private PreferencesHelper preferencesHelper; // PreferencesHelperを追加

    // APIクライアント (このクラスは提供されていませんが、呼び出しはそのまま残します)
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ヘルパークラスとAPIクライアントの初期化
        keyStoreHelper = new KeyStoreHelper(this);
        preferencesHelper = new PreferencesHelper(this); // PreferencesHelperの初期化
        // GeminiApiClientのインスタンス生成 (このクラスが提供されていると仮定)
        apiClient = new GeminiApiClient(); 

        // UIコンポーネントの初期化
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        minPriceInput = findViewById(R.id.edit_text_min_price);
        maxPriceInput = findViewById(R.id.edit_text_max_price);
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

        // 初期状態ではボタンを無効化
        generateRecipeButton.setEnabled(false);
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
     * Activityがフォアグラウンドに戻ってきた時にキーの再ロードを試みる
     * これにより、SettingsActivityから戻った時や、アプリが再開した時に認証チェックが入る
     */
    @Override
    protected void onResume() {
        super.onResume();
        // 1. KeyStoreのキーエイリアスが存在し、かつSharedPreferencesに暗号化データがあるか確認
        boolean keyAliasExist = keyStoreHelper.isKeyAliasExist();
        boolean encryptedDataExist = preferencesHelper.hasEncryptedKey();

        if (apiKey == null && keyAliasExist && encryptedDataExist) {
             // キーデータが存在するが認証されていない場合 -> 認証プロセスへ
             loadApiKey();
        } else if (!keyAliasExist || !encryptedDataExist) {
             // キーエイリアスまたは暗号化データが存在しない場合 -> 設定画面へ誘導
             apiKey = null;
             generateRecipeButton.setEnabled(false);
             recipeOutputText.setText("APIキーが設定されていません。設定画面から設定してください。");
        } else if (apiKey != null) {
             // 既にキーがロードされ、認証済みの場合
             generateRecipeButton.setEnabled(true);
             recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！食材を入力してレシピを生成しましょう。"); // 仮のメッセージ
        }
    }


    /**
     * APIキーを安全なストレージからロードし、必要に応じて生体認証を要求する
     */
    private void loadApiKey() {
        // 認証中に複数回loadApiKeyが呼ばれるのを防ぐために、まずボタンを無効化
        generateRecipeButton.setEnabled(false);

        try {
            // 1. 復号化に必要なCipherをKeystoreから取得 (BiometricPromptのCryptoObjectに渡す準備)
            Cipher cipher = keyStoreHelper.getDecryptCipher();
            showBiometricPrompt(cipher);

        } catch (Exception e) {
            Log.e(TAG, "Error loading API Key components: " + e.getMessage());
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
                        EncryptedData encryptedData = preferencesHelper.getEncryptedData();
                        
                        if (encryptedData == null) {
                             throw new Exception("Encrypted data not found.");
                        }

                        // 認証済みのCipherをdecryptDataに渡す
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        apiKey = keyStoreHelper.decryptData(encryptedData, authenticatedCipher);

                        Log.d(TAG, "API Key successfully decrypted and loaded.");
                        Toast.makeText(MainActivity.this, "APIキーの認証に成功しました", Toast.LENGTH_SHORT).show();
                        generateRecipeButton.setEnabled(true);
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed: " + e.getMessage());
                        // 復号化失敗時
                        Toast.makeText(MainActivity.this, "キーの復号化に失敗しました。再認証してください。", Toast.LENGTH_LONG).show();
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
            .setNegativeButtonText("キャンセル")
            .setAllowedAuthenticators(BiometricProperties.REQUIRED_AUTHENTICATORS) // BiometricPropertiesを追加
            .build();

        // BiometricPromptにCipherをCryptoObjectとして渡す
        biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
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
            Toast.makeText(this, "APIキーがロードされていません。生体認証が必要です。", Toast.LENGTH_LONG).show();
            // 再度認証プロセスを開始
            loadApiKey();
            return;
        }

        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, "食材を入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        // 価格帯のバリデーションと制約文字列の構築
        String minPriceStr = minPriceInput.getText().toString().trim();
        String maxPriceStr = maxPriceInput.getText().toString().trim();
        String priceConstraint = "";

        if (!minPriceStr.isEmpty() || !maxPriceStr.isEmpty()) {
            try {
                int min = minPriceStr.isEmpty() ? 0 : Integer.parseInt(minPriceStr);
                int max;

                if (maxPriceStr.isEmpty()) {
                     // 最高価格が空の場合は、INTの最大値として扱う（プロンプトでは「制限なし」）
                     max = Integer.MAX_VALUE;
                } else {
                     max = Integer.parseInt(maxPriceStr);
                }

                if (min > max) {
                    Toast.makeText(this, "最低価格が最高価格を超えています。", Toast.LENGTH_LONG).show();
                    return; // 実行を中断
                }

                // プロンプト用の価格制約文字列を構築
                String maxDisplay = (max == Integer.MAX_VALUE) ? "制限なし" : max + "円";
                priceConstraint = String.format("価格帯: %d円〜%s", min, maxDisplay);

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

        if (!priceConstraint.isEmpty()) {
             allConstraintsBuilder.append(", ").append(priceConstraint);
        }

        String allConstraints = allConstraintsBuilder.toString();


        // UI操作の準備
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        // APIクライアントの呼び出し (このクラスのコールバックはUIスレッドで実行されると仮定)
        apiClient.generateRecipe(apiKey, ingredients, difficulty, genre, allConstraints, new GeminiApiClient.RecipeCallback() {

            @Override
            public void onResult(String result) {
                // UIスレッドで実行される
                recipeOutputText.setText(result);
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