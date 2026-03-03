package com.example.apiasistente.apikey.repository;

import com.example.apiasistente.apikey.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para API Key.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findFirstByKeyHash(String keyHash);

    List<ApiKey> findByUser_IdOrderByCreatedAtDesc(Long userId);
}


