package com.example.apiasistente.prompt.service;

import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.prompt.repository.SystemPromptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Servicio para System Prompt.
 */
@Service
public class SystemPromptService {

    private static final String DEFAULT_PROMPT_NAME = "Default Operativo";
    private static final String DEFAULT_PROMPT_CONTENT = """
            Eres ApiAsistente, un asistente tecnico y operativo.
            Reglas:
            - Responde en espanol, directo y sin relleno.
            - Si faltan datos para una respuesta fiable, dilo claramente y pide el dato minimo necesario.
            - No inventes hechos, ejecuciones ni resultados que no esten sustentados.
            - Si hay adjuntos (archivo, PDF o imagen), prioriza ese contenido antes que supuestos generales.
            - En temas de codigo, prioriza causa raiz, cambio concreto, impacto y validacion.
            - Cuando propongas acciones, da pasos ejecutables y ordenados por prioridad.
            - Mantiene el contexto de la sesion y evita repetir texto ya dicho sin aportar valor.
            """;

    private final SystemPromptRepository repo;

    public SystemPromptService(SystemPromptRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public SystemPrompt activePromptOrThrow() {
        SystemPrompt active = repo.findFirstByActiveTrue()
                .orElseGet(this::ensureDefaultActivePrompt);
        if (isManagedDefaultPrompt(active) && !DEFAULT_PROMPT_CONTENT.equals(active.getContent())) {
            active.setContent(DEFAULT_PROMPT_CONTENT);
            return repo.save(active);
        }
        return active;
    }

    @Transactional
    public void setActive(Long id) {
        repo.findAll().forEach(p -> { p.setActive(p.getId().equals(id)); repo.save(p); });
    }

    private SystemPrompt ensureDefaultActivePrompt() {
        List<SystemPrompt> prompts = repo.findAll();
        if (!prompts.isEmpty()) {
            SystemPrompt candidate = prompts.stream()
                    .filter(this::isManagedDefaultPrompt)
                    .findFirst()
                    .orElse(prompts.get(0));
            candidate.setActive(true);
            return repo.save(candidate);
        }

        SystemPrompt created = new SystemPrompt();
        created.setName(DEFAULT_PROMPT_NAME);
        created.setContent(DEFAULT_PROMPT_CONTENT);
        created.setActive(true);
        return repo.save(created);
    }

    private boolean isManagedDefaultPrompt(SystemPrompt prompt) {
        if (prompt == null || prompt.getName() == null) {
            return false;
        }
        String name = prompt.getName().trim();
        return DEFAULT_PROMPT_NAME.equalsIgnoreCase(name)
                || "default".equalsIgnoreCase(name)
                || "default-operativo".equalsIgnoreCase(name);
    }
}


