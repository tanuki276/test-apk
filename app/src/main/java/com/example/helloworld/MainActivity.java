package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント (既存)
    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    private Button cameraButton; 
    private ProgressBar loadingIndicator;

    // Spinner
    private Spinner spinnerDifficulty;
    private Spinner spinnerGenre;
    private Spinner spinnerTime;
    private Spinner spinnerDiet;
    
    // 👈 【新規追加コンポーネント】
    // 具材のチェックボックス
    private CheckBox useAllIngredientsCheckbox; 
    
    // 主食の分類 Spinner
    private Spinner spinnerType;

    // 選択式の任意入力フィールド (5つのSpinnerに対応)
    private EditText editOptionalDifficulty;
    private EditText editOptionalGenre;
    private EditText editOptionalTime;
    private EditText editOptionalDiet;
    private EditText editOptionalType; 

    // 自由指示 (最重要指示)
    private EditText editInstructions; 
    
    // APIキー関連
    private String apiKey = null;
    private KeyStoreHelper keyStoreHelper; 
    private PreferencesHelper preferencesHelper;

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        keyStoreHelper = null; 
        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();

        // 既存のUIコンポーネントの初期化
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        minPriceInput = findViewById(R.id.edit_text_min_price);
        maxPriceInput = findViewById(R.id.edit_text_max_price);
        generateRecipeButton = findViewById(R.id.button_generate_recipe); 
        settingsButton = findViewById(R.id.button_settings);
        recipeOutputText = findViewById(R.id.text_view_recipe_output); 
        loadingIndicator = findViewById(R.id.progress_bar_loading);
        loadingIndicator.setVisibility(View.GONE);
        cameraButton = findViewById(R.id.button_camera); 

        // Spinnerの初期化 (既存の4つ)
        spinnerDifficulty = findViewById(R.id.spinner_difficulty);
        spinnerGenre = findViewById(R.id.spinner_genre);
        spinnerTime = findViewById(R.id.spinner_time);
        spinnerDiet = findViewById(R.id.spinner_diet);
        
        // 👈 【新規追加コンポーネントの初期化】
        useAllIngredientsCheckbox = findViewById(R.id.checkbox_use_all_ingredients);
        spinnerType = findViewById(R.id.spinner_type); 

        editOptionalDifficulty = findViewById(R.id.edit_optional_difficulty);
        editOptionalGenre = findViewById(R.id.edit_optional_genre);
        editOptionalTime = findViewById(R.id.edit_optional_time);
        editOptionalDiet = findViewById(R.id.edit_optional_diet);
        editOptionalType = findViewById(R.id.edit_optional_type); 
        
        editInstructions = findViewById(R.id.edit_instructions); 

        loadSpinnerAdapters();

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        cameraButton.setOnClickListener(v -> showFeatureNotImplemented());

        generateRecipeButton.setEnabled(false);
    }

    /**
     * SpinnerにArrayAdapterを設定 (主食分類用を追加)
     */
    private void loadSpinnerAdapters() {
        // XMLで定義された配列リソースを使用
        ArrayAdapter<CharSequence> adapterBase = ArrayAdapter.createFromResource(
                this, 
                R.array.difficulty_options, 
                android.R.layout.simple_spinner_item);
        adapterBase.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 既存のSpinnerの設定
        spinnerDifficulty.setAdapter(adapterBase);
        spinnerGenre.setAdapter(ArrayAdapter.createFromResource(this, R.array.genre_options, android.R.layout.simple_spinner_item));
        spinnerTime.setAdapter(ArrayAdapter.createFromResource(this, R.array.time_options, android.R.layout.simple_spinner_item));
        spinnerDiet.setAdapter(ArrayAdapter.createFromResource(this, R.array.dietary_options, android.R.layout.simple_spinner_item));
        
        // 👈 【主食分類用Spinnerの追加】
        spinnerType.setAdapter(ArrayAdapter.createFromResource(this, R.array.type_options, android.R.layout.simple_spinner_item));

        // ドロップダウンビューリソースの設定 (全てのSpinnerに適用)
        Spinner[] allSpinners = new Spinner[]{
            spinnerDifficulty, spinnerGenre, spinnerTime, spinnerDiet, spinnerType 
        };
        for (Spinner spinner : allSpinners) {
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
        checkAndLoadApiKey();
    }

    /**
     * APIキーの存在チェックとUI表示の更新 (平文版)。
     */
    private void checkAndLoadApiKey() {
        String loadedKey = preferencesHelper.getPlainKey();

        if (loadedKey != null && !loadedKey.isEmpty()) {
             apiKey = loadedKey;
             generateRecipeButton.setEnabled(true);
             recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！");
        } else if (preferencesHelper.hasEncryptedKey()) {
             // 既存の暗号化キーが残っている場合
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

        continueRecipeGeneration();
    }

    /**
     * APIキーがロードされた後にレシピ生成を実行する
     */
    private void continueRecipeGeneration() {
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
        
        // 👈 【制約を結合するロジックの修正】

        // 1. 具材の利用指示をingredients文字列に結合
        boolean mustUseAll = useAllIngredientsCheckbox.isChecked();
        String ingredientUsage = mustUseAll ? " (入力された具材は全て使用してください)" : " (入力された具材は、全て使用しなくても構いません)";
        String ingredientsWithUsage = ingredients + ingredientUsage;

        // 2. 選択式と任意入力の結合
        String difficulty = combineConstraint(spinnerDifficulty.getSelectedItem().toString(), editOptionalDifficulty.getText().toString());
        String genre = combineConstraint(spinnerGenre.getSelectedItem().toString(), editOptionalGenre.getText().toString());
        String timeConstraint = combineConstraint(spinnerTime.getSelectedItem().toString(), editOptionalTime.getText().toString());
        String dietConstraint = combineConstraint(spinnerDiet.getSelectedItem().toString(), editOptionalDiet.getText().toString());
        String typeConstraint = combineConstraint(spinnerType.getSelectedItem().toString(), editOptionalType.getText().toString()); 

        // 3. 全制約の構築 (プロンプトに渡す文字列)
        StringBuilder allConstraintsBuilder = new StringBuilder();
        
        // 主食、難易度、ジャンル、時間、食事制限の情報を追加
        allConstraintsBuilder.append(String.format("主食の分類: %s, 難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s",
            typeConstraint, difficulty, genre, timeConstraint, dietConstraint));

        if (!priceConstraint.isEmpty()) {
             allConstraintsBuilder.append(", ").append(priceConstraint);
        }

        // 4. 自由指示 (最重要指示)
        String instructions = editInstructions.getText().toString().trim();
        if (!instructions.isEmpty()) {
             allConstraintsBuilder.append(". 【重要】: ").append(instructions);
        }
        
        String allConstraints = allConstraintsBuilder.toString();
        
        // 5. APIクライアントの呼び出し
        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        // APIクライアントの呼び出し
        // APIクライアントの引数はシンプルに、ingredientsと全ての制約を結合した文字列の2つに修正します
        apiClient.generateRecipe(apiKey, ingredientsWithUsage, allConstraints, new GeminiApiClient.RecipeCallback() {

            @Override
            public void onResult(String result) {
                // UI操作はメインスレッドで行う必要がある
                runOnUiThread(() -> recipeOutputText.setText(result));
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    generateRecipeButton.setEnabled(true);
                    loadingIndicator.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
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
    
    /**
     * Spinnerの選択結果と任意入力の内容を結合するヘルパーメソッド
     */
    private String combineConstraint(String spinnerSelection, String optionalInput) {
        String input = optionalInput.trim();
        if (input.isEmpty()) {
            return spinnerSelection;
        }
        // 「指定なし」や「その他（任意に入力）」の場合は、任意入力を優先する
        if (spinnerSelection.equals("指定なし") || spinnerSelection.contains("任意に入力")) { 
            return input;
        }
        // それ以外の場合は両方を結合
        return spinnerSelection + "（または、" + input + "）";
    }

}
