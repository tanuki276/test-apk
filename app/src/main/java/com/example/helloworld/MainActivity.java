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
// androidx.biometric.* のimportは不要になります

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ... (UIコンポーネントの宣言は省略)
    private Button cameraButton; 
    private ProgressBar loadingIndicator;

    // APIキー関連
    private String apiKey = null;
    private KeyStoreHelper keyStoreHelper; // 👈 宣言は残すが使用しない
    private PreferencesHelper preferencesHelper;
    // private boolean isBiometricPromptShowing = false; // 削除

    // APIクライアント
    private GeminiApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 👈 修正: KeyStoreHelperの初期化は削除/コメントアウト
        keyStoreHelper = null; 

        preferencesHelper = new PreferencesHelper(this);
        apiClient = new GeminiApiClient();
        
        // ... (UIコンポーネント、Spinnerの初期化、イベントリスナーの設定は省略)
        
        // 初期状態ではボタンを無効化
        generateRecipeButton.setEnabled(false);
    }

    // ... (loadSpinnerAdapters, showFeatureNotImplemented メソッドは省略)

    @Override
    protected void onResume() {
        super.onResume();
        checkAndLoadApiKey();
    }

    /**
     * APIキーの存在チェックとUI表示の更新 (平文版)。
     */
    private void checkAndLoadApiKey() {
        // 👈 修正: 平文キーを読み込む
        String loadedKey = preferencesHelper.getPlainKey();

        if (loadedKey != null && !loadedKey.isEmpty()) {
             // 正常にキーがロードされた場合
             apiKey = loadedKey;
             generateRecipeButton.setEnabled(true);
             recipeOutputText.setText(getString(R.string.app_name) + "へようこそ！食材を入力してレシピを生成しましょう。");
        } else if (preferencesHelper.hasEncryptedKey()) {
             // 既存の暗号化キーが残っている場合 (平文保存に移行するため無効化)
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

    // ⚠ loadApiKey()、showBiometricPrompt()、handleKeyInvalidated() の3つのメソッドは削除

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    // レシピ生成の開始点 (ここで認証は不要)
    private void startRecipeGeneration() {
        // 👈 修正: 認証ロジックを削除
        if (apiKey == null || apiKey.isEmpty()) {
             Toast.makeText(this, "APIキーが設定されていません。設定画面から設定してください。", Toast.LENGTH_LONG).show();
             return;
        }

        continueRecipeGeneration();
    }

    /**
     * APIキーがロードされた後にレシピ生成を実行する (変更なし)
     */
    private void continueRecipeGeneration() {
        // ... (省略: このメソッド内のロジックは変更ありません)
    }

    // ⚠ BiometricPropertiesクラスは削除
}
