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
 * レシピ履歴をFirebase Firestoreに保存・管理するためのユーティリティクラス。
 */
public class HistoryManager {

    private static final String TAG = "HistoryManager";
    
    // Firestoreのパス構造: /artifacts/{appId}/users/{userId}/history/{documentId}
    private static final String FIRESTORE_PATH_FORMAT = "artifacts/%s/users/%s/history";

    private final FirebaseFirestore db;
    private final String userId;
    private final String appId;

    public HistoryManager(Context context) {
        this.db = FirebaseFirestore.getInstance();
        this.appId = getAppId(context);
        
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        this.userId = user != null ? user.getUid() : "anonymous_user"; 
        
        Log.d(TAG, "HistoryManager initialized for App ID: " + appId + ", User ID: " + userId);
    }
    
    /**
     * 環境変数からApp IDを取得するユーティリティメソッド。
     * @param context コンテキスト
     * @return App ID文字列
     */
    public static String getAppId(Context context) {
        // Android環境では、環境変数は直接取得できないため、ここではリソースから取得する、
        // またはコンパイル時に注入された値（今回の場合はダミーまたは既存の手段）を使用する。
        // ここでは、一般的なダミーIDまたはハードコードされたIDを使用します。
        // NOTE: Canvas環境では __app_id が利用可能ですが、Androidでは静的なリソースとして扱うのが一般的です。
        try {
             // 実際にはManifestやBuildConfigから取得しますが、ここではリテラルを使用
             return context.getString(R.string.app_name).toLowerCase();
        } catch (Exception e) {
             return "liefantidia2-default";
        }
    }

    /**
     * Firestoreのコレクションリファレンスを取得する
     */
    private CollectionReference getHistoryCollection() {
        String path = String.format(FIRESTORE_PATH_FORMAT, appId, userId);
        return db.collection(path);
    }

    /**
     * 生成されたレシピを履歴に保存する
     */
    public void saveRecipe(String ingredientsWithUsage, String allConstraints, String recipeContent) {
        // レシピ本文からタイトルを抽出
        String recipeTitle = extractTitleFromRecipe(recipeContent);
        
        RecipeHistory history = new RecipeHistory();
        history.setRecipeTitle(recipeTitle);
        history.setIngredientsWithUsage(ingredientsWithUsage);
        history.setAllConstraints(allConstraints);
        history.setRecipeContent(recipeContent);
        history.setTimestamp(new Date().getTime());

        // Firestoreに保存
        getHistoryCollection().add(history)
                .addOnSuccessListener(documentReference -> {
                    Log.i(TAG, "Recipe history saved successfully with ID: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving recipe history", e);
                });
    }
    
    /**
     * レシピ本文からタイトルを抽出するヘルパーメソッド
     */
    private String extractTitleFromRecipe(String recipeContent) {
        if (recipeContent == null || recipeContent.isEmpty()) {
            return "無題のレシピ";
        }

        // Markdownのタイトル (例: # レシピ名) または最初の行を探す
        Pattern pattern = Pattern.compile("^[#*]?[\\s]*([^\\n]+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(recipeContent);
        
        if (matcher.find()) {
            String title = matcher.group(1).trim();
            // Markdown記号を削除し、長すぎる場合は切り詰める
            title = title.replaceAll("^[#*\\s]+", "").trim();
            if (title.length() > 50) {
                 return title.substring(0, 50) + "...";
            }
            return title;
        }

        // 抽出できなかった場合は最初の10文字
        if (recipeContent.length() > 10) {
            return recipeContent.substring(0, 10) + "...";
        }
        return "無題のレシピ";
    }
}