package com.example.liefantidia2;

import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloudflare Worker 経由でレシピ生成を行うクライアント。
 * LLMプロバイダの秘密鍵はAndroidアプリに持たせず、Worker側で管理する。
 */
public class AiProxyClient {

    private static final String TAG = "AiProxyClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String proxyUrl;

    public AiProxyClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        proxyUrl = BuildConfig.AI_PROXY_URL == null ? "" : BuildConfig.AI_PROXY_URL.trim();
    }

    public boolean isConfigured() {
        return proxyUrl.startsWith("https://") && !proxyUrl.contains("example.workers.dev");
    }

    public interface RecipeCallback {
        void onResult(String result);
        void onComplete();
        void onFailure(String error);
    }

    public void generateRecipe(
            FirebaseUser user,
            String ingredientsWithUsage,
            String allConstraints,
            RecipeCallback callback
    ) {
        if (!isConfigured()) {
            callback.onFailure("AIサーバーURLが未設定です。");
            callback.onComplete();
            return;
        }

        if (user == null) {
            callback.onFailure("Firebase認証が完了していません。");
            callback.onComplete();
            return;
        }

        user.getIdToken(false).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null || TextUtils.isEmpty(task.getResult().getToken())) {
                callback.onFailure("認証トークンを取得できませんでした。");
                callback.onComplete();
                return;
            }

            try {
                JSONObject bodyJson = new JSONObject();
                bodyJson.put("ingredients", ingredientsWithUsage);
                bodyJson.put("constraints", allConstraints);

                Request request = new Request.Builder()
                        .url(proxyUrl)
                        .header("Authorization", "Bearer " + task.getResult().getToken())
                        .header("Accept", "application/json")
                        .post(RequestBody.create(bodyJson.toString(), JSON))
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e(TAG, "Worker request failed", e);
                        callback.onFailure("ネットワークエラー: " + e.getMessage());
                        callback.onComplete();
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (Response safeResponse = response) {
                            String responseBody = safeResponse.body() == null ? "" : safeResponse.body().string();

                            if (!safeResponse.isSuccessful()) {
                                callback.onFailure(parseError(responseBody, safeResponse.code()));
                                return;
                            }

                            JSONObject json = new JSONObject(responseBody);
                            String recipe = json.optString("recipe", "").trim();
                            if (recipe.isEmpty()) {
                                callback.onFailure("AIから有効なレシピが返りませんでした。");
                                return;
                            }

                            callback.onResult(recipe);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to parse Worker response", e);
                            callback.onFailure("AIレスポンスの処理に失敗しました。");
                        } finally {
                            callback.onComplete();
                        }
                    }
                });
            } catch (JSONException e) {
                callback.onFailure("リクエストの作成に失敗しました。");
                callback.onComplete();
            }
        });
    }

    private String parseError(String responseBody, int statusCode) {
        try {
            JSONObject json = new JSONObject(responseBody);
            String error = json.optString("error", "").trim();
            if (!error.isEmpty()) {
                return error;
            }
        } catch (Exception ignored) {
            // Fall through to a stable user-facing message.
        }
        return "AIサーバーエラー (HTTP " + statusCode + ")";
    }
}
