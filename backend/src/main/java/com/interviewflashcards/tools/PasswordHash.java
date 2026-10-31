package com.interviewflashcards.tools;

import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Offline console tool; never starts Spring or connects to a database. */
public final class PasswordHash {
    private PasswordHash() {}
    public static void main(String[] args) {
        var console = System.console();
        if (console == null) throw new IllegalStateException("Run in an interactive terminal");
        char[] password = console.readPassword("Password (12+ characters): ");
        char[] confirmation = console.readPassword("Confirm password: ");
        try {
            if (password == null || confirmation == null || password.length < 12
                    || !Arrays.equals(password, confirmation) || new String(password).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("Use matching passwords, 12+ characters and at most 72 UTF-8 bytes");
            }
            console.printf("%s%n", new BCryptPasswordEncoder(12).encode(new String(password)));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }
}
