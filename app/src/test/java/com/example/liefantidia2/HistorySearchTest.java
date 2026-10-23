package com.example.liefantidia2;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class HistorySearchTest {
    @Test public void searchesTitleIngredientsAndBodyWithMultipleTerms() {
        RecipeHistory curry = new RecipeHistory("1", "チキンカレー", "鶏肉、玉ねぎ", "", "玉ねぎを炒める", 2);
        RecipeHistory soup = new RecipeHistory("2", "野菜スープ", "にんじん", "", "電子レンジで温める", 1);
        List<RecipeHistory> all = Arrays.asList(curry, soup);
        assertEquals(Arrays.asList(curry), HistorySearch.filter(all, "カレー 玉ねぎ"));
        assertEquals(Arrays.asList(soup), HistorySearch.filter(all, "スープ レンジ"));
        assertEquals(Arrays.asList(curry), HistorySearch.filter(all, "ﾁｷﾝ"));
        assertEquals(all, HistorySearch.filter(all, "  "));
        assertTrue(HistorySearch.filter(all, "存在しない").isEmpty());
    }
}
