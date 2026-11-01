package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class AccountRules {
    private AccountRules() {}
    public static String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }
    public static String username(String value) {
        String name = normalize(value);
        if (!name.matches("[a-z][a-z0-9_]{2,31}") || Set.of("admin","administrator","root","system","support","api","null","undefined").contains(name))
            throw new ApiFailure(ErrorCode.ACCOUNT_NAME_INVALID);
        return name;
    }
    public static void password(String value) {
        if (value == null || value.codePointCount(0,value.length()) < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72
                || value.codePoints().anyMatch(Character::isISOControl)) throw new ApiFailure(ErrorCode.PASSWORD_INVALID);
    }
    public static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
