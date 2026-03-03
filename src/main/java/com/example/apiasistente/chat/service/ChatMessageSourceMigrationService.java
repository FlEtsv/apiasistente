package com.example.apiasistente.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Migra `chat_message_source` desde el modelo legacy con FK viva a `chunks`
 * hacia el modelo snapshot estable que ya no depende del corpus actual.
 */
@Service
public class ChatMessageSourceMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageSourceMigrationService.class);
    private static final String TABLE = "chat_message_source";

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageSourceMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyChunkReferences() {
        if (!tableExists(TABLE)) {
            return;
        }
        if (!columnExists(TABLE, "chunk_id")) {
            return;
        }
        if (!columnExists(TABLE, "source_chunk_id")
                || !columnExists(TABLE, "source_document_id")
                || !columnExists(TABLE, "source_document_title")
                || !columnExists(TABLE, "source_snippet")) {
            return;
        }

        int migratedRows = backfillSnapshotColumns();
        int droppedConstraints = dropLegacyChunkForeignKeys();
        if (migratedRows > 0 || droppedConstraints > 0) {
            log.info(
                    "ChatMessageSource migrado a snapshot: filas_backfilled={} foreign_keys_eliminadas={}",
                    migratedRows,
                    droppedConstraints
            );
        }
    }

    private int backfillSnapshotColumns() {
        try {
            return jdbcTemplate.update("""
                    update chat_message_source s
                    join chunks c on c.chunk_id = s.chunk_id
                    join documents d on d.doc_id = c.doc_id
                    set s.source_chunk_id = coalesce(s.source_chunk_id, c.chunk_id),
                        s.source_document_id = coalesce(s.source_document_id, d.doc_id),
                        s.source_document_title = case
                            when s.source_document_title is null or trim(s.source_document_title) = '' then d.title
                            else s.source_document_title
                        end,
                        s.source_snippet = case
                            when s.source_snippet is null or trim(s.source_snippet) = '' then c.text
                            else s.source_snippet
                        end
                    where s.chunk_id is not null
                      and (
                          s.source_chunk_id is null
                          or s.source_document_id is null
                          or s.source_document_title is null
                          or trim(s.source_document_title) = ''
                          or s.source_snippet is null
                          or trim(s.source_snippet) = ''
                      )
                    """);
        } catch (Exception e) {
            log.warn("No se pudieron backfillear snapshots en chat_message_source", e);
            return 0;
        }
    }

    private int dropLegacyChunkForeignKeys() {
        List<String> foreignKeys;
        try {
            foreignKeys = jdbcTemplate.queryForList("""
                    select constraint_name
                    from information_schema.key_column_usage
                    where table_schema = database()
                      and lower(table_name) = lower(?)
                      and lower(column_name) = lower(?)
                      and lower(referenced_table_name) = lower(?)
                    """, String.class, TABLE, "chunk_id", "chunks");
        } catch (Exception e) {
            log.warn("No se pudieron inspeccionar foreign keys legacy de chat_message_source", e);
            return 0;
        }

        int dropped = 0;
        for (String foreignKey : foreignKeys) {
            if (foreignKey == null || foreignKey.isBlank()) {
                continue;
            }
            try {
                jdbcTemplate.execute("alter table " + TABLE + " drop foreign key `" + foreignKey.replace("`", "``") + "`");
                dropped++;
            } catch (Exception e) {
                log.warn("No se pudo eliminar la foreign key legacy {} de {}", foreignKey, TABLE, e);
            }
        }
        return dropped;
    }

    private boolean tableExists(String tableName) {
        return existsInInformationSchema("tables", "table_name", tableName);
    }

    private boolean columnExists(String tableName, String columnName) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    select count(*)
                    from information_schema.columns
                    where table_schema = database()
                      and lower(table_name) = lower(?)
                      and lower(column_name) = lower(?)
                    """, Integer.class, tableName, columnName);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("No se pudo verificar si existe {}.{}", tableName, columnName, e);
            return false;
        }
    }

    private boolean existsInInformationSchema(String infoSchemaTable, String fieldName, String value) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema." + infoSchemaTable
                            + " where table_schema = database() and lower(" + fieldName + ") = lower(?)",
                    Integer.class,
                    value
            );
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("No se pudo verificar {} en information_schema.{}", value, infoSchemaTable, e);
            return false;
        }
    }
}
