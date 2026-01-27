// PasswordConfig.java
package com.example.apiasistente.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new AutoDetectPasswordEncoder();
    }

    static class AutoDetectPasswordEncoder implements PasswordEncoder {
        private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

        @Override
        public String encode(CharSequence rawPassword) {
            return bcrypt.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String storedPassword) {
            if (rawPassword == null || storedPassword == null) return false;

            String raw = rawPassword.toString();
            String s = storedPassword.trim();

            // 1) bcrypt normal
            if (s.startsWith("$2a$") || s.startsWith("$2b$") || s.startsWith("$2y$")) {
                return bcrypt.matches(raw, s);
            }

            // 2) Delegating format {bcrypt}
            if (s.startsWith("{bcrypt}")) {
                return bcrypt.matches(raw, s.substring("{bcrypt}".length()));
            }

            // 3) sha256 hex (64 chars)
            if (s.matches("^[a-fA-F0-9]{64}$")) {
                return sha256Hex(raw).equalsIgnoreCase(s);
            }

            // 4) legacy texto plano
            return s.equals(raw);
        }

        private static String sha256Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(out);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
