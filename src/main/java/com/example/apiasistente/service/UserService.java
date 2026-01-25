package com.example.apiasistente.service;

import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.repository.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;

    public UserService(AppUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @Transactional
    public AppUser register(String username, String rawPassword) {
        repo.findByUsername(username).ifPresent(u -> {
            throw new IllegalArgumentException("El usuario ya existe");
        });

        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        return repo.save(u);
    }
}
