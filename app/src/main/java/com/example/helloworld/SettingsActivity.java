package com.example.liefantidia2;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

public class SettingsActivity extends AppCompatActivity {
    private AccountController accounts;
    private com.google.firebase.auth.FirebaseAuth auth;
    private com.google.firebase.auth.FirebaseAuth.AuthStateListener authListener;
    private android.widget.EditText emailInput, passwordInput;
    private android.widget.TextView accountStatus, accountMessage;
    private android.view.View credentials;
    private android.widget.EditText presetAllergies, presetSeasonings, presetMethods;
    private String presetOwner;
    private boolean presetsLoaded;
    private CookingPreferencesStore cookingStore;
    private final int[] accountActions = {R.id.button_register, R.id.button_login,
        R.id.button_reset_password, R.id.button_logout, R.id.button_delete_account};

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        WindowInsetsHelper.apply(this);
        setupPresets();
        setupAccounts();
        if (savedInstanceState != null && auth.getCurrentUser() != null &&
                auth.getCurrentUser().getUid().equals(savedInstanceState.getString("presetOwner"))) {
            presetAllergies.setText(savedInstanceState.getString("presetDraftAllergies", ""));
            presetSeasonings.setText(savedInstanceState.getString("presetDraftSeasonings", ""));
            presetMethods.setText(savedInstanceState.getString("presetDraftMethods", ""));
        }
        findViewById(R.id.button_back).setOnClickListener(v -> { if (!accounts.busy()) finish(); });
        ((TextView) findViewById(R.id.text_version)).setText("Version " + BuildConfig.VERSION_NAME);
        findViewById(R.id.button_privacy).setOnClickListener(v -> {
            try (java.io.InputStream input = getAssets().open("privacy.txt")) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                new AlertDialog.Builder(this).setTitle("プライバシーポリシー")
                    .setMessage(output.toString("UTF-8")).setPositiveButton("閉じる", null).show();
            } catch (java.io.IOException e) {
                android.widget.Toast.makeText(this, "表示できませんでした。もう一度お試しください。", android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupAccounts() {
        accounts = AccountController.get(this);
        auth = com.google.firebase.auth.FirebaseAuth.getInstance();
        loadPresets(auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid());
        emailInput = findViewById(R.id.edit_account_email);
        passwordInput = findViewById(R.id.edit_account_password);
        accountStatus = findViewById(R.id.text_account_status);
        accountMessage = findViewById(R.id.text_account_message);
        credentials = findViewById(R.id.account_credentials);
        findViewById(R.id.button_register).setOnClickListener(v -> {
            if (validate(true, true)) accounts.register(email(), password());
        });
        findViewById(R.id.button_login).setOnClickListener(v -> {
            if (validate(true, false)) accounts.login(email(), password());
        });
        findViewById(R.id.button_reset_password).setOnClickListener(v -> {
            if (validate(false, false)) accounts.resetPassword(email());
        });
        findViewById(R.id.button_logout).setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("ログアウトしますか？")
            .setMessage("履歴はアカウントに保存されています。同じメールアドレスで再びログインできます。")
            .setNegativeButton("戻る", null)
            .setPositiveButton("ログアウト", (dialog, which) -> accounts.logout()).show());
        findViewById(R.id.button_delete_account).setOnClickListener(v -> confirmAccountDeletion());
        authListener = firebaseAuth -> renderAccount();
        auth.addAuthStateListener(authListener);
        accounts.state().observe(this, state -> {
            accountMessage.setText(state.message);
            findViewById(R.id.progress_account).setVisibility(state.busy ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!state.busy && !state.migrationBlocked) passwordInput.setText("");
            renderAccount();
        });
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!accounts.busy()) finish();
                else android.widget.Toast.makeText(SettingsActivity.this, "処理が終わるまでお待ちください。", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void setupPresets() {
        cookingStore = new CookingPreferencesStore(this);
        presetAllergies = findViewById(R.id.edit_preset_allergies);
        presetSeasonings = findViewById(R.id.edit_preset_seasonings);
        presetMethods = findViewById(R.id.edit_preset_methods);
        presetAllergies.setSaveEnabled(false);
        presetSeasonings.setSaveEnabled(false);
        presetMethods.setSaveEnabled(false);
        findViewById(R.id.button_save_cooking).setOnClickListener(v -> {
            com.google.firebase.auth.FirebaseUser user = auth.getCurrentUser();
            if (user == null || accounts.busy()) return;
            CookingPreferences preferences = new CookingPreferences(presetAllergies.getText().toString(),
                presetSeasonings.getText().toString(), presetMethods.getText().toString());
            boolean saved = cookingStore.save(user.getUid(), preferences);
            ((TextView) findViewById(R.id.text_preset_status)).setText(saved
                ? "保存しました。次のレシピ生成から反映します。" : "保存できませんでした。もう一度お試しください。");
        });
    }
    private void loadPresets(String uid) {
        CookingPreferences preferences = cookingStore.load(uid);
        presetAllergies.setText(preferences.allergies);
        presetSeasonings.setText(preferences.seasonings);
        presetMethods.setText(preferences.methods);
        presetOwner = uid;
        presetsLoaded = true;
        ((TextView) findViewById(R.id.text_preset_status)).setText("");
    }
    private String email() { return emailInput.getText().toString().trim(); }
    private String password() { return passwordInput.getText().toString(); }
    private boolean validate(boolean needsPassword, boolean registering) {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email()).matches()) {
            emailInput.setError("メールアドレスを確認してください。"); return false;
        }
        if (needsPassword && (password().isEmpty() || (registering && password().length() < 6))) {
            passwordInput.setError(registering ? "6文字以上のパスワードを入力してください。" : "パスワードを入力してください。"); return false;
        }
        return true;
    }
    private void renderAccount() {
        com.google.firebase.auth.FirebaseUser user = auth.getCurrentUser();
        boolean signedIn = user != null && !AccountController.isGuest(user);
        String uid = user == null ? null : user.getUid();
        if (!presetsLoaded || !java.util.Objects.equals(presetOwner, uid)) loadPresets(uid);
        boolean editable = user != null && !accounts.busy();
        presetAllergies.setEnabled(editable);
        presetSeasonings.setEnabled(editable);
        presetMethods.setEnabled(editable);
        findViewById(R.id.button_save_cooking).setEnabled(editable);
        accountStatus.setText(signedIn ? "ログイン中: " + EmailMasker.mask(user.getEmail())
            : "ゲストで利用中です。履歴を残すには、アプリを削除する前にメール登録してください。");
        if (signedIn) {
            emailInput.setText("");
            passwordInput.setText("");
        }
        credentials.setVisibility(signedIn ? android.view.View.GONE : android.view.View.VISIBLE);
        findViewById(R.id.button_logout).setVisibility(signedIn ? android.view.View.VISIBLE : android.view.View.GONE);
        findViewById(R.id.button_delete_account).setVisibility(signedIn ? android.view.View.VISIBLE : android.view.View.GONE);
        for (int id : accountActions) findViewById(id).setEnabled(!accounts.busy());
        emailInput.setEnabled(!accounts.busy());
        passwordInput.setEnabled(!accounts.busy());
        findViewById(R.id.button_back).setEnabled(!accounts.busy());
    }
    private void confirmAccountDeletion() {
        android.widget.EditText confirmation = new android.widget.EditText(this);
        confirmation.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmation.setHint("パスワード");
        confirmation.setSaveEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("アカウントと履歴を削除")
            .setMessage("保存したすべての履歴とアカウントを削除します。元に戻せません。確認のためパスワードを入力してください。")
            .setView(confirmation).setNegativeButton("戻る", null).setPositiveButton("削除する", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = confirmation.getText().toString();
            if (value.isEmpty()) { confirmation.setError("パスワードを入力してください。"); return; }
            dialog.dismiss();
            accounts.deleteAccount(value);
        }));
        dialog.show();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("presetOwner", presetOwner);
        state.putString("presetDraftAllergies", presetAllergies.getText().toString());
        state.putString("presetDraftSeasonings", presetSeasonings.getText().toString());
        state.putString("presetDraftMethods", presetMethods.getText().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (auth != null && authListener != null) auth.removeAuthStateListener(authListener);
        super.onDestroy();
    }
}
