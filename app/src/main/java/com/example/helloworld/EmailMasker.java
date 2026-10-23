package com.example.liefantidia2;

/** Mask account labels without ever returning an unmasked or malformed address. */
public final class EmailMasker {
    private EmailMasker() { }
    public static String mask(String email) {
        if (email == null) return "登録済みアカウント";
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@') || at == email.length() - 1) return "登録済みアカウント";
        String local = email.substring(0, at);
        int length = local.codePointCount(0, local.length());
        String prefix = "", suffix = "";
        if (length > 4) {
            prefix = local.substring(0, local.offsetByCodePoints(0, 2));
            suffix = local.substring(local.offsetByCodePoints(0, length - 2));
        } else if (length > 1) {
            prefix = local.substring(0, local.offsetByCodePoints(0, 1));
            if (length > 2) suffix = local.substring(local.offsetByCodePoints(0, length - 1));
        }
        return prefix + "****" + suffix + email.substring(at);
    }
}
