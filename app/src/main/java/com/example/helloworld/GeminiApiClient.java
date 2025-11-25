package com.example.liefantidia2;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google Gemini APIと通信し、レシピ生成を行うクライアントクラス。
 * OkHttpライブラリを使用して非同期通信を行います。
 * (注: OkHttpはGradleファイルで依存関係に追加する必要があります)
 */
public class GeminiApiClient {

    private static final String TAG = "GeminiApiClient";
    private static final String API_URL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String MODEL_NAME = "gemini-2.5-flash-preview-09-2025";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Handler mainHandler;

    public GeminiApiClient() {
        // OkHttpClientのインスタンスは一つで十分
        this.client = new OkHttpClient();
        // UIスレッドでコールバックを実行するためのハンドラ
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * レシピ生成APIの結果を返すためのコールバックインターフェース。
     */
    public interface RecipeCallback {
        void onResult(String result); // 結果のテキストが返されたとき
        void onComplete(); // 処理が完了したとき (成功・失敗に関わらず)
        void onFailure(String error); // API呼び出しまたは処理が失敗したとき
    }

    /**
     * Gemini APIを呼び出し、指定された食材と制約に基づいてレシピを生成します。
     *
     * @param apiKey 認証済みのAPIキー
     * @param ingredients ユーザーが入力した食材
     * @param difficulty 難易度 (Spinnerからの選択値)
     * @param genre ジャンル (Spinnerからの選択値)
     * @param allConstraints その他制約 (時間、価格帯、食事制限など)
     * @param callback 結果をUIスレッドに戻すためのコールバック
     */
    public void generateRecipe(String apiKey, String ingredients, String difficulty, String genre, String allConstraints, RecipeCallback callback) {
        
        // 1. プロンプトとシステムインストラクションの構築
        String systemPrompt = "あなたはプロの料理研究家であり、AIレシピジェネレーターです。ユーザーが入力した食材と制約に基づいて、創造的かつ実用的なレシピを考案してください。出力は日本語で、以下の形式に従ってください。\n\n## [レシピ名]\n\n### 材料\n- ...\n\n### 作り方\n1. ...\n2. ...\n\n### ポイント\n- ...";
        String userQuery = String.format(
            "以下の食材と制約を使用して、レシピを一つ考案してください。\n\n食材: %s\n制約: %s, %s, %s",
            ingredients, difficulty, genre, allConstraints
        );

        // 2. APIリクエストボディの構築
        String jsonPayload;
        try {
            JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));

            JSONObject userPart = new JSONObject()
                .put("text", userQuery);

            JSONObject contents = new JSONObject()
                .put("parts", new JSONArray().put(userPart));

            JSONObject payload = new JSONObject()
                .put("contents", new JSONArray().put(contents))
                .put("systemInstruction", systemInstruction)
                // 検索グラウンディングツールを有効にする (最新情報に基づいたレシピを生成するため)
                .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));

            jsonPayload = payload.toString();
        } catch (Exception e) {
            Log.e(TAG, "JSON Payload construction failed: " + e.getMessage());
            callback.onFailure("リクエスト構築エラー: " + e.getMessage());
            callback.onComplete();
            return;
        }
        
        // 3. HTTPリクエストの構築
        String fullUrl = API_URL_BASE + MODEL_NAME + ":generateContent?key=" + apiKey;
        RequestBody body = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
            .url(fullUrl)
            .post(body)
            .build();

        // 4. APIコールの実行 (非同期)
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "API call failed: " + e.getMessage());
                // UIスレッドでエラーを通知
                mainHandler.post(() -> {
                    callback.onFailure("ネットワークエラー: " + e.getMessage());
                    callback.onComplete();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                
                if (!response.isSuccessful()) {
                    Log.e(TAG, "API call failed with code: " + response.code() + ", body: " + responseBody);
                    // UIスレッドでエラーを通知
                    mainHandler.post(() -> {
                        callback.onFailure("APIエラー (" + response.code() + "): " + responseBody);
                        callback.onComplete();
                    });
                    return;
                }

                try {
                    // 5. レスポンスのパースと結果の抽出
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    JSONArray candidates = jsonResponse.getJSONArray("candidates");
                    if (candidates.length() > 0) {
                        JSONObject candidate = candidates.getJSONObject(0);
                        String generatedText = candidate.getJSONObject("content")
                                                        .getJSONArray("parts")
                                                        .getJSONObject(0)
                                                        .getString("text");

                        // UIスレッドで結果を通知
                        mainHandler.post(() -> {
                            callback.onResult(generatedText);
                            callback.onComplete();
                        });
                    } else {
                        // UIスレッドでエラーを通知
                        mainHandler.post(() -> {
                            callback.onFailure("APIが有効なコンテンツを返しませんでした。");
                            callback.onComplete();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Response parsing failed: " + e.getMessage());
                    // UIスレッドでエラーを通知
                    mainHandler.post(() -> {
                        callback.onFailure("レスポンス解析エラー: " + e.getMessage());
                        callback.onComplete();
                    });
                }
            }
        });
    }
}