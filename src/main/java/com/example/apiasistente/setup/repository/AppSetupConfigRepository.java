package com.example.apiasistente.setup.repository;

import com.example.apiasistente.setup.entity.AppSetupConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio para la configuracion inicial de la instalacion.
 */
public interface AppSetupConfigRepository extends JpaRepository<AppSetupConfig, Long> {

    /**
     * Recupera el primer registro de setup creado en la instalacion.
     *
     * @return configuracion persistida si existe
     */
    Optional<AppSetupConfig> findTopByOrderByIdAsc();
}
