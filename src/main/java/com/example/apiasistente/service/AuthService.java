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

    public AuthService(AppUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public void register(String username, String rawPassword) {
        String u = username == null ? "" : username.trim();

        if (u.length() < 3) throw new IllegalArgumentException("Usuario demasiado corto (mín 3).");
        if (rawPassword == null || rawPassword.length() < 8)
            throw new IllegalArgumentException("Contraseña demasiado corta (mín 8).");

        if (repo.existsByUsername(u))
            throw new IllegalArgumentException("Ese usuario ya existe.");

        AppUser user = new AppUser();
        user.setUsername(u);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setEnabled(true);

        repo.save(user); // <- CLAVE: aquí se inserta en MySQL
    }
}
