package com.example.apiasistente.setup.dto;

/**
 * Estado minimo del setup para redireccion inicial.
 *
 * @param configured si la instalacion ya tiene configuracion valida
 */
public record SetupStatusResponse(boolean configured) {
}
