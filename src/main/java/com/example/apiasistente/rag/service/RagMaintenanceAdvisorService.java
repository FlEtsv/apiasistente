package com.example.apiasistente.rag.service;

import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.rag.entity.RagMaintenanceAction;
import com.example.apiasistente.rag.entity.RagMaintenanceCase;
import com.example.apiasistente.rag.entity.RagMaintenanceIssueType;
import com.example.apiasistente.rag.entity.RagMaintenanceSeverity;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Consulta un modelo ligero para decidir sobre casos pendientes del robot RAG.
 *
 * Responsabilidad:
 * - Convertir un hallazgo tecnico del robot en una accion operativa concreta.
 * - Encapsular prompts y parsing para que el resto del mantenimiento no dependa del LLM.
 */
@Service
public class RagMaintenanceAdvisorService {

    private static final int MAX_CONTENT_CHARS = 2800;

    private final OllamaClient ollamaClient;
    private final ChatModelSelector chatModelSelector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagMaintenanceAdvisorService(OllamaClient ollamaClient, ChatModelSelector chatModelSelector) {
        this.ollamaClient = ollamaClient;
        this.chatModelSelector = chatModelSelector;
    }

    public Advice advise(RagMaintenanceCase ragCase) {
        if (ragCase == null) {
            return Advice.fallback(RagMaintenanceAction.KEEP, "Caso vacio.", null, "fallback");
        }

        String model = chatModelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS);
        String systemPrompt = switch (ragCase.getSeverity()) {
            case CRITICAL -> criticalSystemPrompt();
            case WARNING -> warningSystemPrompt(ragCase.getIssueType());
        };
        String userPrompt = buildUserPrompt(ragCase);

        try {
            // El modelo rapido solo decide; la ejecucion real sigue quedando en mantenimiento.
            String raw = ollamaClient.chat(
                    List.of(
                            new OllamaClient.Message("system", systemPrompt),
                            new OllamaClient.Message("user", userPrompt)
                    ),
                    model
            );
            DecisionPayload payload = parsePayload(raw);

            RagMaintenanceAction decision = payload.decision == null
                    ? fallbackAction(ragCase)
                    : parseAction(payload.decision, fallbackAction(ragCase));

            String normalizedContent = trimToNull(payload.normalizedContent);
            if (decision == RagMaintenanceAction.RESTRUCTURE && normalizedContent == null) {
                normalizedContent = trimToNull(ragCase.getProposedContent());
            }

            return new Advice(
                    decision,
                    trimToNull(payload.reason) == null ? "Sin razon detallada." : payload.reason.trim(),
                    normalizedContent,
                    raw == null ? "" : raw.trim(),
                    model
            );
        } catch (Exception e) {
            return Advice.fallback(
                    fallbackAction(ragCase),
                    "Fallo consulta IA: " + safeMessage(e),
                    trimToNull(ragCase.getProposedContent()),
                    model
            );
        }
    }

    private String criticalSystemPrompt() {
        return """
                Eres un auditor autonomo de corpus RAG.
                Tu prioridad es retirar basura, ruido, contenido ilegible o inservible para retrieval.
                La estructura canonica del sistema es:
                - documents(doc_id, title, source, created_at, ...)
                - chunks(chunk_id, doc_id, chunk_index, text, hash, token_count, created_at, source, tags...)
                - vectors(chunk_id -> embedding) en un indice HNSW
                Si el contenido es ilegible, vacio, corrupto, demasiado pobre o claramente inutil para una base de conocimiento, decide DELETE.
                Solo usa RESTRUCTURE si el contenido aun conserva hechos utiles y puedes dejarlo limpio y compacto para RAG.
                Si reestructuras, devuelve solo el cuerpo textual limpio listo para ser re-chunkeado; no repitas el titulo ni metas wrappers.
                Devuelve solo JSON valido con este esquema exacto:
                {"decision":"DELETE|RESTRUCTURE|KEEP","reason":"texto corto","normalizedContent":"texto o vacio"}
                No uses markdown. No anadas texto fuera del JSON.
                """;
    }

    private String warningSystemPrompt(RagMaintenanceIssueType issueType) {
        String scenario = switch (issueType) {
            case BAD_STRUCTURE -> "problema de estructura, redundancia o chunking deficiente";
            case DUPLICATE_DOCUMENT -> "documento duplicado o muy redundante";
            case UNUSED_DOCUMENT -> "documento sin uso reciente";
            default -> "hallazgo de mantenimiento";
        };

        return """
                Eres un arquitecto de ingesta RAG.
                Debes decidir la accion minima y segura para mejorar el corpus.
                Evalua si conviene mantener, reestructurar o eliminar un documento con """ + scenario + """
                .
                Regla de calidad:
                - RESTRUCTURE si el documento es util pero esta mal formado y puedes dejarlo mejor para retrieval.
                - DELETE si el documento sobra, esta duplicado o no aporta valor real.
                - KEEP solo si sigue siendo valido tal y como esta.
                La estructura ideal para RAG debe respetar:
                - documents(doc_id, title, source, created_at, ...)
                - chunks(chunk_id, doc_id, chunk_index, text, hash, token_count, created_at, source, tags...)
                - vectors(chunk_id -> embedding) en HNSW
                El normalizedContent debe ser solo cuerpo limpio, sin ruido, sin repeticiones, con frases informativas y secciones claras.
                Devuelve solo JSON valido con este esquema exacto:
                {"decision":"DELETE|RESTRUCTURE|KEEP","reason":"texto corto","normalizedContent":"texto o vacio"}
                No uses markdown. No anadas texto fuera del JSON.
                """;
    }

    private String buildUserPrompt(RagMaintenanceCase ragCase) {
        return """
                Documento auditado:
                - id: %s
                - owner: %s
                - titulo: %s
                - severidad: %s
                - problema: %s
                - recomendacion inicial: %s
                - uso historico: %s
                - ultima vez usado: %s
                - vence revision admin: %s

                Resumen del hallazgo:
                %s

                Fragmento original:
                %s

                Propuesta estructurada preliminar:
                %s
                """.formatted(
                ragCase.getId(),
                nullSafe(ragCase.getOwner()),
                nullSafe(ragCase.getDocumentTitle()),
                ragCase.getSeverity(),
                ragCase.getIssueType(),
                ragCase.getRecommendedAction(),
                ragCase.getUsageCount(),
                nullSafe(ragCase.getLastUsedAt()),
                nullSafe(ragCase.getAdminDueAt()),
                clip(ragCase.getSummary()),
                clip(ragCase.getOriginalSnippet()),
                clip(ragCase.getProposedContent())
        );
    }

    private DecisionPayload parsePayload(String raw) throws Exception {
        String candidate = raw == null ? "" : raw.trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start >= 0 && end > start) {
            candidate = candidate.substring(start, end + 1);
        }
        return objectMapper.readValue(candidate, DecisionPayload.class);
    }

    private RagMaintenanceAction fallbackAction(RagMaintenanceCase ragCase) {
        if (ragCase == null || ragCase.getSeverity() == null) {
            return RagMaintenanceAction.KEEP;
        }
        if (ragCase.getSeverity() == RagMaintenanceSeverity.CRITICAL) {
            return RagMaintenanceAction.DELETE;
        }
        return ragCase.getRecommendedAction() == null ? RagMaintenanceAction.KEEP : ragCase.getRecommendedAction();
    }

    private RagMaintenanceAction parseAction(String raw, RagMaintenanceAction fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return RagMaintenanceAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private String clip(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.trim();
        if (clean.length() <= MAX_CONTENT_CHARS) {
            return clean;
        }
        return clean.substring(0, MAX_CONTENT_CHARS).trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String nullSafe(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().trim();
    }

    private static final class DecisionPayload {
        public String decision;
        public String reason;
        public String normalizedContent;
    }

    public record Advice(RagMaintenanceAction action,
                         String reason,
                         String normalizedContent,
                         String rawResponse,
                         String model) {

        static Advice fallback(RagMaintenanceAction action,
                               String reason,
                               String normalizedContent,
                               String model) {
            return new Advice(action, reason, normalizedContent, "", model);
        }
    }
}
