package com.example.apiasistente.service;

import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        if (u.length() < 3) throw new IllegalArgumentException("Usuario demasiado corto (mín 3).");
        if (rawPassword == null || rawPassword.length() < 8)
            throw new IllegalArgumentException("Contraseña demasiado corta (mín 8).");

        if (repo.existsByUsername(u))
            throw new IllegalArgumentException("Ese usuario ya existe.");

        RegistrationCodeService.ConsumedRegistrationCode consumedCode =
                registrationCodeService.consume(registrationCode, u);

        AppUser user = new AppUser();
        user.setUsername(u);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setEnabled(true);
        user.setGrantedPermissions(consumedCode.grantedPermissionsCsv());

        repo.save(user); // <- CLAVE: aquí se inserta en MySQL
    }
}
