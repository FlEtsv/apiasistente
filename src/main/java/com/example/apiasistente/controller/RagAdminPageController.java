// RagAdminPageController.java
package com.example.apiasistente.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RagAdminPageController {

    @GetMapping("/rag-admin")
    public String page() {
        return "rag_admin";
    }
}
