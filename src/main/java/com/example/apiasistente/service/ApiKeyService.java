package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.*;
import com.example.apiasistente.model.entity.ApiKey;
import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.repository.ApiKeyRepository;
import com.example.apiasistente.repository.AppUserRepository;
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
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepo;
    private final AppUserRepository userRepo;
    private final ChatService chatService;

    private final SecureRandom rnd = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepo, AppUserRepository userRepo, ChatService chatService) {
        this.apiKeyRepo = apiKeyRepo;
        this.userRepo = userRepo;
        this.chatService = chatService;
    }

    @Transactional
    public ApiKeyCreateResponse createForUser(String username, String label) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        String cleanLabel = (label == null) ? "" : label.trim();
        if (cleanLabel.isBlank()) throw new IllegalArgumentException("Label vacío");
        if (cleanLabel.length() > 120) cleanLabel = cleanLabel.substring(0, 120);

        String raw = generateRawToken();
        String prefix = raw.substring(0, Math.min(12, raw.length()));
        String hash = sha256Hex(raw);

        ApiKey k = new ApiKey();
        k.setUser(user);
        k.setLabel(cleanLabel);
        k.setKeyPrefix(prefix);
        k.setKeyHash(hash);

        k = apiKeyRepo.save(k);

        // Cada API key externa debe iniciar su propio chat para aislar contexto.
        String sessionId = chatService.newSession(username);

        return new ApiKeyCreateResponse(k.getId(), k.getLabel(), k.getKeyPrefix(), raw, sessionId);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDto> listMine(String username) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        return apiKeyRepo.findByUser_IdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(k -> new ApiKeyDto(
                        k.getId(),
                        k.getLabel(),
                        k.getKeyPrefix(),
                        k.getCreatedAt(),
                        k.getLastUsedAt(),
                        k.getRevokedAt()
                ))
                .toList();
    }

    @Transactional
    public void revokeMine(String username, Long apiKeyId) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        ApiKey k = apiKeyRepo.findById(apiKeyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey no existe: " + apiKeyId));

        if (!k.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No puedes revocar claves de otro usuario");
        }

        if (k.getRevokedAt() == null) {
            k.setRevokedAt(Instant.now());
            apiKeyRepo.save(k);
        }
    }

    // Usado por el filtro de /api/ext/**
    @Transactional
    public String authenticateAndGetUsername(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;

        String hash = sha256Hex(rawToken.trim());

        ApiKey k = apiKeyRepo.findFirstByKeyHash(hash).orElse(null);
        if (k == null) return null;
        if (k.isRevoked()) return null;

        k.setLastUsedAt(Instant.now());
        apiKeyRepo.save(k);

        return k.getUser().getUsername();
    }

    private String generateRawToken() {
        byte[] buf = new byte[32];
        rnd.nextBytes(buf);
        // base64url sin padding
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
