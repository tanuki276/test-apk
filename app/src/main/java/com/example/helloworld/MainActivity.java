package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント
    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    // 【修正点】XMLに合わせてImageButtonからButtonに変更
    private Button cameraButton; 
    private ProgressBar loadingIndicator;

    // レシピ設定用のSpinner
    private Spinner spinnerDifficulty;
    private Spinner spinnerGenre;
    private Spinner spinnerTime;
    private Spinner spinnerDiet;

    // APIキー関連
    private String apiKey = null;
    private KeyStoreHelper keyStoreHelper;
    private PreferencesHelper preferencesHelper;
    private boolean isBiometricPromptShowing = false;

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ヘルパークラスとAPIクライアントの初期化
        try {
            // KeyStoreHelperの初期化はクラッシュの原因になり得るため、try-catch内で確実に処理する
            keyStoreHelper = new KeyStoreHelper(this);
        } catch (RuntimeException e) {
            Log.e(TAG, "KeyStoreHelper initialization failed (potentially ANR risk): " + e.getMessage());
            // エラーが発生した場合、keyStoreHelperはnullのままにしておく
            keyStoreHelper = null; 
            Toast.makeText(this, "セキュリティシステム初期化エラー。APIキー機能は利用できません。", Toast.LENGTH_LONG).show();
        }
        
        preferencesHelper = new PreferencesHelper(this);
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
        // 【修正点】Buttonとして取得
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
        // レシピ生成ボタンクリックで、認証プロセスを開始
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        cameraButton.setOnClickListener(v -> showFeatureNotImplemented());

        // 初期状態ではボタンを無効化 (checkAndLoadApiKeyで状態を決定)
        generateRecipeButton.setEnabled(false);
    }

    /**
     * SpinnerにArrayAdapterを設定
     */
    private void loadSpinnerAdapters() {
        // XMLで定義された配列リソースを使用
        ArrayAdapter<CharSequence> adapterBase = ArrayAdapter.createFromResource(
                this, 
                R.array.difficulty_options, 
                android.R.layout.simple_spinner_item);
        adapterBase.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 難易度
        spinnerDifficulty.setAdapter(adapterBase);
        // ジャンル (同じアダプタベースを使用)
        spinnerGenre.setAdapter(ArrayAdapter.createFromResource(this, R.array.genre_options, android.R.layout.simple_spinner_item));
        // 調理時間
        spinnerTime.setAdapter(ArrayAdapter.createFromResource(this, R.array.time_options, android.R.layout.simple_spinner_item));
        // 食事制限
        spinnerDiet.setAdapter(ArrayAdapter.createFromResource(this, R.array.dietary_options, android.R.layout.simple_spinner_item));

        // ドロップダウンビューリソースの設定 (すべてのSpinnerに適用)
        for (Spinner spinner : new Spinner[]{spinnerDifficulty, spinnerGenre, spinnerTime, spinnerDiet}) {
            ArrayAdapter<?> adapter = (ArrayAdapter<?>) spinner.getAdapter();
            if (adapter != null) {
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            }
        }
    }

    private void showFeatureNotImplemented() {
        Toast.makeText(this, "カメラによる食材認識機能は開発中です。", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 【重要修正】onResumeでBiometricPromptを自動表示するのをやめる。
        // 代わりにキーの状態チェックのみを行い、UIを更新する。
        checkAndLoadApiKey();
    }

    /**
     * APIキーの存在チェックとUI表示の更新。
     * 認証プロセスは、ユーザーがレシピ生成ボタンを押したときにのみ開始されます。
     */
    private void checkAndLoadApiKey() {
        if (keyStoreHelper == null) {
            // 初期化に失敗している場合
            recipeOutputText.setText("セキュリティシステムの初期化エラーが発生しています。APIキーの保存/利用はできません。");
            generateRecipeButton.setEnabled(false);
            return;
        }

        boolean keyAliasExist = keyStoreHelper.isKeyAliasExist();
        boolean encryptedDataExist = preferencesHelper.hasEncryptedKey();

        if (apiKey != null) {
             // 既にキーがロードされ、認証済みの場合
             generateRecipeButton.setEnabled(true);
             recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！食材を入力してレシピを生成しましょう。");
        } else if (keyAliasExist && encryptedDataExist) {
             // キーデータが存在するが認証されていない場合 -> ユーザーアクションを待つ
             generateRecipeButton.setEnabled(true); // ユーザーアクションで認証開始可能
             recipeOutputText.setText("APIキーが保存されています。レシピ生成ボタンを押して生体認証を開始してください。");
        } else {
             // キーエイリアスまたは暗号化データが存在しない場合 -> 設定画面へ誘導
             apiKey = null;
             generateRecipeButton.setEnabled(false);
             recipeOutputText.setText("APIキーが設定されていません。設定画面から設定してください。");
        }
    }

    /**
     * APIキーを安全なストレージからロードし、必要に応じて生体認証を要求する
     */
    private void loadApiKey() {
        // BiometricPromptが既に表示されている場合は何もしない
        if (isBiometricPromptShowing) return;

        isBiometricPromptShowing = true;
        generateRecipeButton.setEnabled(false);
        recipeOutputText.setText("生体認証を待機しています...");

        try {
            EncryptedData encryptedData = preferencesHelper.getEncryptedData();
            if (encryptedData == null) {
                // データが失われている場合
                handleKeyInvalidated();
                return;
            }
            
            // IVを渡してCipherを初期化
            Cipher cipher = keyStoreHelper.getDecryptCipher(encryptedData.getIv());
            
            showBiometricPrompt(cipher, encryptedData);

        } catch (KeyPermanentlyInvalidatedException e) {
             // キー無効化時の処理
            handleKeyInvalidated();
            isBiometricPromptShowing = false;
        } catch (Exception e) {
            Log.e(TAG, "Error loading API Key components or Cipher: " + e.getMessage());
            Toast.makeText(this, "認証準備エラー: " + e.getMessage(), Toast.LENGTH_LONG).show();
            generateRecipeButton.setEnabled(true); 
            isBiometricPromptShowing = false;
            checkAndLoadApiKey();
        }
    }

    /**
     * BiometricPrompt (生体認証) を表示する
     */
    private void showBiometricPrompt(Cipher cipher, EncryptedData encryptedData) {
        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt biometricPrompt = new BiometricPrompt(
            this,
            executor,
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    isBiometricPromptShowing = false;
                    try {
                        Cipher authenticatedCipher = result.getCryptoObject().getCipher();
                        apiKey = keyStoreHelper.decryptData(encryptedData, authenticatedCipher);

                        Log.d(TAG, "API Key successfully decrypted and loaded.");
                        Toast.makeText(MainActivity.this, "APIキーの認証に成功しました", Toast.LENGTH_SHORT).show();
                        
                        // 認証成功後、レシピ生成を続行する
                        continueRecipeGeneration();

                    } catch (KeyPermanentlyInvalidatedException e) {
                        handleKeyInvalidated();
                    } catch (Exception e) {
                        Log.e(TAG, "Decryption failed: " + e.getMessage());
                        Toast.makeText(MainActivity.this, "キーの復号化に失敗しました。設定を確認してください。", Toast.LENGTH_LONG).show();
                        apiKey = null;
                        generateRecipeButton.setEnabled(true);
                        checkAndLoadApiKey(); 
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    isBiometricPromptShowing = false;
                    Toast.makeText(MainActivity.this, "認証失敗", Toast.LENGTH_SHORT).show();
                    generateRecipeButton.setEnabled(true);
                    checkAndLoadApiKey(); 
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    isBiometricPromptShowing = false;
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        Toast.makeText(MainActivity.this, "認証エラー: " + errString, Toast.LENGTH_LONG).show();
                    }
                    generateRecipeButton.setEnabled(true);
                    checkAndLoadApiKey(); 
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("セキュリティ認証")
            .setSubtitle("APIキーを使用するため、指紋またはPINが必要です。")
            .setNegativeButtonText("キャンセル")
            .setAllowedAuthenticators(BiometricProperties.REQUIRED_AUTHENTICATORS)
            .build();

        biometricPrompt.authenticate(promptInfo, new BiometricPrompt.CryptoObject(cipher));
    }
    
    private void handleKeyInvalidated() {
        Log.e(TAG, "Key permanently invalidated or data corrupted.");
        Toast.makeText(getApplicationContext(), "セキュリティキーが無効化されました。設定画面からキーを再入力してください。", Toast.LENGTH_LONG).show();
        // 鍵とデータをクリーンアップ
        if (keyStoreHelper != null) {
            keyStoreHelper.deleteKeyAlias();
        }
        preferencesHelper.deleteEncryptedKey();
        apiKey = null;
        // UIを更新して設定を促す
        checkAndLoadApiKey();
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    // レシピ生成の開始点 (ここで認証が必要か判断する)
    private void startRecipeGeneration() {
        // 1. キーがロードされていないが、キーデータが保存されている場合
        if (apiKey == null && preferencesHelper.hasEncryptedKey()) {
             loadApiKey(); // 生体認証を開始する
             return;
        } 
        
        // 2. キーデータがそもそも保存されていない場合 
        if (apiKey == null || apiKey.isEmpty()) {
             Toast.makeText(this, "APIキーが設定されていません。設定画面から設定してください。", Toast.LENGTH_LONG).show();
             return;
        }

        // 3. キーがロードされている場合、または認証が完了した場合にレシピ生成を続行する
        continueRecipeGeneration();
    }
    
    /**
     * APIキーがロードされた後にレシピ生成を実行する
     */
    private void continueRecipeGeneration() {
        // UIの状態をリセット
        isBiometricPromptShowing = false;
        
        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, "食材を入力してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // --- ここから既存のレシピ生成ロジック ---

        // 価格帯のバリデーションと制約文字列の構築
        String minPriceStr = minPriceInput.getText().toString().trim();
        String maxPriceStr = maxPriceInput.getText().toString().trim();
        String priceConstraint = "";

        if (!minPriceStr.isEmpty() || !maxPriceStr.isEmpty()) {
            try {
                int min = minPriceStr.isEmpty() ? 0 : Integer.parseInt(minPriceStr);
                int max;

                if (maxPriceStr.isEmpty()) {
                     max = Integer.MAX_VALUE;
                } else {
                     max = Integer.parseInt(maxPriceStr);
                }

                if (min > max) {
                    Toast.makeText(this, "最低価格が最高価格を超えています。", Toast.LENGTH_LONG).show();
                    return;
                }
                String maxDisplay = (max == Integer.MAX_VALUE) ? "制限なし" : max + "円";
                priceConstraint = String.format("価格帯: %d円〜%s", min, maxDisplay);

            } catch (NumberFormatException e) {
                 Toast.makeText(this, "価格帯には有効な数値を入力してください。", Toast.LENGTH_LONG).show();
                 return;
            }
        }

        // Spinnerから実際の選択値を取得
        String difficulty = spinnerDifficulty.getSelectedItem().toString();
        String genre = spinnerGenre.getSelectedItem().toString();
        String timeConstraint = spinnerTime.getSelectedItem().toString();
        String dietConstraint = spinnerDiet.getSelectedItem().toString();

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

        // APIクライアントの呼び出し
        apiClient.generateRecipe(apiKey, ingredients, difficulty, genre, allConstraints, new GeminiApiClient.RecipeCallback() {

            @Override
            public void onResult(String result) {
                recipeOutputText.setText(result);
            }

            @Override
            public void onComplete() {
                generateRecipeButton.setEnabled(true);
                loadingIndicator.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String error) {
                generateRecipeButton.setEnabled(true);
                loadingIndicator.setVisibility(View.GONE);
                recipeOutputText.setText("エラーが発生しました:\n" + error);
                Toast.makeText(MainActivity.this, "API呼び出しに失敗: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // 内部クラスとして定義
    private static class BiometricProperties {
        public static final int REQUIRED_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    }
}