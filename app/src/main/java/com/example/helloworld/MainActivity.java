package com.example.liefantidia2;

import android.content.ClipData;
import android.content.ClipboardManager;
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
    private TextView recipeOutputText;
    private TextView generationStatus;
    private Button generateRecipeButton;
    private Button settingsButton;
    private Button historyButton;
    private Button copyRecipeButton;
    private Button shareRecipeButton;
    private ProgressBar loadingIndicator;

    private Spinner spinnerDifficulty;
    private Spinner spinnerGenre;
    private Spinner spinnerTime;
    private Spinner spinnerDiet;
    private CheckBox useAllIngredientsCheckbox;
    private Spinner spinnerType;
    private EditText editInstructions;

    private AiProxyClient apiClient;
    private FirebaseAuth auth;
    private HistoryManager historyManager;
    private String historyOwnerUid;
    private final AtomicBoolean isAuthInitialized = new AtomicBoolean(false);
    private FirebaseAuth.AuthStateListener authListener;

    private boolean generating;
    private long uiGeneration;
    private boolean generationFailed;
    private boolean destroyed;
    private String lastRecipe = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        WindowInsetsHelper.apply(this);

        initializeUI();
        loadSpinnerAdapters();

        apiClient = new AiProxyClient();

        if (settingsButton != null) settingsButton.setOnClickListener(v -> openSettings());
        if (generateRecipeButton != null) generateRecipeButton.setOnClickListener(v -> { if (generating) stopGeneration(); else startRecipeGeneration(); });
        if (historyButton != null) historyButton.setOnClickListener(v -> openHistory());
        if (copyRecipeButton != null) copyRecipeButton.setOnClickListener(v -> copyRecipe());
        if (shareRecipeButton != null) shareRecipeButton.setOnClickListener(v -> shareRecipe());

        if (savedInstanceState != null) {
            historyOwnerUid = savedInstanceState.getString("historyOwnerUid");
            lastRecipe = savedInstanceState.getString("recipe", "");
            if (!lastRecipe.isEmpty() && recipeOutputText != null) {
                recipeOutputText.setText(lastRecipe);
            }
        }
        updateRecipeActions();

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
            }
            auth = FirebaseAuth.getInstance();
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed.", e);
        }

        if (auth != null) {
            AccountController.get(this).state().observe(this, state -> updateGenerationAvailability());
            initializeFirebaseAuth();
        } else {
            isAuthInitialized.set(true);
            updateGenerationAvailability();
        }
    }

    private void initializeFirebaseAuth() {
        if (auth == null) {
            isAuthInitialized.set(true);
            updateGenerationAvailability();
            return;
        }

        authListener = firebaseAuth -> {
            if (destroyed) return;

            FirebaseUser user = firebaseAuth.getCurrentUser();
            String currentUid = user == null ? null : user.getUid();
            if (!java.util.Objects.equals(historyOwnerUid, currentUid)) {
                if (generating) stopGeneration();
                historyManager = null;
                historyOwnerUid = currentUid;
                lastRecipe = "";
                generationStatus.setVisibility(View.GONE);
                recipeOutputText.setText(R.string.text_recipe_initial);
                updateRecipeActions();
            }
            if (user != null) {
                isAuthInitialized.set(true);
                if (historyManager == null) {
                    try {
                        historyManager = new HistoryManager(this);
                    } catch (Exception e) {
                        Log.e(TAG, "HistoryManager initialization failed.", e);
                    }
                }
                updateGenerationAvailability();
                return;
            }

            if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    || AccountController.get(this).busy()) return;
            firebaseAuth.signInAnonymously().addOnCompleteListener(this, task -> {
                if (destroyed) return;
                if (!task.isSuccessful()) {
                    Log.e(TAG, "Anonymous sign-in failed.", task.getException());
                    isAuthInitialized.set(true);
                    updateGenerationAvailability();
                    Toast.makeText(
                            MainActivity.this,
                            "認証に失敗しました。通信を確認してアプリを開き直してください。",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        };
        auth.addAuthStateListener(authListener);
    }

    private void initializeUI() {
        ingredientInput = findViewById(R.id.edit_text_ingredients);
        generateRecipeButton = findViewById(R.id.button_generate_recipe);
        settingsButton = findViewById(R.id.button_settings);
        historyButton = findViewById(R.id.button_history);
        copyRecipeButton = findViewById(R.id.button_copy_recipe);
        shareRecipeButton = findViewById(R.id.button_share_recipe);
        recipeOutputText = findViewById(R.id.text_view_recipe_output);
        generationStatus = findViewById(R.id.text_generation_status);
        loadingIndicator = findViewById(R.id.progress_bar_loading);

        if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);

        spinnerDifficulty = findViewById(R.id.spinner_difficulty);
        spinnerGenre = findViewById(R.id.spinner_genre);
        spinnerTime = findViewById(R.id.spinner_time);
        spinnerDiet = findViewById(R.id.spinner_diet);
        useAllIngredientsCheckbox = findViewById(R.id.checkbox_use_all_ingredients);
        spinnerType = findViewById(R.id.spinner_type);

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
            if (spinners[i] == null) continue;
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                    this,
                    arrayIds[i],
                    R.layout.spinner_selected
            );
            adapter.setDropDownViewResource(R.layout.spinner_dropdown);
            spinners[i].setAdapter(adapter);
        }
    }

    private void copyRecipe() {
        if (lastRecipe.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("CookAi レシピ", lastRecipe));
        Toast.makeText(this, "レシピをコピーしました", Toast.LENGTH_SHORT).show();
    }

    private void shareRecipe() {
        if (lastRecipe.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, lastRecipe);
        startActivity(Intent.createChooser(share, "レシピを共有"));
    }

    private void updateRecipeActions() {
        boolean enabled = !lastRecipe.isEmpty() && !generating;
        if (copyRecipeButton != null) copyRecipeButton.setEnabled(enabled);
        if (shareRecipeButton != null) shareRecipeButton.setEnabled(enabled);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("recipe", lastRecipe);
        state.putString("historyOwnerUid", historyOwnerUid);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (auth != null && authListener != null) {
            auth.removeAuthStateListener(authListener);
        }
        if (apiClient != null) apiClient.cancel();
        super.onDestroy();
    }


    private void openHistory() {
        if (generating || (auth != null && AccountController.get(this).busy())) {
            Toast.makeText(this, "処理が完了してから履歴を開いてください。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isAuthInitialized.get() || auth == null || auth.getCurrentUser() == null || historyManager == null) {
            Toast.makeText(this, "履歴を開けません。通信を確認してアプリを開き直してください。", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, HistoryActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (auth != null && auth.getCurrentUser() != null &&
                !java.util.Objects.equals(historyOwnerUid, auth.getCurrentUser().getUid())) {
            historyManager = null;
            historyOwnerUid = auth.getCurrentUser().getUid();
            lastRecipe = "";
            recipeOutputText.setText(R.string.text_recipe_initial);
            updateRecipeActions();
        }
        if (auth != null && auth.getCurrentUser() != null && historyManager == null) {
            try {
                historyManager = new HistoryManager(this);
            } catch (Exception e) {
                Log.e(TAG, "HistoryManager re-initialization failed.", e);
            }
        }

        updateGenerationAvailability();
        if (auth != null && auth.getCurrentUser() == null && !AccountController.get(this).busy()) {
            // Settings may have signed out while this Activity was paused.
            auth.signInAnonymously().addOnCompleteListener(this, task -> {
                if (!task.isSuccessful()) Log.e(TAG, "Anonymous sign-in failed.", task.getException());
            });
        }
        handleHistoryIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHistoryIntent(intent);
    }

    private void handleHistoryIntent(Intent intent) {
        if (intent == null) return;
        try {
            RecipeHistory item = (RecipeHistory) intent.getSerializableExtra("RECIPE_HISTORY_ITEM");
            if (item == null) return;

            lastRecipe = item.getRecipeContent() == null ? "" : item.getRecipeContent();
            if (recipeOutputText != null) recipeOutputText.setText(lastRecipe);
            updateRecipeActions();

            if (ingredientInput != null && item.getIngredientsWithUsage() != null) {
                String ingredients = item.getIngredientsWithUsage().split(" \\(")[0];
                ingredientInput.setText(ingredients);
            }

            Toast.makeText(
                    this,
                    "履歴からレシピ「" + item.getRecipeTitle() + "」を再表示しました。",
                    Toast.LENGTH_LONG
            ).show();
            intent.removeExtra("RECIPE_HISTORY_ITEM");
        } catch (Exception e) {
            Log.e(TAG, "Error handling history intent.", e);
        }
    }

    private void updateGenerationAvailability() {
        if (generateRecipeButton == null || apiClient == null) return;

        boolean proxyReady = apiClient.isConfigured();
        boolean authReady = isAuthInitialized.get() && auth != null && auth.getCurrentUser() != null;
        boolean ready = proxyReady && (authReady || isAuthInitialized.get()) && !(auth != null && AccountController.get(this).busy());
        generateRecipeButton.setEnabled(generating || ready);
        generateRecipeButton.setText(generating ? "停止" : generationFailed ? "もう一度生成する" : "レシピを生成する");
        ((com.google.android.material.button.MaterialButton) generateRecipeButton).setIconResource(
            generating ? R.drawable.ic_cook_stop : R.drawable.ic_cook_spark);

        if (recipeOutputText == null || !lastRecipe.isEmpty() || generating) return;

        if (!proxyReady) {
            recipeOutputText.setText("現在サービスを利用できません。しばらくしてからお試しください。");
        } else if (isAuthInitialized.get() && !authReady) {
            recipeOutputText.setText("認証に失敗しました。通信を確認してアプリを開き直してください。");
        } else if (ready) {
            String current = recipeOutputText.getText().toString();
            if (current.contains("未設定") || current.contains("認証に失敗")) {
                recipeOutputText.setText(getString(R.string.text_recipe_initial));
            }
        }
    }

    private void openSettings() {
        if (generating) {
            Toast.makeText(this, "生成を停止してから設定を開いてください。", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void startRecipeGeneration() {
        if (generating || (auth != null && AccountController.get(this).busy())) return;
        if (generateRecipeButton == null || loadingIndicator == null || recipeOutputText == null || ingredientInput == null) {
            Toast.makeText(this, "アプリの初期化に失敗しています。", Toast.LENGTH_LONG).show();
            return;
        }
        if (apiClient == null || !apiClient.isConfigured()) {
            Toast.makeText(this, "現在サービスを利用できません。", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isAuthInitialized.get()) {
            Toast.makeText(this, "接続を確認しています。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (auth == null) {
            Toast.makeText(this, "接続できません。アプリを開き直してください。", Toast.LENGTH_LONG).show();
            return;
        }
        if (auth.getCurrentUser() == null) {
            isAuthInitialized.set(false);
            updateGenerationAvailability();
            auth.signInAnonymously().addOnCompleteListener(this, task -> {
                if (destroyed) return;
                isAuthInitialized.set(true);
                updateGenerationAvailability();
                if (task.isSuccessful()) startRecipeGeneration();
                else Toast.makeText(this, "認証に失敗しました。通信を確認してもう一度お試しください。", Toast.LENGTH_LONG).show();
            });
            return;
        }

        continueRecipeGeneration();
    }

    private void continueRecipeGeneration() {
        if (ingredientInput == null || spinnerDifficulty == null || spinnerGenre == null ||
                spinnerTime == null || spinnerDiet == null || spinnerType == null ||
                editInstructions == null || useAllIngredientsCheckbox == null) {
            Toast.makeText(this, "画面を開き直してお試しください。", Toast.LENGTH_LONG).show();
            return;
        }

        String ingredients = ingredientInput.getText().toString().trim();
        if (ingredients.isEmpty()) {
            Toast.makeText(this, R.string.toast_input_ingredients, Toast.LENGTH_SHORT).show();
            return;
        }
        String invalidIngredient = FoodInputValidator.validate(ingredients);
        if (invalidIngredient != null) {
            ingredientInput.setError(invalidIngredient);
            ingredientInput.requestFocus();
            return;
        }

        boolean mustUseAll = useAllIngredientsCheckbox.isChecked();
        String ingredientUsage = mustUseAll
                ? " (入力された具材は全て使用してください)"
                : " (入力された具材は、全て使用しなくても構いません)";
        final String ingredientsWithUsage = ingredients + ingredientUsage;

        String difficulty = spinnerDifficulty.getSelectedItem().toString();
        String genre = spinnerGenre.getSelectedItem().toString();
        String timeConstraint = spinnerTime.getSelectedItem().toString();
        String dietConstraint = spinnerDiet.getSelectedItem().toString();
        String typeConstraint = spinnerType.getSelectedItem().toString();

        StringBuilder allConstraintsBuilder = new StringBuilder();
        allConstraintsBuilder.append(String.format(
                "主食の分類: %s, 難易度: %s, ジャンル: %s, 調理時間: %s, 食事制限: %s",
                typeConstraint,
                difficulty,
                genre,
                timeConstraint,
                dietConstraint
        ));

        String instructions = editInstructions.getText().toString().trim();
        if (!instructions.isEmpty()) {
            allConstraintsBuilder.append("\n【今回の追加指示】").append(instructions);
        }
        FirebaseUser currentUser = auth.getCurrentUser();
        CookingPreferences preferences = new CookingPreferencesStore(this).load(currentUser == null ? null : currentUser.getUid());
        allConstraintsBuilder.append(preferences.prompt());
        final String allConstraints = allConstraintsBuilder.toString();

        if (ingredientsWithUsage.length() > 2000 || allConstraints.length() > 4000) {
            Toast.makeText(this, "入力が長すぎます。食材や条件を短くしてください。", Toast.LENGTH_LONG).show();
            return;
        }
        final long uiRequest = ++uiGeneration;
        generationFailed = false;
        generationStatus.setVisibility(View.GONE);
        generating = true;
        updateRecipeActions();
        updateGenerationAvailability();
        recipeOutputText.setText("レシピをAIが考案中です...");
        loadingIndicator.setVisibility(View.VISIBLE);

        FirebaseUser user = auth.getCurrentUser();
        apiClient.generateRecipe(user, ingredientsWithUsage, allConstraints, new AiProxyClient.RecipeCallback() {
            private boolean succeeded;
            private String generatedRecipe = "";

            @Override
            public void onResult(String result) {
                succeeded = true;
                generatedRecipe = result == null ? "" : result.trim();
                runOnUiThread(() -> {
                    if (destroyed || uiRequest != uiGeneration || generatedRecipe.isEmpty()) return;
                    lastRecipe = generatedRecipe;
                    if (recipeOutputText != null) recipeOutputText.setText(lastRecipe);
                });
            }

            @Override
            public void onFailure(String error) {
                runOnUiThread(() -> {
                    if (destroyed || uiRequest != uiGeneration) return;
                    generationFailed = true;
                    generationStatus.setText(error);
                    generationStatus.setVisibility(View.VISIBLE);
                    if (recipeOutputText != null) {
                        recipeOutputText.setText(lastRecipe.isEmpty()
                                ? "エラーが発生しました:\n" + error
                                : lastRecipe);
                    }
                    Toast.makeText(MainActivity.this, "AI生成に失敗: " + error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    if (destroyed || uiRequest != uiGeneration) return;
                    generating = false;
                    if (loadingIndicator != null) loadingIndicator.setVisibility(View.GONE);
                    updateGenerationAvailability();
                    updateRecipeActions();

                    if (succeeded && !generatedRecipe.isEmpty()) {
                        Toast.makeText(MainActivity.this, "レシピ生成が完了しました！", Toast.LENGTH_SHORT).show();
                        if (historyManager != null) {
                            historyManager.saveRecipe(ingredientsWithUsage, allConstraints, generatedRecipe);
                        }
                    }
                });
            }
        });
    }

    private void stopGeneration() {
        ++uiGeneration;
        apiClient.cancel();
        generating = false;
        generationFailed = true;
        loadingIndicator.setVisibility(View.GONE);
        recipeOutputText.setText(lastRecipe.isEmpty() ? "生成を停止しました。もう一度生成できます。" : lastRecipe);
        generationStatus.setText("生成を停止しました。もう一度生成できます。");
        generationStatus.setVisibility(View.VISIBLE);
        updateGenerationAvailability();
        updateRecipeActions();
    }

}
