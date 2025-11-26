package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton; // ImageButtonを使用
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthCredentialProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント
    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    private ImageButton historyButton; // 👈 履歴ボタン
    private Button cameraButton;
    private ProgressBar loadingIndicator;

    // Spinner and Optionals
    private Spinner spinnerDifficulty;
    private Spinner spinnerGenre;
    private Spinner spinnerTime;
    private Spinner spinnerDiet;
    private CheckBox useAllIngredientsCheckbox;
    private Spinner spinnerType;
    private EditText editOptionalDifficulty;
    private EditText editOptionalGenre;
    private EditText editOptionalTime;
    private EditText editOptionalDiet;
    private EditText editOptionalType;
    private EditText editInstructions;

    // APIキー関連
    private String apiKey = null;
    private PreferencesHelper preferencesHelper;
    private GeminiApiClient apiClient;
    
    // Firebase Auth/DB
    private FirebaseAuth auth;
    private HistoryManager historyManager; // 履歴管理クラス
    private AtomicBoolean isAuthInitialized = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Firebase初期化 (既にどこかで実行されているはずだが、念のため)
        try {
            FirebaseApp.initializeApp(this);
            auth = FirebaseAuth.getInstance();
        } catch (IllegalStateException e) {
             Log.w(TAG, "FirebaseApp already initialized.");
             auth = FirebaseAuth.getInstance();
        }

        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();

        // 認証処理を開始
        initializeFirebaseAuth();

        // UI初期化
        initializeUI();
        loadSpinnerAdapters();

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        cameraButton.setOnClickListener(v -> showFeatureNotImplemented());
        historyButton.setOnClickListener(v -> openHistory()); // 👈 履歴ボタンのリスナー

        generateRecipeButton.setEnabled(false);
    }
    
    private void initializeFirebaseAuth() {
        // NOTE: Canvas環境では __initial_auth_token が渡されることを想定
        // しかし、Androidではそのトークンは利用できないため、匿名認証にフォールバックします。
        
        // 1. 認証状態の変化を監視
        auth.addAuthStateListener(firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {
                Log.i(TAG, "User authenticated: " + user.getUid());
                isAuthInitialized.set(true);
                historyManager = new HistoryManager(this); // 認証後に初期化
                // APIキーのチェックを再実行してボタンを有効にする
                checkAndLoadApiKey(); 
            } else {
                 Log.w(TAG, "User not authenticated, starting anonymous sign-in...");
                 // 2. 認証されていない場合、匿名認証を実行
                 auth.signInAnonymously().addOnCompleteListener(this, task -> {
                     if (task.isSuccessful()) {
                         Log.d(TAG, "signInAnonymously:success");
                         // onAuthStateChangedが呼ばれ、isAuthInitializedがtrueになる
                     } else {
                         Log.e(TAG, "signInAnonymously:failure", task.getException());
                         Toast.makeText(MainActivity.this, "匿名認証に失敗しました。", Toast.LENGTH_LONG).show();
                         isAuthInitialized.set(true); // 失敗してもUI処理を進めるためtrueに
                     }
                 });
            }
        });
    }

    private void initializeUI() {
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        minPriceInput = findViewById(R.id.edit_text_min_price);
        maxPriceInput = findViewById(R.id.edit_text_max_price);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        loadingIndicator.setVisibility(View.GONE);
        cameraButton = findViewById(R.id.button_camera);
        
        // 👈 【履歴ボタンの初期化】
        historyButton = findViewById(R.id.button_history);

        spinnerDifficulty = findViewById(R.id.spinner_difficulty);
        spinnerGenre = findViewById(R.id.spinner_genre);
        spinnerTime = findViewById(R.id.spinner_time);
        spinnerDiet = findViewById(R.id.spinner_diet);

        useAllIngredientsCheckbox = findViewById(R.id.checkbox_use_all_ingredients);
        spinnerType = findViewById(R.id.spinner_type);

        editOptionalDifficulty = findViewById(R.id.edit_optional_difficulty);
        editOptionalGenre = findViewById(R.id.edit_optional_genre);
        editOptionalTime = findViewById(R.id.edit_optional_time);
        editOptionalDiet = findViewById(R.id.edit_optional_diet);
        editOptionalType = findViewById(R.id.edit_optional_type);

        editInstructions = findViewById(R.id.edit_instructions);
    }

    private void loadSpinnerAdapters() {
        // XMLで定義された配列リソースを使用
        int[] arrayIds = {R.array.difficulty_options, R.array.genre_options, R.array.time_options, R.array.dietary_options, R.array.type_options};
        Spinner[] spinners = {spinnerDifficulty, spinnerGenre, spinnerTime, spinnerDiet, spinnerType};

        for (int i = 0; i < arrayIds.length; i++) {
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    this,
                    arrayIds[i],
                    android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinners[i].setAdapter(adapter);
        }
    }

    private void showFeatureNotImplemented() {
        Toast.makeText(this, "カメラによる食材認識機能は開発中です。", Toast.LENGTH_SHORT).show();
    }
    
    private void openHistory() {
        if (!isAuthInitialized.get() || auth.getCurrentUser() == null) {
            Toast.makeText(this, "認証処理中です。しばらくお待ちください。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, HistoryActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndLoadApiKey();
        
        // HistoryActivityから戻ってきた際のIntent処理
        handleHistoryIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // 新しいIntentをセット
        handleHistoryIntent(intent);
    }

    /**
     * HistoryActivityから戻ってきたIntentを処理し、UIを更新する
     */
    private void handleHistoryIntent(Intent intent) {
        RecipeHistory item = (RecipeHistory) intent.getSerializableExtra("RECIPE_HISTORY_ITEM");
        if (item != null) {
            // レシピ本文をセット
            recipeOutputText.setText(item.getRecipeContent());
            
            // 入力情報をUIに反映 (完全に復元するのは複雑なので、一旦食材とメッセージのみ)
            ingredientInput.setText(item.getIngredientsWithUsage().split(" \\(")[0]);
            
            Toast.makeText(this, "履歴からレシピ「" + item.getRecipeTitle() + "」を再表示しました。", Toast.LENGTH_LONG).show();
            
            // Intentからデータを削除して、次回Resume/NewIntentで再度読み込まれないようにする
            intent.removeExtra("RECIPE_HISTORY_ITEM");
        }
    }


    private void checkAndLoadApiKey() {
        if (!isAuthInitialized.get()) {
            // 認証が完了するまで待機
            return;
        }
        
        String loadedKey = preferencesHelper.getPlainKey();

        if (loadedKey != null && !loadedKey.isEmpty()) {
             apiKey = loadedKey;
             generateRecipeButton.setEnabled(true);
             if (recipeOutputText.getText().toString().contains("AIが考案中です")) {
                 // ロード中にボタンが無効になっていた場合、メッセージを初期化
                 recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！食材を入力してレシピを生成しましょう。");
             }
        } else if (preferencesHelper.hasEncryptedKey()) {
             // 旧版の暗号化キーが残っている場合、削除して警告
             preferencesHelper.deleteAllKeys();
             apiKey = null;
             generateRecipeButton.setEnabled(false);
             recipeOutputText.setText("APIキーが設定されていません。設定画面から設定してください。(旧キーデータは削除されました)");
        } else {
             // キーデータが存在しない場合
             apiKey = null;
             generateRecipeButton.setEnabled(false);
             recipeOutputText.setText("APIキーが設定されていません。設定画面から設定してください。");
        }
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void startRecipeGeneration() {
        if (apiKey == null || apiKey.isEmpty()) {
             Toast.makeText(this, "APIキーが設定されていません。設定画面から設定してください。", Toast.LENGTH_LONG).show();
             return;
        }
        
        if (!isAuthInitialized.get() || auth.getCurrentUser() == null) {
            Toast.makeText(this, "認証処理中です。しばらくお待ちください。", Toast.LENGTH_SHORT).show();
            return;
        }

        continueRecipeGeneration();
    }

    private void continueRecipeGeneration() {
        // --- 1. 入力値の取得とバリデーション ---
        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, R.string.toast_input_ingredients, Toast.LENGTH_SHORT).show();
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
                     max = Integer.MAX_VALUE;
                } else {
                     max = Integer.parseInt(maxPriceStr);
                }

                if (min > max) {
                    Toast.makeText(this, R.string.error_invalid_price_range, Toast.LENGTH_LONG).show();
                    return;
                }
                String maxDisplay = (max == Integer.MAX_VALUE) ? "制限なし" : max + "円";
                priceConstraint = String.format("価格帯: %d円〜%s", min, maxDisplay);

            } catch (NumberFormatException e) {
                 Toast.makeText(this, R.string.error_invalid_price_format, Toast.LENGTH_LONG).show();
                 return;
            }
        }

        // --- 2. 制約の結合とプロンプトの構築 ---

        boolean mustUseAll = useAllIngredientsCheckbox.isChecked();
        String ingredientUsage = mustUseAll ? " (入力された具材は全て使用してください)" : " (入力された具材は、全て使用しなくても構いません)";
        final String ingredientsWithUsage = ingredients + ingredientUsage;

        String difficulty = combineConstraint(spinnerDifficulty.getSelectedItem().toString(), editOptionalDifficulty.getText().toString());
        String genre = combineConstraint(spinnerGenre.getSelectedItem().toString(), editOptionalGenre.getText().toString());
        String timeConstraint = combineConstraint(spinnerTime.getSelectedItem().toString(), editOptionalTime.getText().toString());
        String dietConstraint = combineConstraint(spinnerDiet.getSelectedItem().toString(), editOptionalDiet.getText().toString());
        String typeConstraint = combineConstraint(spinnerType.getSelectedItem().toString(), editOptionalType.getText().toString());

        StringBuilder allConstraintsBuilder = new StringBuilder();

        allConstraintsBuilder.append(String.format("主食の分類: %s, 難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s",
            typeConstraint, difficulty, genre, timeConstraint, dietConstraint));

        if (!priceConstraint.isEmpty()) {
             allConstraintsBuilder.append(", ").append(priceConstraint);
        }

        String instructions = editInstructions.getText().toString().trim();
        if (!instructions.isEmpty()) {
             allConstraintsBuilder.append(". 【最重要指示】: ").append(instructions);
        }

        final String allConstraints = allConstraintsBuilder.toString();

        // --- 3. APIクライアントの呼び出し ---
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        apiClient.generateRecipe(apiKey, ingredientsWithUsage, allConstraints, new GeminiApiClient.RecipeCallback() {

            @Override
            public void onResult(String result) {
                runOnUiThread(() -> recipeOutputText.setText(result));
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    generateRecipeButton.setEnabled(true);
                    loadingIndicator.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
                    
                    // 👈 【履歴の保存】
                    String generatedRecipe = recipeOutputText.getText().toString();
                    if (!generatedRecipe.contains("エラー") && historyManager != null) {
                         historyManager.saveRecipe(ingredientsWithUsage, allConstraints, generatedRecipe);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    generateRecipeButton.setEnabled(true);
                    loadingIndicator.setVisibility(View.GONE);
                    recipeOutputText.setText("エラーが発生しました:\n" + error);
                    Toast.makeText(MainActivity.this, "API呼び出しに失敗: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String combineConstraint(String spinnerSelection, String optionalInput) {
        String input = optionalInput.trim();
        if (input.isEmpty()) {
            return spinnerSelection;
        }
        if (spinnerSelection.contains("選択なし") || spinnerSelection.contains("特になし")) {
            return input;
        }
        return spinnerSelection + " (" + input + "による詳細)";
    }
}