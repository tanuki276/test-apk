package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton; // ImageButtonは不要になったが、他の場所で使われる可能性を考慮して残す
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.FirebaseApp;
// ... 他のFirebase import

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
    
    // 【修正点】XMLに合わせてImageButtonからButtonに変更
    private Button historyButton; 
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
    private HistoryManager historyManager; 
    private AtomicBoolean isAuthInitialized = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "onCreate: Activity started.");
        
        // 1. レイアウトを設定
        setContentView(R.layout.activity_main);

        // 2. UI初期化（最優先）とアダプターのロード
        initializeUI();
        loadSpinnerAdapters();

        // 3. イベントリスナーの設定 (null チェックを付けて防御的に)
        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());
        if (generateRecipeButton != null) generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        if (cameraButton != null) cameraButton.setOnClickListener(v -> showFeatureNotImplemented());
        if (historyButton != null) historyButton.setOnClickListener(v -> openHistory()); 

        // 4. Firebase初期化と認証処理を開始
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                 FirebaseApp.initializeApp(this);
                 Log.d(TAG, "FirebaseApp initialized.");
            }
            auth = FirebaseAuth.getInstance();
        } catch (Exception e) {
             Log.e(TAG, "FATAL: Firebase initialization failed.", e);
        }

        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();
        
        // 認証処理を開始
        initializeFirebaseAuth();
        
        // 初期状態では無効化
        if (generateRecipeButton != null) {
            generateRecipeButton.setEnabled(false);
        }
        
        Log.d(TAG, "onCreate: Activity setup complete.");
    }

    private void initializeFirebaseAuth() {
        // 1. 認証状態の変化を監視
        auth.addAuthStateListener(firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {
                Log.i(TAG, "User authenticated: " + user.getUid());
                isAuthInitialized.set(true);
                // 認証後に HistoryManager を初期化 (二重初期化防止のチェックを追加)
                if (historyManager == null) {
                    // 認証後のみHistoryManagerが正しく機能するようにする
                    try {
                         historyManager = new HistoryManager(this); 
                         Log.d(TAG, "HistoryManager initialized after auth.");
                    } catch (Exception e) {
                         Log.e(TAG, "HistoryManager initialization failed.", e);
                    }
                }
                // APIキーのチェックを再実行してボタンを有効にする
                checkAndLoadApiKey(); 
            } else {
                 Log.w(TAG, "User not authenticated, starting anonymous sign-in...");
                 // 2. 認証されていない場合、匿名認証を実行
                 auth.signInAnonymously().addOnCompleteListener(this, task -> {
                     if (task.isSuccessful()) {
                         Log.d(TAG, "signInAnonymously:success");
                         // onAuthStateChangedが呼ばれるため、isAuthInitializedのセットはそちらに任せる
                     } else {
                         Log.e(TAG, "signInAnonymously:failure", task.getException());
                         // 匿名認証に失敗した場合でも、UIクラッシュを防ぐため isAuthInitialized を true に
                         isAuthInitialized.set(true); 
                         Toast.makeText(MainActivity.this, "匿名認証に失敗しました。履歴機能は利用できません。", Toast.LENGTH_LONG).show();
                         checkAndLoadApiKey(); // 認証失敗でもAPIキーはチェックする
                     }
                 });
            }
        });
    }


    private void initializeUI() {
        // すべてのUIコンポーネントを取得
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        minPriceInput = findViewById(R.id.edit_text_min_price);
        maxPriceInput = findViewById(R.id.edit_text_max_price);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        
        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
        
        // 【修正点】XMLに合わせてfindViewById後の型をButtonに統一
        cameraButton = findViewById(R.id.button_camera);
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
        
        Log.d(TAG, "initializeUI: UI components initialization complete.");
    }

    private void loadSpinnerAdapters() {
        // XMLで定義された配列リソースを使用
        int[] arrayIds = {R.array.difficulty_options, R.array.genre_options, R.array.time_options, R.array.dietary_options, R.array.type_options};
        Spinner[] spinners = {spinnerDifficulty, spinnerGenre, spinnerTime, spinnerDiet, spinnerType};

        for (int i = 0; i < arrayIds.length; i++) {
            // Spinnerがnullでないかチェック（XMLのID間違い対策）
            if (spinners[i] == null) {
                Log.e(TAG, "loadSpinnerAdapters: Spinner at index " + i + " is null. Skipping adapter loading.");
                continue; 
            }
            
            try {
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                        this,
                        arrayIds[i],
                        android.R.layout.simple_spinner_item);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinners[i].setAdapter(adapter);
            } catch (Exception e) {
                Log.e(TAG, "FATAL: Resource loading failed for spinner at index " + i, e);
            }
        }
    }

    private void showFeatureNotImplemented() {
        Toast.makeText(this, "カメラによる食材認識機能は開発中です。", Toast.LENGTH_SHORT).show();
    }

    private void openHistory() {
        if (!isAuthInitialized.get() || auth == null || auth.getCurrentUser() == null || historyManager == null) {
            Toast.makeText(this, "認証処理中、または履歴機能が初期化できていません。しばらくお待ちください。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, HistoryActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // HistoryManagerの防御的初期化 (authリスナーが失敗した場合に備えて)
        if (auth != null && auth.getCurrentUser() != null && historyManager == null) {
             try {
                 historyManager = new HistoryManager(this);
                 Log.d(TAG, "HistoryManager re-initialized in onResume.");
             } catch (Exception e) {
                 Log.e(TAG, "HistoryManager re-initialization failed in onResume.", e);
             }
        }
        
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
        try {
            RecipeHistory item = (RecipeHistory) intent.getSerializableExtra("RECIPE_HISTORY_ITEM");
            if (item != null) {
                // UIコンポーネントのnullチェックは必須
                if (recipeOutputText != null) {
                    recipeOutputText.setText(item.getRecipeContent());
                }

                if (ingredientInput != null) {
                    // 食材と使用フラグを分離
                    String ingredients = item.getIngredientsWithUsage().split(" \\(")[0];
                    ingredientInput.setText(ingredients);
                }

                Toast.makeText(this, "履歴からレシピ「" + item.getRecipeTitle() + "」を再表示しました。", Toast.LENGTH_LONG).show();

                // Intentからデータを削除して、次回Resume/NewIntentで再度読み込まれないようにする
                intent.removeExtra("RECIPE_HISTORY_ITEM");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling history intent.", e);
        }
    }


    private void checkAndLoadApiKey() {
        // UIコンポーネントの null チェック (最低限、ボタンとテキストフィールドは必要)
        if (generateRecipeButton == null || recipeOutputText == null) {
             Log.e(TAG, "checkAndLoadApiKey: UI components are null. Cannot proceed.");
             return;
        }
        
        if (!isAuthInitialized.get()) {
            // 認証が完了するまで待機
            return;
        }

        String loadedKey = preferencesHelper.getPlainKey();

        if (loadedKey != null && !loadedKey.isEmpty()) {
             apiKey = loadedKey;
             generateRecipeButton.setEnabled(true);
             if (recipeOutputText.getText().toString().contains("AIが考案中です") || recipeOutputText.getText().toString().contains("APIキーが設定されていません")) {
                 // ロード中にボタンが無効になっていた場合、メッセージを初期化
                 recipeOutputText.setText(getString(R.string.text_recipe_initial));
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
        // UIコンポーネントの null チェック
        if (generateRecipeButton == null || loadingIndicator == null || recipeOutputText == null || ingredientInput == null) {
             Log.e(TAG, "startRecipeGeneration: UI components are null. Cannot proceed.");
             Toast.makeText(this, "アプリの初期化に失敗しています。", Toast.LENGTH_LONG).show();
             return;
        }
        
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
        // null チェック
        if (ingredientInput == null || minPriceInput == null || maxPriceInput == null || 
            spinnerDifficulty == null || spinnerGenre == null || spinnerTime == null || 
            spinnerDiet == null || spinnerType == null || editOptionalDifficulty == null || 
            editOptionalGenre == null || editOptionalTime == null || editOptionalDiet == null || 
            editOptionalType == null || editInstructions == null || useAllIngredientsCheckbox == null) {
            
            Log.e(TAG, "continueRecipeGeneration: One or more critical UI components are null.");
            Toast.makeText(this, "レシピ生成に必要なUIコンポーネントが初期化されていません。開発者にご連絡ください。", Toast.LENGTH_LONG).show();
            return;
        }
        
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
                runOnUiThread(() -> {
                    if (recipeOutputText != null) {
                        recipeOutputText.setText(result);
                    }
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (generateRecipeButton != null) generateRecipeButton.setEnabled(true);
                    if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();

                    // 履歴の保存
                    String generatedRecipe = recipeOutputText != null ? recipeOutputText.getText().toString() : "";
                    if (!generatedRecipe.contains("エラー") && historyManager != null) {
                         // nullチェックはcontinueRecipeGenerationの最初で行われている
                         String title = extractTitle(generatedRecipe);
                         historyManager.saveRecipe(title, ingredientsWithUsage, allConstraints, generatedRecipe);
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    if (generateRecipeButton != null) generateRecipeButton.setEnabled(true);
                    if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
                    if (recipeOutputText != null) {
                        recipeOutputText.setText("エラーが発生しました:\n" + error);
                    }
                    Toast.makeText(MainActivity.this, "API呼び出しに失敗: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * レシピ本文からタイトルを抽出するヘルパーメソッド
     */
    private String extractTitle(String recipeContent) {
        if (recipeContent == null || recipeContent.isEmpty()) {
            return "無題のレシピ";
        }
        // 最初の行をタイトルとして抽出（Markdownの#または単純な最初の行）
        String[] lines = recipeContent.split("\n");
        if (lines.length > 0) {
            String title = lines[0].trim();
            // Markdownのタイトルマークダウンを削除
            if (title.startsWith("#")) {
                title = title.replaceAll("^#+\\s*", "");
            }
            if (title.length() > 30) {
                 return title.substring(0, 30) + "...";
            }
            return title.isEmpty() ? "無題のレシピ" : title;
        }
        return "無題のレシピ";
    }

    private String combineConstraint(String spinnerSelection, String optionalInput) {
        String input = optionalInput.trim();
        if (input.isEmpty()) {
            return spinnerSelection;
 