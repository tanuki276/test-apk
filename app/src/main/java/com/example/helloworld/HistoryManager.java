package com.example.helloworld;

import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private HistoryManager historyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        historyManager = new HistoryManager(this);

    }

    private void onRecipeGenerated(String title,
                                   String ingredientsWithUsage,
                                   String allConstraints,
                                   String generatedRecipe) {

        try {
            historyManager.saveRecipe(
                    ingredientsWithUsage,
                    allConstraints,
                    generatedRecipe
            );
            Log.d("MainActivity", "Recipe saved.");
        } catch (Exception e) {
            Log.e("MainActivity", "Save failed", e);
        }
    }
}