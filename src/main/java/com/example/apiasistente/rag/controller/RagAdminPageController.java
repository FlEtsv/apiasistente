// RagAdminPageController.java
package com.example.apiasistente.rag.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador para RAG Admin Page.
 */
@Controller
public class RagAdminPageController {

    @GetMapping("/rag-admin")
    public String page() {
        return "redirect:/app/rag-admin";
    }
}

