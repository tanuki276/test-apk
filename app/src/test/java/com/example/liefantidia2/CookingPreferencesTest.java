package com.example.liefantidia2;
import org.junit.Test;
import static org.junit.Assert.*;

public class CookingPreferencesTest {
    @Test public void includesSavedDefaultsAndAllergyPriority() {
        String prompt = new CookingPreferences("卵、乳", "塩、しょうゆ", "電子レンジのみ").prompt();
        assertTrue(prompt.contains("卵、乳"));
        assertTrue(prompt.contains("塩、しょうゆ"));
        assertTrue(prompt.contains("電子レンジのみ"));
        assertTrue(prompt.contains("追加指示と矛盾する場合も、この除外条件を優先"));
        assertTrue(prompt.contains("他の調味料を追加しない"));
    }
    @Test public void explicitNoneIsNotTreatedAsUnspecified() {
        assertTrue(new CookingPreferences("", "なし", "").prompt().contains("【使える調味料】なし"));
        assertFalse(new CookingPreferences("", "", "").prompt().contains("【使える調味料】"));
    }
    @Test public void normalizesAndBoundsInputWithoutAddingBudget() {
        CookingPreferences preferences = new CookingPreferences(null, "  塩  ", "a".repeat(500));
        assertEquals("", preferences.allergies);
        assertEquals("塩", preferences.seasonings);
        assertEquals(400, preferences.methods.length());
        assertFalse(preferences.prompt().contains("予算"));
    }
}
