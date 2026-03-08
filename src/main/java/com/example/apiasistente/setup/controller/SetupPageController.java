package com.example.apiasistente.setup.controller;

import com.example.apiasistente.setup.service.SetupConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pantalla inicial de configuracion para instalaciones nuevas.
 */
@Controller
public class SetupPageController {

    private final SetupConfigService setupConfigService;

    public SetupPageController(SetupConfigService setupConfigService) {
        this.setupConfigService = setupConfigService;
    }

    /**
     * Renderiza la vista del wizard de instalacion.
     *
     * @param model modelo de UI con bandera de configuracion actual
     * @return nombre de plantilla Thymeleaf
     */
    @GetMapping("/setup")
    public String setupPage(Model model) {
        model.addAttribute("configured", setupConfigService.isConfigured());
        return "setup";
    }
}
