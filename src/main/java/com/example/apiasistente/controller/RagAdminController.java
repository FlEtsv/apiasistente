package com.example.apiasistente.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RagAdminController {

    @GetMapping("/rag-admin")
    public String page() {
        return "rag_admin";
    }
}
