package com.example.liefantidia2;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** A high-entropy user-held credential for recovering a guest UID after reinstall. */
public final class GuestRecovery {
    private static final String DOMAIN = "@guest.cookai.invalid";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private GuestRecovery() {}

    public static String generate() {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        return hex(random);
    }
    public static String normalize(String input) {
        return input == null ? "" : input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
    public static boolean valid(String input) { return normalize(input).matches("[0-9A-F]{48}"); }
    public static String display(String input) {
        String code = normalize(input);
        return code.replaceAll("(.{8})(?=.)", "$1-");
    }
    public static String email(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalize(input).getBytes(StandardCharsets.UTF_8));
            return "recovery-" + hex(digest).substring(0, 40).toLowerCase(Locale.ROOT) + DOMAIN;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    public static boolean isRecoveryEmail(String email) {
        return email != null && email.matches("recovery-[0-9a-f]{40}" + java.util.regex.Pattern.quote(DOMAIN));
    }
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[2*i] = HEX[(bytes[i] >> 4) & 15];
            chars[2*i+1] = HEX[bytes[i] & 15];
        }
        return new String(chars);
    }
    public static void save(Context context, String uid, String code) {
        context.getSharedPreferences("guest_recovery", Context.MODE_PRIVATE).edit()
            .putString(uid, normalize(code)).apply();
    }
    public static String load(Context context, String uid) {
        return context.getSharedPreferences("guest_recovery", Context.MODE_PRIVATE).getString(uid, "");
    }
    public static void remove(Context context, String uid) {
        context.getSharedPreferences("guest_recovery", Context.MODE_PRIVATE).edit().remove(uid).apply();
    }
}
