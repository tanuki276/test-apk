package com.example.liefantidia2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UIコンポーネント (宣言)
    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
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

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        keyStoreHelper = null; 

        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();

        // 👈 【修正箇所】すべてのUIコンポーネントを findViewById で初期化する
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

        loadSpinnerAdapters();

        // イベントリスナーの設定
        settingsButton.setOnClickListener(v -> openSettings());
        generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        cameraButton.setOnClickListener(v -> showFeatureNotImplemented());

        // 初期状態ではボタンを無効化
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
             recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！食材を入力してレシピを生成しましょう。");
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

    // レシピ生成の開始点 (ここで認証は不要)
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
            // R.string.toast_input_ingredients を使用
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
                    // R.string.error_invalid_price_range を使用
                    Toast.makeText(this, R.string.error_invalid_price_range, Toast.LENGTH_LONG).show();
                    return;
                }
                String maxDisplay = (max == Integer.MAX_VALUE) ? "制限なし" : max + "円";
                priceConstraint = String.format("価格帯: %d円〜%s", min, maxDisplay);

            } catch (NumberFormatException e) {
                 // R.string.error_invalid_price_format を使用
                 Toast.makeText(this, R.string.error_invalid_price_format, Toast.LENGTH_LONG).show();
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
}
