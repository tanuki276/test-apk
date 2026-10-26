package com.example.liefantidia2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Compatibility boundary for older recipe schemas. */
final class HistoryMigration {
    static String destinationId(String uid, String id) {
        String original = "guest_" + uid + "_" + id;
        if (original.getBytes(StandardCharsets.UTF_8).length <= 1500) return original;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("guest_");
            for (byte value : digest) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static Map<String, Object> normalize(Map<String, Object> source, long fallbackTimestamp) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", null);
        out.put("recipeTitle", text(source.get("recipeTitle"), "CookAi レシピ", 200));
        out.put("ingredientsWithUsage", text(source.get("ingredientsWithUsage"), "", 2000));
        out.put("allConstraints", text(source.get("allConstraints"), "", 4000));
        // Never truncate the recipe body: oversized legacy records must be surfaced for recovery.
        out.put("recipeContent", text(source.get("recipeContent"), "", Integer.MAX_VALUE));
        Object timestamp = source.get("timestamp");
        long value = timestamp instanceof Number ? ((Number) timestamp).longValue() : fallbackTimestamp;
        out.put("timestamp", value > 0 ? value : fallbackTimestamp);
        return out;
    }
    private static String text(Object value, String fallback, int max) {
        String text = value instanceof String ? (String) value : fallback;
        return text.length() > max ? text.substring(0, max) : text;
    }
}
