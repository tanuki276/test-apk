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

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private EditText ingredientInput;
    private EditText minPriceInput;
    private EditText maxPriceInput;
    private TextView recipeOutputText;
    private Button generateRecipeButton;
    private Button settingsButton;
    private Button historyButton;
    private Button cameraButton;
    private ProgressBar loadingIndicator;

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

    private AiProxyClient apiClient;

    private FirebaseAuth auth;
    private HistoryManager historyManager;
    private final AtomicBoolean isAuthInitialized = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate: Activity started.");
        setContentView(R.layout.activity_main);

        initializeUI();
        loadSpinnerAdapters();

        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());
        if (generateRecipeButton != null) generateRecipeButton.setOnClickListener(v -> startRecipeGeneration());
        if (cameraButton != null) cameraButton.setOnClickListener(v -> showFeatureNotImplemented());
        if (historyButton != null) historyButton.setOnClickListener(v -> openHistory());

        apiClient = new AiProxyClient();

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
                Log.d(TAG, "FirebaseApp initialized.");
            }
            auth = FirebaseAuth.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed.", e);
        }

        if (auth != null) {
            initializeFirebaseAuth();
        } else {
            isAuthInitialized.set(true);
            updateGenerationAvailability();
        }

        if (generateRecipeButton != null) {
            generateRecipeButton.setEnabled(false);
        }

        Log.d(TAG, "onCreate: Activity setup complete.");
    }

    private void initializeFirebaseAuth() {
        if (auth == null) {
            isAuthInitialized.set(true);
            updateGenerationAvailability();
            return;
        }

        auth.addAuthStateListener(firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user != null) {
                Log.i(TAG, "Firebase anonymous user authenticated.");
                isAuthInitialized.set(true);

                if (historyManager == null) {
                    try {
                        historyManager = new HistoryManager(this);
                        Log.d(TAG, "HistoryManager initialized after auth.");
                    } catch (Exception e) {
                        Log.e(TAG, "HistoryManager initialization failed.", e);
                    }
                }

                updateGenerationAvailability();
            } else {
                Log.w(TAG, "User not authenticated, starting anonymous sign-in...");
                auth.signInAnonymously().addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "signInAnonymously:success");
                    } else {
                        Log.e(TAG, "signInAnonymously:failure", task.getException());
                        isAuthInitialized.set(true);
                        updateGenerationAvailability();
                        Toast.makeText(
                                MainActivity.this,
                                "匿名認証に失敗しました。AI生成と履歴機能を利用できません。",
                                Toast.LENGTH_LONG
                        ).show();
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

        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

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
    }

    private void loadSpinnerAdapters() {
        int[] arrayIds = {
                R.array.difficulty_options,
                R.array.genre_options,
                R.array.time_options,
                R.array.dietary_options,
                R.array.type_options
        };
        Spinner[] spinners = {spinnerDifficulty, spinnerGenre, spinnerTime, spinnerDiet, spinnerType};

        for (int i = 0; i < arrayIds.length; i++) {
            if (spinners[i] == null) {
                Log.e(TAG, "Spinner at index " + i + " is null. Skipping adapter loading.");
                continue;
            }

            try {
                ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                        this,
                        arrayIds[i],
                        android.R.layout.simple_spinner_item
                );
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinners[i].setAdapter(adapter);
            } catch (Exception e) {
                Log.e(TAG, "Resource loading failed for spinner at index " + i, e);
            }
        }
    }

    private void showFeatureNotImplemented() {
        Toast.makeText(this, "カメラによる食材認識機能は開発中です。", Toast.LENGTH_SHORT).show();
    }

    private void openHistory() {
        if (!isAuthInitialized.get() || auth == null || auth.getCurrentUser() == null || historyManager == null) {
            Toast.makeText(this, "認証処理中、または履歴機能が初期化できていません。", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, HistoryActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (auth != null && auth.getCurrentUser() != null && historyManager == null) {
            try {
                historyManager = new HistoryManager(this);
            } catch (Exception e) {
                Log.e(TAG, "HistoryManager re-initialization failed in onResume.", e);
            }
        }

        updateGenerationAvailability();
        handleHistoryIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHistoryIntent(intent);
    }

    private void handleHistoryIntent(Intent intent) {
        try {
            RecipeHistory item = (RecipeHistory) intent.getSerializableExtra("RECIPE_HISTORY_ITEM");
            if (item != null) {
                if (recipeOutputText != null) {
                    recipeOutputText.setText(item.getRecipeContent());
                }

                if (ingredientInput != null) {
                    String ingredients = item.getIngredientsWithUsage().split(" \\(")[0];
                    ingredientInput.setText(ingredients);
                }

                Toast.makeText(
                        this,
                        "履歴からレシピ「" + item.getRecipeTitle() + "」を再表示しました。",
                        Toast.LENGTH_LONG
                ).show();
                intent.removeExtra("RECIPE_HISTORY_ITEM");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling history intent.", e);
        }
    }

    private void updateGenerationAvailability() {
        if (generateRecipeButton == null || apiClient == null) return;

        boolean proxyReady = apiClient.isConfigured();
        boolean authReady = isAuthInitialized.get() && auth != null && auth.getCurrentUser() != null;
        boolean ready = proxyReady && authReady;
        generateRecipeButton.setEnabled(ready);

        if (recipeOutputText == null) return;

        if (!proxyReady) {
            recipeOutputText.setText("AIサーバーが未設定です。アプリのAI_PROXY_URLを設定してください。");
        } else if (isAuthInitialized.get() && !authReady) {
            recipeOutputText.setText("Firebase匿名認証に失敗したためAI生成を利用できません。");
        } else if (ready) {
            String current = recipeOutputText.getText().toString();
            if (current.contains("未設定") || current.contains("認証に失敗")) {
                recipeOutputText.setText(getString(R.string.text_recipe_initial));
            }
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void startRecipeGeneration() {
        if (generateRecipeButton == null || loadingIndicator == null || recipeOutputText == null || ingredientInput == null) {
            Log.e(TAG, "startRecipeGeneration: UI components are null.");
            Toast.makeText(this, "アプリの初期化に失敗しています。", Toast.LENGTH_LONG).show();
            return;
        }

        if (apiClient == null || !apiClient.isConfigured()) {
            Toast.makeText(this, "AIサーバーが未設定です。", Toast.LENGTH_LONG).show();
            return;
        }

        if (!isAuthInitialized.get() || auth == null || auth.getCurrentUser() == null) {
            Toast.makeText(this, "認証処理中です。", Toast.LENGTH_SHORT).show();
            return;
        }

        continueRecipeGeneration();
    }

    private void continueRecipeGeneration() {
        if (ingredientInput == null || minPriceInput == null || maxPriceInput == null ||
                spinnerDifficulty == null || spinnerGenre == null || spinnerTime == null ||
                spinnerDiet == null || spinnerType == null || editOptionalDifficulty == null ||
                editOptionalGenre == null || editOptionalTime == null || editOptionalDiet == null ||
                editOptionalType == null || editInstructions == null || useAllIngredientsCheckbox == null) {

            Log.e(TAG, "One or more critical UI components are null.");
            Toast.makeText(this, "レシピ生成に必要なUIコンポーネントが初期化されていません。", Toast.LENGTH_LONG).show();
            return;
        }

        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, R.string.toast_input_ingredients, Toast.LENGTH_SHORT).show();
            return;
        }

        String minPriceStr = minPriceInput.getText().toString().trim();
        String maxPriceStr = maxPriceInput.getText().toString().trim();
        String priceConstraint = "";

        if (!minPriceStr.isEmpty() || !maxPriceStr.isEmpty()) {
            try {
                int min = minPriceStr.isEmpty() ? 0 : Integer.parseInt(minPriceStr);
                int max = maxPriceStr.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(maxPriceStr);

                if (min > max) {
                    Toast.makeText(this, R.string.error_invalid_price_range, Toast.LENGTH_LONG).show();
                    return;
                }
                String maxDisplay = max == Integer.MAX_VALUE ? "制限なし" : max + "円";
                priceConstraint = String.format("価格帯: %d円〜%s", min, maxDisplay);
            } catch (NumberFormatException e) {
                Toast.makeText(this, R.string.error_invalid_price_format, Toast.LENGTH_LONG).show();
                return;
            }
        }

        boolean mustUseAll = useAllIngredientsCheckbox.isChecked();
        String ingredientUsage = mustUseAll
                ? " (入力された具材は全て使用してください)"
                : " (入力された具材は、全て使用しなくても構いません)";
        final String ingredientsWithUsage = ingredients + ingredientUsage;

        String difficulty = combineConstraint(spinnerDifficulty.getSelectedItem().toString(), editOptionalDifficulty.getText().toString());
        String genre = combineConstraint(spinnerGenre.getSelectedItem().toString(), editOptionalGenre.getText().toString());
        String timeConstraint = combineConstraint(spinnerTime.getSelectedItem().toString(), editOptionalTime.getText().toString());
        String dietConstraint = combineConstraint(spinnerDiet.getSelectedItem().toString(), editOptionalDiet.getText().toString());
        String typeConstraint = combineConstraint(spinnerType.getSelectedItem().toString(), editOptionalType.getText().toString());

        StringBuilder allConstraintsBuilder = new StringBuilder();
        allConstraintsBuilder.append(String.format(
                "主食の分類: %s, 難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s",
                typeConstraint,
                difficulty,
                genre,
                timeConstraint,
                dietConstraint
        ));

        if (!priceConstraint.isEmpty()) {
            allConstraintsBuilder.append(", ").append(priceConstraint);
        }

        String instructions = editInstructions.getText().toString().trim();
        if (!instructions.isEmpty()) {
            allConstraintsBuilder.append(". 【最重要指示】: ").append(instructions);
        }

        final String allConstraints = allConstraintsBuilder.toString();

        recipeOutputText.setText("レシピをAIが考案中です...");
        generateRecipeButton.setEnabled(false);
        loadingIndicator.setVisibility(View.VISIBLE);

        FirebaseUser user = auth == null ? null : auth.getCurrentUser();
        apiClient.generateRecipe(user, ingredientsWithUsage, allConstraints, new AiProxyClient.RecipeCallback() {
            private boolean succeeded = false;

            @Override
            public void onResult(String result) {
                succeeded = true;
                runOnUiThread(() -> {
                    if (recipeOutputText != null) {
                        recipeOutputText.setText(result);
                    }
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    updateGenerationAvailability();
                    if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

                    if (succeeded) {
                        Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
                        String generatedRecipe = recipeOutputText != null ? recipeOutputText.getText().toString() : "";
                        if (!generatedRecipe.isEmpty() && historyManager != null) {
                            historyManager.saveRecipe(ingredientsWithUsage, allConstraints, generatedRecipe);
                        }
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    if (recipeOutputText != null) {
                        recipeOutputText.setText("エラーが発生しました:\n" + error);
                    }
                    Toast.makeText(MainActivity.this, "AI生成に失敗: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String combineConstraint(String spinnerSelection, String optionalInput) {
        String input = optionalInput.trim();

        if (!input.isEmpty()
                && !spinnerSelection.contains("選択しない")
                && !spinnerSelection.contains("指定なし")) {
            return spinnerSelection + "（" + input + "）";
        }

        if (!input.isEmpty()) {
            return input;
        }

        return spinnerSelection;
    }
}
