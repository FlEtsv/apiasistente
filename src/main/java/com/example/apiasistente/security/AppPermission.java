package com.example.apiasistente.security;

import com.example.apiasistente.model.entity.AppUser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Permisos funcionales del producto para controlar vistas y APIs.
 */
public enum AppPermission {
    CHAT("PERM_CHAT"),
    RAG("PERM_RAG"),
    MONITOR("PERM_MONITOR"),
    API_KEYS("PERM_API_KEYS");

    private final String authority;

    AppPermission(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    public static EnumSet<AppPermission> all() {
        return EnumSet.allOf(AppPermission.class);
    }

    /**
     * Parsea CSV persistido en BD.
     * Si está vacío y {@code blankMeansAll} es true, devuelve acceso total.
     */
    public static EnumSet<AppPermission> fromCsv(String csv, boolean blankMeansAll) {
        if (csv == null || csv.isBlank()) {
            return blankMeansAll ? all() : EnumSet.noneOf(AppPermission.class);
        }

        EnumSet<AppPermission> out = EnumSet.noneOf(AppPermission.class);
        for (String token : csv.split(",")) {
            AppPermission p = fromToken(token);
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty() && blankMeansAll) {
            return all();
        }
        return out;
    }

    public static EnumSet<AppPermission> fromRequestList(Collection<String> requested, boolean emptyMeansAll) {
        if (requested == null || requested.isEmpty()) {
            return emptyMeansAll ? all() : EnumSet.noneOf(AppPermission.class);
        }

        EnumSet<AppPermission> out = EnumSet.noneOf(AppPermission.class);
        for (String raw : requested) {
            AppPermission p = fromToken(raw);
            if (p == null) {
                throw new IllegalArgumentException("Permiso no valido: " + raw);
            }
            out.add(p);
        }

        if (out.isEmpty() && emptyMeansAll) {
            return all();
        }
        return out;
    }

    public static String toCsv(Collection<AppPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        return permissions.stream()
                .map(Enum::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    public static List<String> toApiValues(Collection<AppPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        return permissions.stream()
                .map(Enum::name)
                .toList();
    }

    public static List<String> authoritiesForUser(AppUser user) {
        EnumSet<AppPermission> resolved = resolveForUser(user);
        List<String> out = new ArrayList<>(resolved.size());
        for (AppPermission p : resolved) {
            out.add(p.authority());
        }
        return out;
    }

    public static EnumSet<AppPermission> resolveForUser(AppUser user) {
        if (user == null) {
            return EnumSet.noneOf(AppPermission.class);
        }
        return fromCsv(user.getGrantedPermissions(), true);
    }

    private static AppPermission fromToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (token.startsWith("PERM_")) {
            token = token.substring("PERM_".length());
        }
        return switch (token) {
            case "CHAT" -> CHAT;
            case "RAG", "RAG_ADMIN" -> RAG;
            case "MONITOR", "MONITOREO", "OPS" -> MONITOR;
            case "API_KEYS", "APIKEYS", "REG_CODES", "REGISTRATION_CODES" -> API_KEYS;
            default -> null;
        };
    }
}
