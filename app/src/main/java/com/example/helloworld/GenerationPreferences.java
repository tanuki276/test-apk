package com.example.liefantidia2;

import android.content.Context;
import com.google.firebase.auth.FirebaseUser;

/** Generation choices are isolated per account and never enable paid modes for guests. */
public final class GenerationPreferences {
    private GenerationPreferences() {}
    public static boolean isHighAccuracy(Context context, FirebaseUser user) {
        return user != null && !AccountController.isGuest(user) && context.getSharedPreferences(
            "generation_preferences", Context.MODE_PRIVATE).getBoolean(user.getUid(), false);
    }
    public static void setHighAccuracy(Context context, FirebaseUser user, boolean enabled) {
        if (user != null && !AccountController.isGuest(user)) context.getSharedPreferences(
            "generation_preferences", Context.MODE_PRIVATE).edit().putBoolean(user.getUid(), enabled).apply();
    }
}
