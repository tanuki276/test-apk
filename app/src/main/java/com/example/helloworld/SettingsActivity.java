package com.example.liefantidia2;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        WindowInsetsHelper.apply(this);
        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.text_version)).setText("Version " + BuildConfig.VERSION_NAME);
        findViewById(R.id.button_privacy).setOnClickListener(v -> {
            try (java.io.InputStream input = getAssets().open("privacy.txt")) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                new AlertDialog.Builder(this).setTitle("プライバシーポリシー")
                    .setMessage(output.toString("UTF-8")).setPositiveButton("閉じる", null).show();
            } catch (java.io.IOException e) {
                android.widget.Toast.makeText(this, "表示できませんでした。もう一度お試しください。", android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
