package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.RegistrationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistrationCodeRepository extends JpaRepository<RegistrationCode, Long> {

    Optional<RegistrationCode> findByCodeHash(String codeHash);

    List<RegistrationCode> findByCreatedBy_IdOrderByCreatedAtDesc(Long userId);

    Optional<RegistrationCode> findByIdAndCreatedBy_Id(Long id, Long userId);
}
