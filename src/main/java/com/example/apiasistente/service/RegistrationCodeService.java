package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.RegistrationCodeCreateResponse;
import com.example.apiasistente.model.dto.RegistrationCodeDto;
import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.model.entity.RegistrationCode;
import com.example.apiasistente.repository.AppUserRepository;
import com.example.apiasistente.repository.RegistrationCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Service
public class RegistrationCodeService {

    private final RegistrationCodeRepository repo;
    private final AppUserRepository userRepo;

    private final int defaultTtlMinutes;
    private final int minTtlMinutes;
    private final int maxTtlMinutes;

    private final SecureRandom rnd = new SecureRandom();

    public RegistrationCodeService(
            RegistrationCodeRepository repo,
            AppUserRepository userRepo,
            @Value("${registration.codes.default-ttl-minutes:1440}") int defaultTtlMinutes,
            @Value("${registration.codes.min-ttl-minutes:10}") int minTtlMinutes,
            @Value("${registration.codes.max-ttl-minutes:10080}") int maxTtlMinutes
    ) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.defaultTtlMinutes = defaultTtlMinutes;
        this.minTtlMinutes = minTtlMinutes;
        this.maxTtlMinutes = maxTtlMinutes;
    }

    @Transactional
    public RegistrationCodeCreateResponse createForUser(String username, String label, Integer ttlMinutes) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        String cleanLabel = (label == null) ? "" : label.trim();
        if (cleanLabel.length() > 120) cleanLabel = cleanLabel.substring(0, 120);

        int ttl = ttlMinutes == null ? defaultTtlMinutes : ttlMinutes;
        if (ttl < minTtlMinutes) {
            throw new IllegalArgumentException("TTL demasiado corto (min " + minTtlMinutes + " minutos).");
        }
        if (ttl > maxTtlMinutes) {
            throw new IllegalArgumentException("TTL demasiado largo (max " + maxTtlMinutes + " minutos).");
        }

        String raw = generateRawToken();
        String prefix = raw.substring(0, Math.min(12, raw.length()));
        String hash = sha256Hex(raw);

        RegistrationCode code = new RegistrationCode();
        code.setCreatedBy(user);
        code.setLabel(cleanLabel.isBlank() ? null : cleanLabel);
        code.setCodePrefix(prefix);
        code.setCodeHash(hash);
        code.setExpiresAt(Instant.now().plusSeconds(ttl * 60L));

        code = repo.save(code);

        return new RegistrationCodeCreateResponse(
                code.getId(),
                code.getLabel(),
                code.getCodePrefix(),
                raw,
                code.getCreatedAt(),
                code.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public List<RegistrationCodeDto> listMine(String username) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        return repo.findByCreatedBy_IdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(c -> new RegistrationCodeDto(
                        c.getId(),
                        c.getLabel(),
                        c.getCodePrefix(),
                        c.getCreatedAt(),
                        c.getExpiresAt(),
                        c.getUsedAt(),
                        c.getRevokedAt(),
                        c.getUsedBy()
                ))
                .toList();
    }

    @Transactional
    public void revokeMine(String username, Long id) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        RegistrationCode code = repo.findByIdAndCreatedBy_Id(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Codigo no existe: " + id));

        if (!code.getCreatedBy().getId().equals(user.getId())) {
            throw new AccessDeniedException("No puedes revocar codigos de otro usuario");
        }

        if (code.getRevokedAt() == null) {
            code.setRevokedAt(Instant.now());
            repo.save(code);
        }
    }

    @Transactional
    public void consume(String rawCode, String newUsername) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new IllegalArgumentException("Codigo requerido.");
        }

        RegistrationCode code = repo.findByCodeHash(sha256Hex(rawCode.trim()))
                .orElseThrow(() -> new IllegalArgumentException("Codigo invalido o expirado."));

        if (code.isRevoked() || code.isUsed()) {
            throw new IllegalArgumentException("Codigo ya usado o revocado.");
        }

        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Codigo expirado.");
        }

        code.setUsedAt(Instant.now());
        code.setUsedBy(newUsername);
        repo.save(code);
    }

    private String generateRawToken() {
        byte[] buf = new byte[24];
        rnd.nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
