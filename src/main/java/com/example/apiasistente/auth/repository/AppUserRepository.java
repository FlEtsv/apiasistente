package com.example.apiasistente.auth.repository;

import com.example.apiasistente.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio para App User.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
    Optional<AppUser> findByApiKeyPrefix(String apiKeyPrefix);
    Optional<AppUser> findByApiKeySha256(String apiKeySha256);

}



