package com.example.liefantidia2;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

public class HistoryMigrationTest {
    @Test public void legacyRecordsHaveValidFieldTypesAndLimits() {
        Map<String,Object> old = new HashMap<>();
        old.put("recipeTitle", "x".repeat(250));
        old.put("ingredientsWithUsage", "a".repeat(2200));
        old.put("timestamp", -1L);
        old.put("unknown", true);
        old.put("recipeContent", "本文");
        Map<String,Object> normalized = HistoryMigration.normalize(old, 123L);
        assertEquals(200, ((String) normalized.get("recipeTitle")).length());
        assertEquals(2000, ((String) normalized.get("ingredientsWithUsage")).length());
        assertEquals(123L, normalized.get("timestamp"));
        assertEquals("本文", normalized.get("recipeContent"));
        assertFalse(normalized.containsKey("unknown"));
        assertTrue(normalized.containsKey("id"));
    }
    @Test public void destinationIdsAreStableAndFitFirestoreLimits() {
        assertEquals("guest_uid_recipe", HistoryMigration.destinationId("uid", "recipe"));
        String longId = "食".repeat(500);
        String destination = HistoryMigration.destinationId("uid", longId);
        assertTrue(destination.length() < 1500);
        assertEquals(destination, HistoryMigration.destinationId("uid", longId));
        assertNotEquals(destination, HistoryMigration.destinationId("other", longId));
    }
}
