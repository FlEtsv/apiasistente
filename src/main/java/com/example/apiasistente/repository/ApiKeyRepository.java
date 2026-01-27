package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findFirstByKeyHash(String keyHash);

    List<ApiKey> findByUser_IdOrderByCreatedAtDesc(Long userId);
}
