package com.example.liefantidia2;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.HttpUrl;

/**
 * Gemini/GPT APIと通信し、レシピを生成するためのクライアントクラス。
 * ネットワーク処理は非同期で実行する。
 */
public class GeminiApiClient {

    private static final String TAG = "GeminiApiClient";
    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ExecutorService executor;
    private final Handler handler;
    private final OkHttpClient client;

    public GeminiApiClient() {
        executor = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        client = new OkHttpClient();
    }

    public interface RecipeCallback {
        void onNewChunk(String chunk);
        void onComplete();
        void onFailure(String error);
    }

    /**
     * ユーザーの設定に基づき、レシピ生成プロセスを開始する。
     * @param apiKey 認証済みのAPIキー
     * @param ingredients 食材リスト
     * @param difficulty 難易度設定
     * @param genre ジャンル設定
     * @param allConstraints 時間・食事制限などを含むその他の制約文字列
     * @param callback 結果をMainActivityに返すためのインターフェース
     */
    public void generateRecipe(String apiKey, String ingredients, String difficulty, String genre, String allConstraints, RecipeCallback callback) {

        executor.execute(() -> {
            Response response = null;
            try {
                // 1. プロンプト生成
                // ★修正点：allConstraintsを渡す
                String prompt = buildPrompt(ingredients, difficulty, genre, allConstraints);
                
                // 2. APIリクエストボディ (JSON) を構築
                String jsonBody = buildJsonPayload(prompt);

                // 3. APIリクエストの構築
                HttpUrl httpUrl = HttpUrl.parse(API_URL).newBuilder()
                                        .addQueryParameter("key", apiKey)
                                        .build();

                RequestBody body = RequestBody.create(jsonBody, JSON);
                Request request = new Request.Builder()
                        .url(httpUrl)
                        .post(body)
                        .header("Content-Type", "application/json")
                        .build();

                // 4. APIリクエストの実行
                response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    String errorDetail = response.body() != null ? response.body().string() : "No body";
                    throw new IOException("API呼び出しが失敗しました: " + response.code() + ", 詳細: " + errorDetail);
                }

                // 5. レスポンスのパースと結果の抽出
                String responseBody = response.body().string();
                String generatedText = parseResponse(responseBody);
                
                // 6. UIスレッドで結果を通知
                handler.post(() -> {
                    callback.onNewChunk(generatedText);
                    callback.onComplete();
                });

            } catch (Exception e) {
                String errorMessage = "API通信または処理エラー: " + e.getMessage();
                handler.post(() -> callback.onFailure(errorMessage));
                Log.e(TAG, errorMessage, e);
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        });
    }

    /**
     * ユーザー設定を統合したプロンプト文字列を生成する
     * ★修正点：allConstraintsを受け取る
     */
    private String buildPrompt(String ingredients, String difficulty, String genre, String allConstraints) {
        String expertise = "";
        String constraint = "";

        if (difficulty.contains("初心者")) {
            expertise = "料理初心者にも分かりやすい言葉で、失敗しない手順を提案するプロの料理人として振る舞ってください。";
            constraint = "手順は短く区切り、難しい調理技術は使わないでください。";
        } else if (difficulty.contains("上級者")) {
            expertise = "創造的で挑戦的なレシピを提供するミシュランのシェフとして振る舞ってください。";
            constraint = "プロの用語や高度な調理法を含めてください。";
        } else {
             expertise = "バランスの取れた中級者向けレシピを提案する家庭料理研究家として振る舞ってください。";
             constraint = "";
        }

        String prompt = "あなたはAIレシピ提案アシスタントです。"
                + expertise
                + "\n\n以下の条件と食材に基づいて、調理手順を含むレシピを一つ提案してください。"
                + "\n\n---"
                + "\n[入力食材]: " + ingredients
                + "\n[難易度]: " + difficulty
                + "\n[ジャンル]: " + genre
                + "\n[追加制約]: " + allConstraints // ★修正点：新しい制約を追加
                + "\n[その他技術制約]: " + constraint
                + "\n\n提案は、レシピ名、材料（分量付き）、調理手順の順に、Markdown形式で出力してください。"
                + "\n---";

        return prompt;
    }

    /**
     * Gemini APIに送信するためのJSONペイロードを構築する (変更なし)
     */
    private String buildJsonPayload(String prompt) {
        try {
            JSONObject contentPart = new JSONObject();
            contentPart.put("text", prompt);

            JSONArray partsArray = new JSONArray();
            partsArray.put(contentPart);

            JSONObject content = new JSONObject();
            content.put("parts", partsArray);
            content.put("role", "user");

            JSONArray contentsArray = new JSONArray();
            contentsArray.put(content);

            JSONObject payload = new JSONObject();
            payload.put("contents", contentsArray);
            payload.put("temperature", 0.7);

            return payload.toString();
        } catch (Exception e) {
            Log.e(TAG, "JSONペイロードの構築に失敗", e);
            return "{}";
        }
    }

    /**
     * APIレスポンス (JSON) から生成されたテキストを抽出する (変更なし)
     */
    private String parseResponse(String responseBody) {
        try {
            JSONObject json = new JSONObject(responseBody);
            
            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() > 0) {
                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    return parts.getJSONObject(0).getString("text");
                }
            }
            return "レシピ生成に失敗しました。APIが応答をブロックした可能性があります。";

        } catch (Exception e) {
            Log.e(TAG, "APIレスポンスのパースに失敗: " + responseBody, e);
            return "エラー: APIからの応答を読み取れませんでした。";
        }
    }
}