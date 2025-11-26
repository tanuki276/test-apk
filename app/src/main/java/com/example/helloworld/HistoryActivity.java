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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        recyclerView = findViewById(R.id.recycler_view_history);
        emptyHistoryText = findViewById(R.id.text_empty_history);
        clearAllButton = findViewById(R.id.button_clear_history);
        ImageButton backButton = findViewById(R.id.button_back);

        db = FirebaseFirestore.getInstance();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        // ユーザーIDは認証が完了しているはず
        userId = user != null ? user.getUid() : "anonymous_user"; 
        
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
                    updateUi(new ArrayList<>()); // 失敗時もUIを更新して空表示
                });
    }
    
    /**
     * 履歴リストに基づいてUI（RecyclerViewと空メッセージ）を更新
     */
    private void updateUi(List<RecipeHistory> historyList) {
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
        historyRef.get().addOnSuccessListener(queryDocumentSnapshots -> {
            if (queryDocumentSnapshots.isEmpty()) {
                Toast.makeText(this, "削除する履歴はありません。", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // バッチ処理で全てのドキュメントを削除
            db.runBatch(batch -> {
                for (QueryDocumentSnapshot snapshot : queryDocumentSnapshots) {
                    batch.delete(snapshot.getReference());
                }
            }).addOnSuccessListener(aVoid -> {
                Toast.makeText(this, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show();
                loadHistory(); // UIを更新
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Error clearing all history: ", e);
                Toast.makeText(this, "全ての履歴の削除に失敗しました。", Toast.LENGTH_SHORT).show();
            });
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Error fetching history for deletion: ", e);
            Toast.makeText(this, "履歴取得中にエラーが発生しました。", Toast.LENGTH_SHORT).show();
        });
    }
}