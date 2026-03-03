package com.example.apiasistente.registration.controller;

import com.example.apiasistente.registration.dto.RegistrationCodeCreateRequest;
import com.example.apiasistente.registration.dto.RegistrationCodeCreateResponse;
import com.example.apiasistente.registration.dto.RegistrationCodeDto;
import com.example.apiasistente.registration.service.RegistrationCodeService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controlador para Registration Code.
 */
@RestController
@RequestMapping("/api/registration-codes")
public class RegistrationCodeController {

    private final RegistrationCodeService service;

    public RegistrationCodeController(RegistrationCodeService service) {
        this.service = service;
    }

    @GetMapping
    public List<RegistrationCodeDto> listMine(Principal principal) {
        return service.listMine(principal.getName());
    }

    @PostMapping
    public RegistrationCodeCreateResponse create(@RequestBody RegistrationCodeCreateRequest req, Principal principal) {
        return service.createForUser(principal.getName(), req.getLabel(), req.getTtlMinutes(), req.getPermissions());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> revoke(@PathVariable Long id, Principal principal) {
        service.revokeMine(principal.getName(), id);
        return Map.of("ok", "true");
    }
}


