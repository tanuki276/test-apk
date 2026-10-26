package com.example.liefantidia2;

import android.content.Context;
import android.content.SharedPreferences;

/** Private, per-account device settings. No cloud write or new Firestore permission is needed. */
public final class CookingPreferencesStore {
    private final Context context;
    public CookingPreferencesStore(Context context) { this.context = context.getApplicationContext(); }
    private SharedPreferences file(String uid) {
        if (uid == null || uid.isEmpty()) throw new IllegalArgumentException("Authentication required");
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(uid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : bytes) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return context.getSharedPreferences("cooking_" + key, Context.MODE_PRIVATE);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public CookingPreferences load(String uid) {
        if (uid == null) return new CookingPreferences("", "", "");
        SharedPreferences prefs = file(uid);
        return new CookingPreferences(prefs.getString("allergies", ""), prefs.getString("seasonings", ""), prefs.getString("methods", ""));
    }
    public boolean save(String uid, CookingPreferences preferences) {
        return file(uid).edit().putString("allergies", preferences.allergies)
            .putString("seasonings", preferences.seasonings).putString("methods", preferences.methods)
            .putBoolean("saved", true).commit();
    }
    public boolean copyIfMissing(String sourceUid, String targetUid) {
        if (sourceUid == null || sourceUid.equals(targetUid) || file(targetUid).contains("saved") || !file(sourceUid).contains("saved")) return true;
        return save(targetUid, load(sourceUid));
    }
    public void delete(String uid) { file(uid).edit().clear().apply(); }
}
