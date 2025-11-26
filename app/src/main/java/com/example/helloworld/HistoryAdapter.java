package com.example.liefantidia2;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast; // ★ 追加: Toastを使用できるようにする

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * レシピ履歴リスト用のRecyclerViewアダプタ。
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    private final List<RecipeHistory> historyList;
    private final HistoryActionListener listener;
    private final Context context;

    public interface HistoryActionListener {
        void onDeleteClicked(RecipeHistory item);
        void onShowClicked(RecipeHistory item);
        // ダウンロードはHistoryActivityで処理するため、ここでは不要だが将来的に追加可能
    }

    public HistoryAdapter(Context context, List<RecipeHistory> historyList, HistoryActionListener listener) {
        this.context = context;
        this.historyList = historyList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        RecipeHistory item = historyList.get(position);

        holder.titleText.setText(item.getRecipeTitle());
        holder.ingredientsText.setText(buildConstraintSummary(item));
        holder.dateText.setText(formatTimestamp(item.getTimestamp()));

        // 個別削除ボタン
        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClicked(item);
            }
        });

        // 再表示ボタン
        holder.showButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onShowClicked(item);
            }
        });

        // ダウンロードボタン (現在はダミー。HistoryActivityで実装予定)
        holder.downloadButton.setOnClickListener(v -> {
            Toast.makeText(context, "ダウンロード機能は実装中です。", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public int getItemCount() {
        return historyList.size();
    }

    /**
     * リストを更新してUIを再描画する
     */
    public void updateList(List<RecipeHistory> newList) {
        historyList.clear();
        historyList.addAll(newList);
        notifyDataSetChanged();
    }

    /**
     * Firestoreのタイムスタンプ (long) を整形
     */
    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * 制約情報から簡潔なサマリー文字列を生成
     */
    private String buildConstraintSummary(RecipeHistory item) {
        StringBuilder sb = new StringBuilder();

        // 具材情報
        String ingredients = item.getIngredientsWithUsage();
        if (!TextUtils.isEmpty(ingredients)) {
            // " (入力された具材は全て使用してください)" のような指示部分を削除して表示
            int index = ingredients.indexOf(" (");
            String displayIngredients = (index > 0) ? ingredients.substring(0, index) : ingredients;
            sb.append("具材: ").append(displayIngredients);
        }

        // 制約情報
        String constraints = item.getAllConstraints();
        if (!TextUtils.isEmpty(constraints)) {
            // 主食の分類、難易度、ジャンル、調理時間、食事制限、価格帯などを含む文字列。
            // 自由指示（最重要指示）は長くなるので省略
            int importantIndex = constraints.indexOf("【最重要指示】");
            String displayConstraints = (importantIndex > 0) ? constraints.substring(0, importantIndex).trim() : constraints.trim();

            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("設定: ").append(displayConstraints);
        }

        // 長すぎる場合は省略
        String summary = sb.toString();
        if (summary.length() > 80) {
            return summary.substring(0, 77) + "...";
        }
        return summary;
    }

    public static class HistoryViewHolder extends RecyclerView.ViewHolder {
        final TextView titleText;
        final TextView dateText;
        final TextView ingredientsText;
        final ImageButton deleteButton;
        final Button showButton;
        final Button downloadButton;

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.text_recipe_title);
            dateText = itemView.findViewById(R.id.text_recipe_date);
            ingredientsText = itemView.findViewById(R.id.text_recipe_ingredients);
            deleteButton = itemView.findViewById(R.id.button_delete_recipe);
            showButton = itemView.findViewById(R.id.button_show_recipe);
            downloadButton = itemView.findViewById(R.id.button_download);
        }
    }
}