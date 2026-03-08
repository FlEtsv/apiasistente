package com.example.apiasistente.setup.repository;

import com.example.apiasistente.setup.entity.AppSetupConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio para la configuracion inicial de la instalacion.
 */
public interface AppSetupConfigRepository extends JpaRepository<AppSetupConfig, Long> {
    Optional<AppSetupConfig> findTopByOrderByIdAsc();
}
