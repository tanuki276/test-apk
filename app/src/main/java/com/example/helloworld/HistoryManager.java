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
     * App IDを取得するユーティリティメソッド。
     */
    public static String getAppId(Context context) {
        try {
            return context.getString(R.string.app_name).toLowerCase();
        } catch (Exception e) {
            return "liefantidia2-default";
        }
    }

    /**
     * Firestoreのコレクションリファレンスを取得
     */
    private CollectionReference getHistoryCollection() {
        String path = String.format(FIRESTORE_PATH_FORMAT, appId, userId);
        return db.collection(path);
    }

    /**
     * 生成されたレシピを履歴に保存
     */
    public void saveRecipe(String ingredientsWithUsage, String allConstraints, String recipeContent) {
        String recipeTitle = extractTitleFromRecipe(recipeContent);

        RecipeHistory history = new RecipeHistory();
        history.setRecipeTitle(recipeTitle);
        history.setIngredientsWithUsage(ingredientsWithUsage);
        history.setAllConstraints(allConstraints);
        history.setRecipeContent(recipeContent);
        history.setTimestamp(new Date().getTime());

        getHistoryCollection().add(history)
                .addOnSuccessListener(docRef -> Log.i(TAG, "Recipe saved: " + docRef.getId()))
                .addOnFailureListener(e -> Log.e(TAG, "Error saving recipe", e));
    }

    /**
     * レシピ本文からタイトルを抽出
     */
    private String extractTitleFromRecipe(String recipeContent) {
        if (recipeContent == null || recipeContent.isEmpty()) {
            return "無題のレシピ";
        }

        Pattern pattern = Pattern.compile("^[#*]?[\\s]*([^\\n]+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(recipeContent);

        if (matcher.find()) {
            String title = matcher.group(1).trim();
            title = title.replaceAll("^[#*\\s]+", "").trim();
            return title.length() > 50 ? title.substring(0, 50) + "..." : title;
        }

        return recipeContent.length() > 10 ? recipeContent.substring(0, 10) + "..." : "無題のレシピ";
    }
}