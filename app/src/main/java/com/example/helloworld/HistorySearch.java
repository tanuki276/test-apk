package com.example.liefantidia2;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches already loaded history without weakening the owner-only Firestore query. */
public final class HistorySearch {
    private HistorySearch() {}

    public static List<RecipeHistory> filter(List<RecipeHistory> recipes, String query) {
        String value = normalize(query).trim();
        if (value.isEmpty()) return new ArrayList<>(recipes);
        String[] terms = value.split("\\s+");
        List<RecipeHistory> matched = new ArrayList<>();
        for (RecipeHistory recipe : recipes) {
            String searchable = normalize(recipe.getRecipeTitle()) + " "
                + normalize(recipe.getIngredientsWithUsage()) + " "
                + normalize(recipe.getRecipeContent());
            boolean includesAll = true;
            for (String term : terms) {
                if (!searchable.contains(term)) { includesAll = false; break; }
            }
            if (includesAll) matched.add(recipe);
        }
        return matched;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }
}
