package com.example.liefantidia2;

import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Cloudflare Worker経由でレシピ生成と利用状況取得を行うクライアント。 */
public class AiProxyClient {

    private static final String TAG = "AiProxyClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long TOKEN_TIMEOUT_MS = 20_000L;
    private static final ScheduledExecutorService TOKEN_TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "cookai-token-timeout");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private final OkHttpClient client;
    private final String proxyUrl;
    private final AtomicLong generation = new AtomicLong(0);
    private volatile Call activeCall;

    public AiProxyClient() { this(BuildConfig.AI_PROXY_URL); }

    AiProxyClient(String configuredUrl) {
        client = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                .build();
        proxyUrl = configuredUrl == null ? "" : configuredUrl.trim();
    }

    public boolean isConfigured() {
        try {
            URI endpoint = new URI(proxyUrl);
            return "https".equals(endpoint.getScheme()) && endpoint.getHost() != null
                    && !"example.workers.dev".equals(endpoint.getHost())
                    && "/generate-recipe".equals(endpoint.getPath())
                    && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null;
        } catch (java.net.URISyntaxException e) { return false; }
    }

    public interface RecipeCallback {
        void onResult(String result);
        void onComplete();
        void onFailure(String error);
    }

    public static final class UsageStatus {
        public final boolean guest;
        public final int normalUsed, normalLimit, normalRemaining;
        public final boolean highAvailable;
        public final int highUsed, highLimit, highRemaining;

        UsageStatus(boolean guest, int normalUsed, int normalLimit, int normalRemaining,
                    boolean highAvailable, int highUsed, int highLimit, int highRemaining) {
            this.guest = guest;
            this.normalUsed = normalUsed;
            this.normalLimit = normalLimit;
            this.normalRemaining = normalRemaining;
            this.highAvailable = highAvailable;
            this.highUsed = highUsed;
            this.highLimit = highLimit;
            this.highRemaining = highRemaining;
        }
    }

    public interface UsageCallback {
        void onResult(UsageStatus status);
        void onFailure(String error);
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        Call call = activeCall;
        activeCall = null;
        if (call != null) call.cancel();
    }

    public void generateRecipe(FirebaseUser user, String ingredientsWithUsage,
                               String allConstraints, RecipeCallback callback) {
        generateRecipe(user, ingredientsWithUsage, allConstraints, false, callback);
    }

    public void generateRecipe(FirebaseUser user, String ingredientsWithUsage,
                               String allConstraints, boolean highAccuracy,
                               RecipeCallback callback) {
        cancel();
        final long requestId = generation.incrementAndGet();
        if (!isConfigured()) {
            failAndComplete(requestId, callback, "現在サービスを利用できません。");
            return;
        }
        if (user == null) {
            failAndComplete(requestId, callback, "認証に失敗しました。アプリを開き直してください。");
            return;
        }

        final JSONObject body = new JSONObject();
        try {
            body.put("ingredients", ingredientsWithUsage);
            body.put("constraints", allConstraints);
            body.put("mode", highAccuracy ? "high" : "normal");
        } catch (JSONException e) {
            failAndComplete(requestId, callback, "生成を開始できませんでした。もう一度お試しください。");
            return;
        }

        getToken(user, false, requestId, new TokenCallback() {
            @Override public void onToken(String token) {
                enqueueRecipe(user, token, body.toString(), requestId, callback, true);
            }
            @Override public void onFailure(String message) { failAndComplete(requestId, callback, message); }
        });
    }

    public void getUsage(FirebaseUser user, UsageCallback callback) {
        cancel();
        final long requestId = generation.incrementAndGet();
        if (!isConfigured() || user == null) {
            callback.onFailure("利用状況を取得できません。");
            return;
        }
        getToken(user, false, requestId, new TokenCallback() {
            @Override public void onToken(String token) { enqueueUsage(user, token, requestId, callback, true); }
            @Override public void onFailure(String message) {
                if (isCurrent(requestId)) callback.onFailure(message);
            }
        });
    }

    private interface TokenCallback {
        void onToken(String token);
        void onFailure(String message);
    }

    private void getToken(FirebaseUser user, boolean forceRefresh, long requestId, TokenCallback callback) {
        AtomicBoolean finished = new AtomicBoolean(false);
        ScheduledFuture<?> timeout = TOKEN_TIMEOUTS.schedule(() -> {
            if (finished.compareAndSet(false, true) && isCurrent(requestId)) {
                callback.onFailure("認証情報の取得に時間がかかっています。通信を確認してもう一度お試しください。");
            }
        }, TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        user.getIdToken(forceRefresh).addOnCompleteListener(task -> {
            if (!finished.compareAndSet(false, true)) return;
            timeout.cancel(false);
            if (!isCurrent(requestId)) return;
            if (!task.isSuccessful() || task.getResult() == null || TextUtils.isEmpty(task.getResult().getToken())) {
                callback.onFailure("認証に失敗しました。通信を確認してもう一度お試しください。");
                return;
            }
            callback.onToken(task.getResult().getToken());
        });
    }

    private void enqueueRecipe(FirebaseUser user, String token, String body, long requestId,
                               RecipeCallback callback, boolean allowRefresh) {
        final Request request;
        try {
            request = new Request.Builder()
                    .url(proxyUrl)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();
        } catch (IllegalArgumentException e) {
            failAndComplete(requestId, callback, "生成を開始できませんでした。もう一度お試しください。");
            return;
        }

        Call call = client.newCall(request);
        if (!activate(call, requestId)) return;
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failedCall, IOException e) {
                clearActive(failedCall);
                if (!isCurrent(requestId) || failedCall.isCanceled()) return;
                Log.e(TAG, "Worker request failed", e);
                callback.onFailure("通信できませんでした。接続を確認して、もう一度お試しください。");
                callback.onComplete();
            }

            @Override public void onResponse(Call completedCall, Response response) {
                if (!isCurrent(requestId)) { response.close(); return; }
                try (Response safeResponse = response) {
                    String responseBody = safeResponse.body() == null ? "" : safeResponse.body().string();
                    if (!isCurrent(requestId) || completedCall.isCanceled()) return;
                    if (safeResponse.code() == 401 && allowRefresh) {
                        getToken(user, true, requestId, new TokenCallback() {
                            @Override public void onToken(String refreshed) {
                                enqueueRecipe(user, refreshed, body, requestId, callback, false);
                            }
                            @Override public void onFailure(String message) { failAndComplete(requestId, callback, message); }
                        });
                        return;
                    }
                    if (!safeResponse.isSuccessful()) {
                        callback.onFailure(parseError(responseBody, safeResponse.code()));
                        callback.onComplete();
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    String recipe = json.optString("recipe", "").trim();
                    if (recipe.isEmpty()) callback.onFailure("AIから有効なレシピが返りませんでした。");
                    else callback.onResult(recipe);
                    callback.onComplete();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to process Worker response", e);
                    if (isCurrent(requestId)) {
                        callback.onFailure("レシピを受け取れませんでした。もう一度お試しください。");
                        callback.onComplete();
                    }
                } finally {
                    clearActive(completedCall);
                }
            }
        });
    }

    private void enqueueUsage(FirebaseUser user, String token, long requestId,
                              UsageCallback callback, boolean allowRefresh) {
        String usageUrl;
        try {
            URI endpoint = new URI(proxyUrl);
            usageUrl = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(),
                    "/usage", null, null).toString();
        } catch (Exception e) {
            callback.onFailure("利用状況を取得できません。");
            return;
        }
        Request request = new Request.Builder()
                .url(usageUrl)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .get()
                .build();
        Call call = client.newCall(request);
        if (!activate(call, requestId)) return;
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failedCall, IOException e) {
                clearActive(failedCall);
                if (isCurrent(requestId) && !failedCall.isCanceled()) callback.onFailure("利用状況を取得できません。");
            }

            @Override public void onResponse(Call completedCall, Response response) {
                if (!isCurrent(requestId)) { response.close(); return; }
                try (Response safeResponse = response) {
                    String body = safeResponse.body() == null ? "" : safeResponse.body().string();
                    if (!isCurrent(requestId) || completedCall.isCanceled()) return;
                    if (safeResponse.code() == 401 && allowRefresh) {
                        getToken(user, true, requestId, new TokenCallback() {
                            @Override public void onToken(String refreshed) {
                                enqueueUsage(user, refreshed, requestId, callback, false);
                            }
                            @Override public void onFailure(String message) { callback.onFailure(message); }
                        });
                        return;
                    }
                    if (!safeResponse.isSuccessful()) {
                        callback.onFailure(safeResponse.code() == 404
                            ? "利用状況サーバーの更新が必要です。しばらくしてから再試行してください。"
                            : parseError(body, safeResponse.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    JSONObject normal = json.optJSONObject("normal");
                    JSONObject high = json.optJSONObject("highAccuracy");
                    if (normal == null || high == null) throw new JSONException("missing usage fields");
                    callback.onResult(new UsageStatus(
                            json.optBoolean("guest", true),
                            normal.optInt("used", 0), normal.optInt("limit", 0), normal.optInt("remaining", 0),
                            high.optBoolean("available", false),
                            high.optInt("used", 0), high.optInt("limit", 0), high.optInt("remaining", 0)));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse usage", e);
                    if (isCurrent(requestId)) callback.onFailure("利用状況を取得できません。");
                } finally {
                    clearActive(completedCall);
                }
            }
        });
    }

    private boolean isCurrent(long requestId) { return generation.get() == requestId; }
    private synchronized boolean activate(Call call, long requestId) {
        if (!isCurrent(requestId)) { call.cancel(); return false; }
        activeCall = call;
        return true;
    }

    private synchronized void clearActive(Call call) { if (activeCall == call) activeCall = null; }

    private void failAndComplete(long requestId, RecipeCallback callback, String message) {
        if (!isCurrent(requestId)) return;
        callback.onFailure(message);
        callback.onComplete();
    }

    private String parseError(String responseBody, int statusCode) {
        try {
            String message = new JSONObject(responseBody).optString("error", "").trim();
            if (!message.isEmpty()) return message.length() > 220 ? message.substring(0, 220) : message;
        } catch (Exception ignored) { }
        if (statusCode == 401) return "認証に失敗しました。ログイン状態を確認して再試行してください。";
        if (statusCode == 403) return "この機能は現在のアカウントでは利用できません。";
        if (statusCode == 429) return "本日の利用上限に達したか、短時間に連続して生成しています。";
        if (statusCode >= 500) return "ただいま混み合っています。しばらくしてからもう一度お試しください。";
        if (statusCode == 400 || statusCode == 413) return "入力内容が長すぎるか、正しくありません。食材と条件を確認してください。";
        return "生成できませんでした。もう一度お試しください。";
    }
}
