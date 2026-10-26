package com.example.liefantidia2;

import java.util.Locale;
import java.util.regex.Pattern;

/** UI guard for unmistakable non-food and question inputs, not a food-safety classifier. */
public final class FoodInputValidator {
    private static final Pattern QUESTION = Pattern.compile("[?？]|(?:とは|どこ|いつ|なぜ|何(?:ですか|なの|が)|教えて|答えて|高さは|身長は|面積は)");
    private static final Pattern NON_FOOD = Pattern.compile("タイヤ|輪ゴム|ギ酸メチル|プラスチック|乾電池|電池|ガソリン|灯油|漂白剤|洗剤|接着剤|殺虫剤|農薬|水銀|東京タワー", Pattern.CASE_INSENSITIVE);

    private FoodInputValidator() {}

    public static String validate(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "食べられる食材を入力してください。";
        if (QUESTION.matcher(value).find() || NON_FOOD.matcher(value).find()) {
            return "食材欄には食べられる食材だけを入力してください。質問や食品以外のものは使えません。";
        }
        return null;
    }
}
