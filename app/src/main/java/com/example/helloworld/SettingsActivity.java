package com.example.liefantidia2;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * AI接続情報を表示する画面。
 * LLMのAPIキーは端末ではなくCloudflare Worker側で管理する。
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        View backButton = findViewById(R.id.button_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        TextView status = findViewById(R.id.text_ai_status);
        TextView endpoint = findViewById(R.id.text_ai_endpoint);

        AiProxyClient client = new AiProxyClient();
        if (status != null) {
            status.setText(client.isConfigured()
                    ? "AIクラウド接続: 設定済み"
                    : "AIクラウド接続: 未設定");
        }

        if (endpoint != null) {
            endpoint.setText(client.isConfigured()
                    ? "ユーザーによるAPIキー入力は不要です。\nGroq / Gemini / Workers AI はサーバー側で自動切り替えされます。"
                    : "AI_PROXY_URL をCloudflare WorkerのURLに設定してください。");
        }
    }
}
