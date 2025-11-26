package com.example.liefantidia2;

import android.util.Log;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GeminiApiClient {

    private static final String TAG = "GeminiApiClient";
    private static final String API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient client;

    public GeminiApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public interface RecipeCallback {
        void onResult(String result);
        void onComplete();
        void onFailure(String error);
    }

    public void generateRecipe(String apiKey, String ingredientsWithUsage, String allConstraints, RecipeCallback callback) {
        String prompt = buildRecipePrompt(ingredientsWithUsage, allConstraints);
        String url = API_URL_BASE + apiKey;

        try {
            String jsonBody = buildJsonBody(prompt);
            
            RequestBody body = RequestBody.create(jsonBody, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "API call failed: " + e.getMessage());
                    callback.onFailure("ネットワークエラー: " + e.getMessage());
                    callback.onComplete();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body().string();
                            Log.e(TAG, "API call unsuccessful: " + response.code() + ", Body: " + errorBody);
                            callback.onFailure("APIエラー: " + response.code() + " - " + parseApiError(errorBody));
                            return;
                        }

                        String responseBody = response.body().string();
                        String recipe = parseRecipeFromResponse(responseBody);
                        callback.onResult(recipe);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing API response: " + e.getMessage());
                        callback.onFailure("レスポンス処理エラー: " + e.getMessage());
                    } finally {
                        callback.onComplete();
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error building JSON body: " + e.getMessage());
            callback.onFailure("内部エラー: JSON構築失敗");
            callback.onComplete();
        }
    }

    private String buildRecipePrompt(String ingredientsWithUsage, String allConstraints) {
        return String.format(
            "以下の情報に基づいて、実用的で美味しいレシピを日本語で提案してください。\n" +
            "結果は、レシピ名、材料、手順の3つのセクションに分けて、Markdown形式で整形してください。\n" +
            "---情報---\n" +
            "利用食材: %s\n" +
            "全ての制約: %s\n" +
            "---レシピ要件---\n" +
            "上記の「全ての制約」を最大限満たすようにしてください。特に【最重要指示】がある場合はそれを最優先してください。\n",
            ingredientsWithUsage, allConstraints);
    }

    private String buildJsonBody(String prompt) throws JSONException {
        JSONObject content = new JSONObject();
        content.put("role", "user");
        
        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
        
        JSONArray parts = new JSONArray();
        parts.put(textPart);
        
        JSONArray contents = new JSONArray();
        JSONObject contentObject = new JSONObject();
        contentObject.put("role", "user");
        contentObject.put("parts", parts);
        contents.put(contentObject);
        
        JSONObject config = new JSONObject();
        config.put("temperature", 0.9); 
        
        JSONObject json = new JSONObject();
        json.put("contents", contents);
        json.put("config", config);
        
        return json.toString();
    }

    private String parseRecipeFromResponse(String responseBody) throws JSONException {
        JSONObject json = new JSONObject(responseBody);
        
        if (json.has("candidates")) {
            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    JSONObject part = parts.getJSONObject(0);
                    return part.getString("text");
                }
            }
        }
        return "AIからのレスポンスが空でした。";
    }
    
    private String parseApiError(String errorBody) {
        try {
            JSONObject json = new JSONObject(errorBody);
            if (json.has("error")) {
                JSONObject error = json.getJSONObject("error");
                if (error.has("message")) {
                    return error.getString("message");
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing API error: " + e.getMessage());
        }
        return "詳細不明 (サーバーはエラーを返しました)";
    }
}
