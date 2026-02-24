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
    private final RegistrationCodeService registrationCodeService;

    public UserService(AppUserRepository repo, PasswordEncoder encoder, RegistrationCodeService registrationCodeService) {
        this.repo = repo;
        this.encoder = encoder;
        this.registrationCodeService = registrationCodeService;
    }

    @Transactional
    public AppUser register(String username, String rawPassword, String registrationCode) {
        repo.findByUsername(username).ifPresent(u -> {
            throw new IllegalArgumentException("El usuario ya existe");
        });

        RegistrationCodeService.ConsumedRegistrationCode consumedCode =
                registrationCodeService.consume(registrationCode, username);

        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setGrantedPermissions(consumedCode.grantedPermissionsCsv());
        return repo.save(u);
    }
}
