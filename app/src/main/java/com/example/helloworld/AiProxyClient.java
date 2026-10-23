package com.example.liefantidia2;

import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Cloudflare Worker経由でレシピ生成を行うクライアント。 */
public class AiProxyClient {

    private static final String TAG = "AiProxyClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String proxyUrl;
    private final AtomicLong generation = new AtomicLong(0);
    private volatile Call activeCall;

    public AiProxyClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
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

    public void cancel() {
        generation.incrementAndGet();
        Call call = activeCall;
        activeCall = null;
        if (call != null) call.cancel();
    }

    public void generateRecipe(
            FirebaseUser user,
            String ingredientsWithUsage,
            String allConstraints,
            RecipeCallback callback
    ) {
        cancel();
        final long requestId = generation.incrementAndGet();

        if (!isConfigured()) {
            failAndComplete(requestId, callback, "AIサーバーURLが未設定です。");
            return;
        }
        if (user == null) {
            failAndComplete(requestId, callback, "Firebase認証が完了していません。");
            return;
        }

        user.getIdToken(false).addOnCompleteListener(task -> {
            if (!isCurrent(requestId)) return;
            if (!task.isSuccessful() || task.getResult() == null || TextUtils.isEmpty(task.getResult().getToken())) {
                failAndComplete(requestId, callback, "認証トークンを取得できませんでした。");
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

                Call call = client.newCall(request);
                if (!isCurrent(requestId)) {
                    call.cancel();
                    return;
                }
                activeCall = call;
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call failedCall, IOException e) {
                        clearActive(failedCall);
                        if (!isCurrent(requestId) || failedCall.isCanceled()) return;
                        Log.e(TAG, "Worker request failed", e);
                        callback.onFailure("通信できませんでした。接続を確認して、もう一度お試しください。");
                        callback.onComplete();
                    }

                    @Override
                    public void onResponse(Call completedCall, Response response) {
                        try (Response safeResponse = response) {
                            if (!isCurrent(requestId)) return;
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
                            if (isCurrent(requestId)) callback.onResult(recipe);
                        } catch (Exception e) {
                            if (isCurrent(requestId)) {
                                Log.e(TAG, "Failed to process Worker response", e);
                                callback.onFailure("AIレスポンスの処理に失敗しました。");
                            }
                        } finally {
                            clearActive(completedCall);
                            if (isCurrent(requestId)) callback.onComplete();
                        }
                    }
                });
            } catch (JSONException | IllegalArgumentException e) {
                failAndComplete(requestId, callback, "リクエストの作成に失敗しました。");
            }
        });
    }

    private boolean isCurrent(long requestId) {
        return generation.get() == requestId;
    }

    private void clearActive(Call call) {
        if (activeCall == call) activeCall = null;
    }

    private void failAndComplete(long requestId, RecipeCallback callback, String message) {
        if (!isCurrent(requestId)) return;
        callback.onFailure(message);
        callback.onComplete();
    }

    private String parseError(String responseBody, int statusCode) {
        try {
            JSONObject json = new JSONObject(responseBody);
            String error = json.optString("error", "").trim();
            if (!error.isEmpty()) return error;
        } catch (Exception ignored) {
            // Stable fallback below.
        }
        return "AIサーバーエラー (HTTP " + statusCode + ")";
    }
}
