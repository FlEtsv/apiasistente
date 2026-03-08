package com.example.apiasistente.monitoring.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador MVC para la pantalla de monitor interno.
 */
@Controller
public class MonitorPageController {

    /**
     * Renderiza la vista de monitor.
     *
     * @return nombre de plantilla Thymeleaf
     */
    @GetMapping("/monitor")
    public String monitorPage() {
        return "monitor";
    }
}
