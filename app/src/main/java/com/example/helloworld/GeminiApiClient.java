package com.example.liefantidia;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ★外部ライブラリ（OkHttpやGemini SDK）のインポートを想定
// import okhttp3.OkHttpClient; 
// import okhttp3.Request;
// import okhttp3.Response;

/**
 * Gemini/GPT APIと通信し、レシピを生成するためのクライアントクラス。
 * ネットワーク処理は非同期で実行する。
 */
public class GeminiApiClient {

    private static final String TAG = "GeminiApiClient";
    // 外部APIのエンドポイントURL (例: Google Gemini APIのURLを想定)
    private static final String API_URL = "YOUR_GEMINI_API_URL_HERE"; 
    
    // 非同期処理用のスレッドプール
    private final ExecutorService executor;
    // UIスレッドで結果を反映させるためのハンドラ
    private final Handler handler; 
    
    // ★外部ライブラリのクライアントインスタンスを想定
    // private final OkHttpClient client;

    public GeminiApiClient() {
        // スレッドプールを初期化 (バックグラウンドでネットワークタスクを実行)
        executor = Executors.newSingleThreadExecutor();
        // UIスレッドのLooperに紐づいたハンドラ
        handler = new Handler(Looper.getMainLooper());
        // client = new OkHttpClient(); 
    }

    /**
     * レシピ生成APIと通信するためのコールバックインターフェース。
     * ストリーミングに対応するため、部分的な結果と完了/失敗を通知する。
     */
    public interface RecipeCallback {
        /**
         * レシピの新しいチャンク（断片）が到着したときに呼び出される
         * @param chunk 新しく追加されたテキスト部分
         */
        void onNewChunk(String chunk); 
        
        /**
         * レシピ生成が完全に完了したときに呼び出される
         */
        void onComplete(); 

        /**
         * エラーが発生したときに呼び出される
         * @param error エラーメッセージ
         */
        void onFailure(String error);
    }

    /**
     * ユーザーの設定に基づき、レシピ生成プロセスを開始する。
     * @param apiKey 認証済みのAPIキー
     * @param ingredients 食材リスト
     * @param difficulty 難易度設定
     * @param genre ジャンル設定
     * @param callback 結果をMainActivityに返すためのインターフェース
     */
    public void generateRecipe(String apiKey, String ingredients, String difficulty, String genre, RecipeCallback callback) {
        
        // ネットワークタスクをバックグラウンドスレッドで実行
        executor.execute(() -> {
            try {
                // 1. プロンプト生成
                String prompt = buildPrompt(ingredients, difficulty, genre);
                Log.d(TAG, "Generated Prompt: " + prompt);
                
                // 2. APIリクエストの実行（ストリーミングを想定）
                // ★この部分は、実際のGemini/GPT SDKの使用に合わせて大幅に変更が必要です。
                // 以下のロジックは、HTTP通信の抽象的な流れを示すものです。

                // Simulating Network Delay and Streaming
                simulateStreamingResponse(callback); 

                // ★本来の処理のイメージ：
                /*
                Request request = buildApiRequest(apiKey, prompt);
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Unexpected code " + response);
                    }
                    // ストリーム処理: 応答をチャンク（断片）に分割して処理
                    // while ((chunk = stream.readChunk()) != null) {
                    //     handler.post(() -> callback.onNewChunk(chunk));
                    // }
                }
                */

            } catch (Exception e) {
                // UIスレッドでエラーを通知
                String errorMessage = "API通信エラー: " + e.getMessage();
                handler.post(() -> callback.onFailure(errorMessage));
                Log.e(TAG, errorMessage, e);
            }
        });
    }
    
    /**
     * ユーザー設定を統合したプロンプト文字列を生成する
     * @param ingredients 食材リスト
     * @param difficulty 難易度設定 (例: "難易度: 初心者 (簡単)")
     * @param genre ジャンル設定 (例: "ジャンル: 和食")
     * @return LLMに送信するための最終的なプロンプト
     */
    private String buildPrompt(String ingredients, String difficulty, String genre) {
        // 難易度に基づいたペルソナや制約の調整
        String expertise = "";
        String constraint = "";
        
        if (difficulty.contains("初心者")) {
            expertise = "料理初心者にも分かりやすい言葉で、失敗しない手順を提案するプロの料理人として振る舞ってください。";
            constraint = "手順は短く区切り、難しい調理技術は使わないでください。";
        } else if (difficulty.contains("上級者")) {
            expertise = "創造的で挑戦的なレシピを提供するミシュランのシェフとして振る舞ってください。";
            constraint = "プロの用語や高度な調理法を含めてください。";
        }
        
        // 最終的なプロンプト構造
        String prompt = "あなたはAIレシピ提案アシスタントです。"
                + expertise
                + "\n\n以下の条件と食材に基づいて、調理手順を含むレシピを一つ提案してください。"
                + "\n\n---"
                + "\n[入力食材]: " + ingredients
                + "\n[難易度]: " + difficulty
                + "\n[ジャンル]: " + genre
                + "\n[追加制約]: " + constraint // 他のSpinnerからの制約（時間、食事制限）もここに追加する
                + "\n\n提案は、レシピ名、材料（分量付き）、調理手順の順に、Markdown形式で出力してください。"
                + "\n---";
        
        return prompt;
    }

    /**
     * 【デモ用】ストリーミング応答をシミュレーションする
     */
    private void simulateStreamingResponse(RecipeCallback callback) throws InterruptedException {
        String fullRecipe = "## 絶品鶏肉と玉ねぎの照り焼き\n\n### 材料\n* 鶏もも肉: 300g\n* 玉ねぎ: 1/2個\n* 醤油: 大さじ3\n* みりん: 大さじ3\n\n### 手順\n1. 鶏肉を一口大に切ります。\n2. 玉ねぎを薄切りにします。\n3. フライパンに油をひき、鶏肉の皮目を下にして中火で焼きます。\n4. 皮に焼き色がついたら裏返し、玉ねぎを加えます。\n5. 鶏肉に火が通ったら、醤油とみりんを混ぜたタレを一気に加えます。\n6. タレを鶏肉によく絡ませ、とろみがついたら完成です。";
        String[] chunks = fullRecipe.split(" "); // スペースで区切ってチャンク化
        
        for (String chunk : chunks) {
            final String finalChunk = chunk + " ";
            // UIスレッドにチャンクを送信
            handler.post(() -> callback.onNewChunk(finalChunk));
            Thread.sleep(50); // 応答遅延をシミュレート
        }
        
        // 完了を通知
        handler.post(callback::onComplete);
    }
}
