package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    interface EmbeddingView {
        Long getId();
        String getEmbeddingJson();
    }

    // Existente (si lo tienes)
    @Query("""
        select c.id as id, c.embeddingJson as embeddingJson
        from KnowledgeChunk c
    """)
    Slice<EmbeddingView> findEmbeddingPage(Pageable pageable);

    // NUEVO: filtrar por owners (ej: ["global", "steven"])
    @Query("""
        select c.id as id, c.embeddingJson as embeddingJson
        from KnowledgeChunk c
        where c.document.owner in :owners
    """)
    Slice<EmbeddingView> findEmbeddingPageByOwners(@Param("owners") List<String> owners, Pageable pageable);

    // Para traer chunks con documento
    List<KnowledgeChunk> findWithDocumentByIdIn(List<Long> ids);

    // NUEVO: borrar chunks de un doc al re-upsert
    void deleteByDocument_Id(Long documentId);

    // NUEVO: sacar ids antes de borrar para limpiar cache
    @Query("select c.id from KnowledgeChunk c where c.document.id = :docId")
    List<Long> findIdsByDocumentId(@Param("docId") Long docId);
}
