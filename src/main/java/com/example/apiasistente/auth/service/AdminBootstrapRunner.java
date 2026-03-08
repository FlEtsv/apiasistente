package com.example.apiasistente.auth.service;

import com.example.apiasistente.auth.config.AdminBootstrapProperties;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.auth.repository.AppUserRepository;
import com.example.apiasistente.shared.security.AppPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Crea un admin inicial en instalaciones nuevas para simplificar el onboarding.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%&*+-_";
    private static final int GENERATED_PASSWORD_LENGTH = 22;

    private final AdminBootstrapProperties properties;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AtomicBoolean attempted = new AtomicBoolean(false);

    public AdminBootstrapRunner(AdminBootstrapProperties properties,
                                AppUserRepository userRepository,
                                PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        bootstrapIfNeeded();
    }

    @Transactional
    void bootstrapIfNeeded() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        if (userRepository.count() > 0) {
            return;
        }

        String username = normalizeUsername(properties.getUsername());
        PasswordSelection passwordSelection = resolvePassword(properties);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEnabled(true);
        user.setPasswordHash(passwordEncoder.encode(passwordSelection.password()));
        user.setGrantedPermissions(AppPermission.toCsv(AppPermission.all()));
        userRepository.save(user);

        writeCredentialsFile(username, passwordSelection.password(), properties.getOutputFile());
        if (passwordSelection.generated()) {
            log.warn("Bootstrap admin creado. user='{}' password='{}'. Cambia la contrasena tras el primer login.", username, passwordSelection.password());
        } else {
            log.info("Bootstrap admin creado para user='{}'. Password definido por entorno.", username);
        }
    }

    private PasswordSelection resolvePassword(AdminBootstrapProperties cfg) {
        String configured = trimToEmpty(cfg.getPassword());
        if (!configured.isBlank()) {
            return new PasswordSelection(configured, false);
        }
        if (!cfg.isGenerateRandomPasswordIfEmpty()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD es obligatorio cuando generate-random-password-if-empty=false.");
        }
        return new PasswordSelection(generatePassword(GENERATED_PASSWORD_LENGTH), true);
    }

    private String normalizeUsername(String raw) {
        String candidate = trimToEmpty(raw);
        if (candidate.isBlank()) {
            return "admin";
        }
        if (candidate.length() > 80) {
            candidate = candidate.substring(0, 80);
        }
        if (candidate.length() < 3) {
            return "admin";
        }
        return candidate;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String generatePassword(int length) {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder out = new StringBuilder(Math.max(12, length));
        int size = Math.max(12, length);
        for (int i = 0; i < size; i++) {
            int idx = random.nextInt(PASSWORD_CHARS.length());
            out.append(PASSWORD_CHARS.charAt(idx));
        }
        return out.toString();
    }

    private void writeCredentialsFile(String username, String password, String rawPath) {
        String pathValue = trimToEmpty(rawPath);
        if (pathValue.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(pathValue).normalize();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = """
                    # ApiAsistente bootstrap credentials
                    # Generated at: %s
                    username=%s
                    password=%s
                    """.formatted(Instant.now().toString(), username, password);
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("No se pudo escribir archivo de credenciales bootstrap '{}': {}", rawPath, compactMessage(e));
        }
    }

    private String compactMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private record PasswordSelection(String password, boolean generated) {
    }
}
