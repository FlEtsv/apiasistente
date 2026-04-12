package com.example.apiasistente.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
    private static final String LEGACY_CHUNK_COLUMN = "chunk_id";

    private final JdbcTemplate jdbcTemplate;

    public ChatMessageSourceMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyChunkReferences() {
        if (!tableExists(TABLE)) {
            return;
        }
        if (!columnExists(TABLE, LEGACY_CHUNK_COLUMN)) {
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
        boolean droppedLegacyColumn = dropLegacyChunkColumn();
        boolean relaxedLegacyColumn = !droppedLegacyColumn && relaxLegacyChunkColumnNullability();
        if (migratedRows > 0 || droppedConstraints > 0 || droppedLegacyColumn || relaxedLegacyColumn) {
            log.info(
                    "ChatMessageSource migrado a snapshot: filas_backfilled={} foreign_keys_eliminadas={} columna_chunk_eliminada={} columna_chunk_nullable={}",
                    migratedRows,
                    droppedConstraints,
                    droppedLegacyColumn,
                    relaxedLegacyColumn
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
                    select distinct constraint_name
                    from information_schema.key_column_usage
                    where table_schema = database()
                      and lower(table_name) = lower(?)
                      and lower(column_name) = lower(?)
                      and referenced_table_name is not null
                    """, String.class, TABLE, LEGACY_CHUNK_COLUMN);
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

    private boolean dropLegacyChunkColumn() {
        if (!columnExists(TABLE, LEGACY_CHUNK_COLUMN)) {
            return false;
        }
        try {
            jdbcTemplate.execute("alter table " + TABLE + " drop column `" + LEGACY_CHUNK_COLUMN + "`");
            return true;
        } catch (Exception e) {
            log.warn("No se pudo eliminar la columna legacy {}.{}", TABLE, LEGACY_CHUNK_COLUMN, e);
            return false;
        }
    }

    private boolean relaxLegacyChunkColumnNullability() {
        String columnType = columnType(TABLE, LEGACY_CHUNK_COLUMN);
        if (columnType == null || columnType.isBlank()) {
            return false;
        }
        try {
            jdbcTemplate.execute(
                    "alter table " + TABLE + " modify column `" + LEGACY_CHUNK_COLUMN + "` " + columnType + " null"
            );
            return true;
        } catch (Exception e) {
            log.warn("No se pudo dejar nullable la columna legacy {}.{}", TABLE, LEGACY_CHUNK_COLUMN, e);
            return false;
        }
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

    private String columnType(String tableName, String columnName) {
        try {
            return jdbcTemplate.queryForObject("""
                    select column_type
                    from information_schema.columns
                    where table_schema = database()
                      and lower(table_name) = lower(?)
                      and lower(column_name) = lower(?)
                    """, String.class, tableName, columnName);
        } catch (Exception e) {
            log.debug("No se pudo obtener el tipo de {}.{}", tableName, columnName, e);
            return null;
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
