package com.example.apiasistente.auth.service;

import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.auth.repository.AppUserRepository;
import com.example.apiasistente.registration.service.RegistrationCodeService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio para Auth.
 */
@Service
public class AuthService {

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;
    private final RegistrationCodeService registrationCodeService;

    public AuthService(AppUserRepository repo, PasswordEncoder encoder, RegistrationCodeService registrationCodeService) {
        this.repo = repo;
        this.encoder = encoder;
        this.registrationCodeService = registrationCodeService;
    }

    @Transactional
    public void register(String username, String rawPassword, String registrationCode) {
        String u = username == null ? "" : username.trim();

        if (u.length() < 3) throw new IllegalArgumentException("Usuario demasiado corto (mÃ­n 3).");
        if (rawPassword == null || rawPassword.length() < 8)
            throw new IllegalArgumentException("ContraseÃ±a demasiado corta (mÃ­n 8).");

        if (repo.existsByUsername(u))
            throw new IllegalArgumentException("Ese usuario ya existe.");

        RegistrationCodeService.ConsumedRegistrationCode consumedCode =
                registrationCodeService.consume(registrationCode, u);

        AppUser user = new AppUser();
        user.setUsername(u);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setEnabled(true);
        user.setGrantedPermissions(consumedCode.grantedPermissionsCsv());

        repo.save(user); // <- CLAVE: aquÃ­ se inserta en MySQL
    }
}


