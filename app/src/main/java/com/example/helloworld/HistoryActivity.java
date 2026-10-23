package com.example.liefantidia2;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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

public class HistoryActivity extends AppCompatActivity implements HistoryAdapter.HistoryActionListener {

    private static final String TAG = "HistoryActivity";
    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private TextView emptyHistoryText;
    private Button clearAllButton;

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
        if (savedInstanceState != null) pendingExport = savedInstanceState.getString("pendingExport");

        recyclerView = findViewById(R.id.recycler_view_history);
        emptyHistoryText = findViewById(R.id.text_empty_history);
        clearAllButton = findViewById(R.id.button_clear_history);
        ImageButton backButton = findViewById(R.id.button_back);

        db = FirebaseFirestore.getInstance();
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

        backButton.setOnClickListener(v -> finish());
        clearAllButton.setOnClickListener(v -> showClearHistoryConfirmation());

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
                    List<RecipeHistory> historyList = new ArrayList<>();
                    for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                        RecipeHistory history = document.toObject(RecipeHistory.class);
                        history.setId(document.getId());
                        historyList.add(history);
                    }
                    updateUi(historyList);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error loading history: ", e);
                    Toast.makeText(this, "履歴の読み込みに失敗しました。", Toast.LENGTH_SHORT).show();
                    emptyHistoryText.setText("履歴を読み込めませんでした。タップして再試行");
                    emptyHistoryText.setVisibility(View.VISIBLE);
                    emptyHistoryText.setOnClickListener(v -> loadHistory());
                });
    }

    /**
     * 履歴リストに基づいてUI（RecyclerViewと空メッセージ）を更新
     */
    private void updateUi(List<RecipeHistory> historyList) {
        emptyHistoryText.setText(R.string.history_empty_message);
        emptyHistoryText.setOnClickListener(null);
        if (historyList.isEmpty()) {
            emptyHistoryText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            clearAllButton.setEnabled(false);
        } else {
            emptyHistoryText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            clearAllButton.setEnabled(true);
        }
        adapter.updateList(historyList);
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

    /**
     * 全て削除の確認ダイアログを表示
     */
    private void showClearHistoryConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_history_title)
                .setMessage(R.string.dialog_clear_history_message)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> clearAllHistory())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Firestoreから全ての履歴を削除
     */
    private void clearAllHistory() {
        clearAllButton.setEnabled(false);
        deleteNextBatch();
    }

    private void deleteNextBatch() {
        historyRef.limit(400).get().addOnSuccessListener(documents -> {
            if (documents.isEmpty()) {
                Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show();
                loadHistory();
                return;
            }
            db.runBatch(batch -> {
                for (QueryDocumentSnapshot document : documents) batch.delete(document.getReference());
            }).addOnSuccessListener(unused -> deleteNextBatch())
              .addOnFailureListener(error -> deletionFailed());
        }).addOnFailureListener(error -> deletionFailed());
    }

    private void deletionFailed() {
        clearAllButton.setEnabled(true);
        Toast.makeText(this, "削除を完了できませんでした。残っている履歴を確認して再試行してください。", Toast.LENGTH_LONG).show();
        loadHistory();
    }
}
