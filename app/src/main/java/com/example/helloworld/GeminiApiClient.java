package com.example.liefantidia2;

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

/** One request per screen; failure never emits a successful completion. */
public class GeminiApiClient {
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final String endpoint;
    private Call activeCall;

    public GeminiApiClient() {
        this(new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS).build(), API_URL);
    }

    GeminiApiClient(OkHttpClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public interface RecipeCallback {
        void onResult(String result);
        void onComplete();
        void onFailure(String error);
    }

    public void cancel() {
        if (activeCall != null) activeCall.cancel();
    }

    public void generateRecipe(String apiKey, String ingredients, String constraints, RecipeCallback callback) {
        cancel();
        try {
            String prompt = "あなたはCookAi（コークアイ）、家庭料理のアシスタントです。日本語でレシピを1つ提案してください。\n"
                    + "最初の行は料理名。人数（未指定なら2人分）、調理時間、材料と分量、番号付き手順、予算の目安を記載してください。\n"
                    + "追加購入が必要な食材は区別し、価格は地域により変わる概算と明記してください。\n"
                    + "アレルギー・食事制限を優先し、満たせない条件は説明してください。肉・魚の加熱と衛生に配慮してください。\n"
                    + "利用食材: " + ingredients + "\n条件: " + constraints;
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(
                    new JSONObject().put("role", "user").put("parts", new JSONArray().put(
                            new JSONObject().put("text", prompt)))));
            body.put("generationConfig", new JSONObject().put("temperature", 0.7));
            Request request = new Request.Builder().url(endpoint)
                    .header("x-goog-api-key", apiKey)
                    .post(RequestBody.create(body.toString(), JSON)).build();
            activeCall = client.newCall(request);
            activeCall.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    if (!call.isCanceled()) callback.onFailure("通信できませんでした。接続を確認して、もう一度お試しください。");
                }
                @Override public void onResponse(Call call, Response response) {
                    try (Response closeable = response) {
                        if (call.isCanceled()) return;
                        if (!response.isSuccessful()) {
                            callback.onFailure(httpError(response.code()));
                            return;
                        }
                        if (response.body() == null) throw new JSONException("Empty body");
                        String recipe = parseRecipeFromResponse(response.body().string());
                        if (call.isCanceled()) return;
                        callback.onResult(recipe);
                        callback.onComplete();
                    } catch (IOException | JSONException error) {
                        if (!call.isCanceled()) callback.onFailure("レシピを取得できませんでした。条件を見直して再試行してください。");
                    }
                }
            });
        } catch (JSONException | IllegalArgumentException error) {
            callback.onFailure("リクエストを作成できませんでした。APIキーと入力を確認してください。");
        }
    }

    static String httpError(int code) {
        if (code == 400 || code == 401 || code == 403) return "APIキーまたは利用権限を確認してください（" + code + "）。";
        if (code == 429) return "利用上限に達しました。時間をおくか、APIの割り当てを確認してください（429）。";
        if (code >= 500) return "AIサービスが混み合っています。時間をおいて再試行してください（" + code + "）。";
        return "AIサービスに接続できませんでした（" + code + "）。";
    }

    static String parseRecipeFromResponse(String body) throws JSONException {
        JSONObject json = new JSONObject(body);
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) throw new JSONException("No candidates");
        JSONObject candidate = candidates.getJSONObject(0);
        if (!"STOP".equals(candidate.optString("finishReason"))) throw new JSONException("Incomplete response");
        JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (!part.optBoolean("thought", false)) text.append(part.optString("text", ""));
        }
        String recipe = text.toString().trim();
        if (recipe.isEmpty()) throw new JSONException("Empty recipe");
        return recipe;
    }
}
