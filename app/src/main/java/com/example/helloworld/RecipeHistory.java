package com.example.liefantidia2;

import java.io.Serializable;

/**
 * レシピ履歴をFirestoreまたはローカルに保存するためのデータモデル。
 * インテント経由での受け渡しのためSerializableを実装。
 */
public class RecipeHistory implements Serializable {

    // FirestoreのドキュメントIDとして使用
    private String id;
    
    // UI表示用
    private String recipeTitle; 

    // レシピ生成時に使用したプロンプトの情報
    private String ingredientsWithUsage;
    private String allConstraints;

    // AIからのレスポンス（レシピ本文）
    private String recipeContent;

    // 生成日時 (UI表示およびソート用)
    private long timestamp;
    
    // Firestoreでの保存を容易にするための空のコンストラクタ
    public RecipeHistory() {
        // デフォルトコンストラクタ
    }

    public RecipeHistory(String id, String recipeTitle, String ingredientsWithUsage, String allConstraints, String recipeContent, long timestamp) {
        this.id = id;
        this.recipeTitle = recipeTitle;
        this.ingredientsWithUsage = ingredientsWithUsage;
        this.allConstraints = allConstraints;
        this.recipeContent = recipeContent;
        this.timestamp = timestamp;
    }

    // --- Getter and Setter ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRecipeTitle() {
        return recipeTitle;
    }

    public void setRecipeTitle(String recipeTitle) {
        this.recipeTitle = recipeTitle;
    }

    public String getIngredientsWithUsage() {
        return ingredientsWithUsage;
    }

    public void setIngredientsWithUsage(String ingredientsWithUsage) {
        this.ingredientsWithUsage = ingredientsWithUsage;
    }

    public String getAllConstraints() {
        return allConstraints;
    }

    public void setAllConstraints(String allConstraints) {
        this.allConstraints = allConstraints;
    }

    public String getRecipeContent() {
        return recipeContent;
    }

    public void setRecipeContent(String recipeContent) {
        this.recipeContent = recipeContent;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}