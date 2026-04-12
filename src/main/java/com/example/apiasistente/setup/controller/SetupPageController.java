package com.example.apiasistente.setup.controller;

import com.example.apiasistente.setup.service.SetupConfigService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Pantalla inicial de configuracion para instalaciones nuevas.
 */
@Controller
public class SetupPageController {

    public SetupPageController(SetupConfigService setupConfigService) {
    }

    @GetMapping("/setup")
    public String setupPage() {
        return "redirect:/app/setup";
    }
}
