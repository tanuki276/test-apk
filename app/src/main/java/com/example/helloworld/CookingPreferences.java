package com.example.liefantidia2;

/** Immutable cooking defaults. Empty means unspecified; an explicit "none" is preserved. */
public final class CookingPreferences {
    public final String allergies, seasonings, methods;
    public CookingPreferences(String allergies, String seasonings, String methods) {
        this.allergies = clean(allergies);
        this.seasonings = clean(seasonings);
        this.methods = clean(methods);
    }
    private static String clean(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400);
    }
    public String prompt() {
        StringBuilder result = new StringBuilder();
        result.append("\n【基本条件】入力された食材で作り、未入力の食材を必須材料として追加しないでください。");
        if (!seasonings.isEmpty()) result.append("\n【使える調味料】").append(seasonings)
            .append("。ここに記載された調味料だけを使用し、他の調味料を追加しないでください。");
        if (!methods.isEmpty()) result.append("\n【使える調理方法】").append(methods)
            .append("。記載された方法・器具の範囲で調理してください。");
        if (!allergies.isEmpty()) result.append("\n【最優先・避ける食材とアレルギー】").append(allergies)
            .append("。これらを含む食材・調味料は使わないでください。食材を全部使う指定や追加指示と矛盾する場合も、この除外条件を優先してください。条件を満たせない場合は無理に料理を提案せず理由を伝えてください。");
        return result.toString();
    }
    public String summary() {
        if (allergies.isEmpty() && seasonings.isEmpty() && methods.isEmpty()) return "未登録 · 設定すると次回から入力不要";
        return "アレルギー " + (allergies.isEmpty() ? "未指定" : "登録済み")
            + " / 調味料 " + (seasonings.isEmpty() ? "未指定" : "登録済み")
            + " / 調理方法 " + (methods.isEmpty() ? "未指定" : "登録済み");
    }
}
