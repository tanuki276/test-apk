package com.example.liefantidia2;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.HistoryActionListener {

    private static final String TAG = "HistoryActivity";
    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private TextView emptyHistoryText;
    private EditText searchInput;
    private List<RecipeHistory> allHistory = new ArrayList<>();
    private boolean loadFailed;

    // Firestore関連
    private FirebaseFirestore db;
    private String userId;
    private CollectionReference historyRef;
    private String pendingExport;
    private final androidx.activity.result.ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null || pendingExport == null) return;
                try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("No output stream");
                    output.write(pendingExport.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Toast.makeText(this, "レシピを保存しました", Toast.LENGTH_SHORT).show();
                } catch (java.io.IOException e) {
                    Toast.makeText(this, R.string.toast_download_failed, Toast.LENGTH_LONG).show();
                } finally {
                    pendingExport = null;
                }
            });

    @Override public void onDownloadClicked(RecipeHistory item) {
        pendingExport = item.getRecipeContent();
        exportLauncher.launch("CookAi-recipe-" + item.getTimestamp() + ".txt");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingExport", pendingExport);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        WindowInsetsHelper.apply(this);
        if (savedInstanceState != null) pendingExport = savedInstanceState.getString("pendingExport");

        recyclerView = findViewById(R.id.recycler_view_history);
        emptyHistoryText = findViewById(R.id.text_empty_history);
        searchInput = findViewById(R.id.edit_history_search);
        ImageButton backButton = findViewById(R.id.button_back);

        db = FirebaseFirestore.getInstance("default");
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        // ユーザーIDは認証が完了しているはず
        if (user == null) {
            Toast.makeText(this, "履歴を開くには認証が必要です。もう一度お試しください。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        userId = user.getUid();

        // Firestoreのパス: /artifacts/{appId}/users/{userId}/history
        String appId = HistoryManager.getAppId(this);
        historyRef = db.collection(
            String.format("artifacts/%s/users/%s/history", appId, userId)
        );

        setupRecyclerView();
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { renderHistory(); }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        findViewById(R.id.button_clear_search).setOnClickListener(v -> searchInput.setText(""));

        backButton.setOnClickListener(v -> finish());

        loadHistory();
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter(this, new ArrayList<>(), this);
        recyclerView.setAdapter(adapter);
    }

    /**
     * Firestoreから履歴データを読み込む
     */
    private void loadHistory() {
        // 時刻降順でクエリ
        historyRef.orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (isFinishing() || isDestroyed()) return;
                    List<RecipeHistory> historyList = new ArrayList<>();
                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        Map<String, Object> data = new java.util.HashMap<>(document.getData());
                        Object time = data.get("timestamp");
                        if (time instanceof com.google.firebase.Timestamp)
                            data.put("timestamp", ((com.google.firebase.Timestamp) time).toDate().getTime());
                        Map<String, Object> normalized = HistoryMigration.normalize(data, 1L);
                        RecipeHistory history = new RecipeHistory(null, (String) normalized.get("recipeTitle"),
                            (String) normalized.get("ingredientsWithUsage"), (String) normalized.get("allConstraints"),
                            (String) normalized.get("recipeContent"), (Long) normalized.get("timestamp"));
                        history.setId(document.getId());
                        historyList.add(history);
                    }
                    allHistory = historyList;
                    loadFailed = false;
                    renderHistory();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error loading history: ", e);
                    Toast.makeText(this, "履歴の読み込みに失敗しました。", Toast.LENGTH_SHORT).show();
                    loadFailed = true;
                    adapter.updateList(new ArrayList<>());
                    recyclerView.setVisibility(View.GONE);
                    emptyHistoryText.setText("履歴を読み込めませんでした。タップして再試行");
                    emptyHistoryText.setVisibility(View.VISIBLE);
                    emptyHistoryText.setOnClickListener(v -> loadHistory());
                });
    }

    /**
     * 履歴リストに基づいてUI（RecyclerViewと空メッセージ）を更新
     */
    private void renderHistory() {
        if (loadFailed) return;
        List<RecipeHistory> visible = HistorySearch.filter(allHistory, searchInput.getText().toString());
        emptyHistoryText.setText(allHistory.isEmpty() ? getString(R.string.history_empty_message)
            : "検索に一致する履歴がありません。");
        emptyHistoryText.setOnClickListener(null);
        if (visible.isEmpty()) {
            emptyHistoryText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyHistoryText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        adapter.updateList(visible);
    }

    // --- HistoryAdapter.HistoryActionListener の実装 ---

    /**
     * 個別削除ボタンがクリックされた時の処理
     */
    @Override
    public void onDeleteClicked(RecipeHistory item) {
        showDeleteConfirmation(item);
    }

    /**
     * レシピ再表示ボタンがクリックされた時の処理 (MainActivityに戻す)
     */
    @Override
    public void onShowClicked(RecipeHistory item) {
        // MainActivityに戻り、結果をMainActivityで受け取る
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("RECIPE_HISTORY_ITEM", item);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    // --- 削除処理 ---

    /**
     * 個別レシピ削除の確認ダイアログを表示
     */
    private void showDeleteConfirmation(RecipeHistory item) {
        new AlertDialog.Builder(this)
                .setTitle("レシピ削除の確認")
                .setMessage(item.getRecipeTitle() + " を削除しますか？")
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> deleteRecipe(item))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Firestoreから個別のレシピを削除
     */
    private void deleteRecipe(RecipeHistory item) {
        historyRef.document(item.getId()).delete()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "レシピ「" + item.getRecipeTitle() + "」を削除しました。", Toast.LENGTH_SHORT).show();
                    // UIを更新するため再読み込み
                    loadHistory();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error deleting document: " + item.getId(), e);
                    Toast.makeText(this, "削除に失敗しました。", Toast.LENGTH_SHORT).show();
                });
    }

}
