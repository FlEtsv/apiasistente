package com.example.apiasistente.registration.repository;

import com.example.apiasistente.registration.entity.RegistrationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para Registration Code.
 */
public interface RegistrationCodeRepository extends JpaRepository<RegistrationCode, Long> {

    Optional<RegistrationCode> findByCodeHash(String codeHash);

    List<RegistrationCode> findByCreatedBy_IdOrderByCreatedAtDesc(Long userId);

    Optional<RegistrationCode> findByIdAndCreatedBy_Id(Long id, Long userId);
}


