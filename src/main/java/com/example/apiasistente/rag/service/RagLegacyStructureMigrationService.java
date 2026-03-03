package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Migracion ligera desde la estructura vieja (`knowledge_document`) al nuevo esquema canonico.
 *
 * Importante:
 * - Solo corre si la estructura legacy existe.
 * - Solo migra automatico cuando el nuevo esquema aun no tiene documentos activos.
 * - No borra tablas antiguas; las deja como respaldo hasta que valides la migracion.
 */
@Service
public class RagLegacyStructureMigrationService {

    private static final Logger log = LoggerFactory.getLogger(RagLegacyStructureMigrationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentRepository documentRepository;
    private final RagService ragService;
    private final boolean migrationEnabled;

    public RagLegacyStructureMigrationService(JdbcTemplate jdbcTemplate,
                                              KnowledgeDocumentRepository documentRepository,
                                              RagService ragService,
                                              @Value("${rag.migration.legacy-enabled:true}") boolean migrationEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.ragService = ragService;
        this.migrationEnabled = migrationEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyDocumentsIfNeeded() {
        if (!migrationEnabled) {
            return;
        }
        if (documentRepository.countByActiveTrue() > 0) {
            return;
        }
        if (!tableExists("knowledge_document")) {
            return;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id, owner, title, content
                from knowledge_document
                order by id asc
                """);
        if (rows.isEmpty()) {
            return;
        }

        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String title = asText(row.get("title"));
            String content = asText(row.get("content"));
            if (title == null || content == null) {
                continue;
            }
            String owner = asText(row.get("owner"));
            ragService.upsertDocumentForOwner(owner, title, content, "legacy-migration", "legacy");
            migrated++;
        }

        log.info("Migracion legacy RAG completada: {} documentos absorbidos en documents/chunks/vectors", migrated);
    }

    private boolean tableExists(String tableName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    select count(*)
                    from information_schema.tables
                    where lower(table_name) = lower(?)
                    """, Integer.class, tableName);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("No se pudo verificar si existe la tabla legacy {}", tableName, e);
            return false;
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
