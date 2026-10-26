package com.example.liefantidia2;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * レシピ履歴をFirebase Firestoreに保存・管理するユーティリティクラス。
 *
 * Firestoreのパス構造: /artifacts/{appId}/users/{userId}/history/{documentId}
 * このクラスは、このパス構造に従って履歴を保存します。
 */
public class HistoryManager {

    private static final String TAG = "HistoryManager";

    private static final String FIRESTORE_PATH_FORMAT = "artifacts/%s/users/%s/history";

    private final android.content.Context context;
    private final FirebaseFirestore db;
    private final String userId;
    private final String appId;

    /**
     * HistoryManagerを初期化し、FirestoreインスタンスとユーザーIDを設定します。
     * @param context アプリケーションコンテキスト
     */
    public HistoryManager(Context context) {
        // Firestoreインスタンスはシングルトンで取得
        this.context = context.getApplicationContext();
        this.db = FirebaseFirestore.getInstance("default");
        this.appId = getAppId(context);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        // ユーザーが認証済みであればUID、そうでなければ匿名ユーザーとして設定
        if (user == null) throw new IllegalStateException("Authentication required for history");
        this.userId = user.getUid();

    }

    /**
     * App IDを取得するユーティリティメソッド。
     */
    public static String getAppId(Context context) {
        // Stable storage namespace: changing the display name must not orphan existing history.
        return "liefantidia2";
    }

    /**
     * Firestoreのコレクションリファレンスを取得します。
     */
    private CollectionReference getHistoryCollection() {
        String path = String.format(FIRESTORE_PATH_FORMAT, appId, userId);
        return db.collection(path);
    }

    /**
     * 生成されたレシピを履歴に保存します。
     * @param ingredientsWithUsage 食材と使用に関する制約
     * @param allConstraints その他の詳細な制約
     * @param recipeContent Geminiによって生成されたレシピ本文
     */
    public void saveRecipe(String ingredientsWithUsage, String allConstraints, String recipeContent) {
        String recipeTitle = extractTitleFromRecipe(recipeContent);

        RecipeHistory history = new RecipeHistory();
        history.setRecipeTitle(recipeTitle);
        history.setIngredientsWithUsage(ingredientsWithUsage);
        history.setAllConstraints(allConstraints);
        history.setRecipeContent(recipeContent);
        history.setTimestamp(new Date().getTime()); // 現在時刻をミリ秒で保存

        // Firestoreへの追加処理
        getHistoryCollection().add(history)
                .addOnSuccessListener(docRef -> Log.i(TAG, "Recipe saved successfully. Doc ID: " + docRef.getId()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving recipe to Firestore", e);
                    android.widget.Toast.makeText(context, "履歴を保存できませんでした。必要なレシピはコピーして保存してください。", android.widget.Toast.LENGTH_LONG).show();
                });
    }

    /**
     * レシピ本文からタイトルを抽出します。
     * 最初の非空行を検索し、Markdownの記号（#や*）を除去します。
     * @param recipeContent レシピ本文
     * @return 抽出されたタイトル
     */
    private String extractTitleFromRecipe(String recipeContent) {
        if (recipeContent == null || recipeContent.isEmpty()) {
            return "無題のレシピ";
        }

        // 行頭にある#または*、それに続くスペースを無視して最初の非空行を抽出するパターン
        // ^[#*]?[\\s]*([^\\n]+)
        Pattern pattern = Pattern.compile("^[#*]?[\\s]*([^\\n]+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(recipeContent.trim()); // trim()で全体からスペースを除去

        if (matcher.find()) {
            String title = matcher.group(1).trim();
            // 抽出された文字列から、行頭のMarkdown記号やスペースを再度除去
            title = title.replaceAll("^[#*\\s]+", "").trim();
            
            // タイトルが空でないことを確認
            if (title.isEmpty()) {
                return recipeContent.length() > 10 ? recipeContent.substring(0, 10) + "..." : "無題のレシピ";
            }
            
            // 長すぎるタイトルをクリップ
            return title.length() > 50 ? title.substring(0, 50) + "..." : title;
        }

        // パターンマッチに失敗した場合（極端に短いコンテンツなど）
        return recipeContent.length() > 10 ? recipeContent.substring(0, 10) + "..." : "無題のレシピ";
    }
}
