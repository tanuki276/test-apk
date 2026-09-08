package com.example.liefantidia2;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;

/**
 * 生成済みレシピに対するコピー・共有操作をUI側だけで提供するボタン。
 * MainActivityの生成処理には依存しないため、既存ロジックを壊さず利用できる。
 */
public class RecipeActionButton extends AppCompatButton {

    public RecipeActionButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(this::handleClick);
    }

    public RecipeActionButton(Context context) {
        this(context, null);
    }

    private void handleClick(View ignored) {
        TextView recipeView = getRootView().findViewById(R.id.text_view_recipe_output);
        if (recipeView == null) {
            Toast.makeText(getContext(), "レシピ表示欄が見つかりません。", Toast.LENGTH_SHORT).show();
            return;
        }

        String recipe = recipeView.getText() == null ? "" : recipeView.getText().toString().trim();
        if (TextUtils.isEmpty(recipe)
                || recipe.contains("APIキーが設定されていません")
                || recipe.contains("レシピをAIが考案中です")
                || recipe.startsWith("エラーが発生しました")) {
            Toast.makeText(getContext(), "共有・コピーできるレシピがありません。", Toast.LENGTH_SHORT).show();
            return;
        }

        String action = getTag() == null ? "" : getTag().toString();
        if ("copy".equals(action)) {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("レシピ", recipe));
                Toast.makeText(getContext(), "レシピをコピーしました。", Toast.LENGTH_SHORT).show();
            }
        } else if ("share".equals(action)) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "AIレシピ");
            shareIntent.putExtra(Intent.EXTRA_TEXT, recipe);
            getContext().startActivity(Intent.createChooser(shareIntent, "レシピを共有"));
        }
    }
}
