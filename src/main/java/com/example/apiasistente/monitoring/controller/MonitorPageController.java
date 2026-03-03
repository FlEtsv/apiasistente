package com.example.apiasistente.monitoring.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador para Monitor Page.
 */
@Controller
public class MonitorPageController {

    @GetMapping("/monitor")
    public String monitorPage() {
        return "monitor";
    }
}
