// ApiKeyService.java
package com.example.apiasistente.service;

import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ApiKeyService {

    private final AppUserRepository userRepo;
    private final PasswordEncoder encoder;
    private final SecureRandom rnd = new SecureRandom();

    public ApiKeyService(AppUserRepository userRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.encoder = encoder;
    }

    @Transactional
    public String rotateKey(String username) {
        AppUser user = userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario no existe: " + username));

        String secret = randomUrlSafe(32);
        String prefix = secret.substring(0, 10); // suficiente para indexar
        String apiKey = "ak_" + prefix + "_" + randomUrlSafe(32);

        user.setApiKeyPrefix(prefix);
        user.setApiKeyHash(encoder.encode(apiKey));
        userRepo.save(user);

        // IMPORTANTE: se devuelve una sola vez. Guárdala.
        return apiKey;
    }

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
