package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.KnowledgeVector;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio de la persistencia durable de embeddings.
 */
public interface KnowledgeVectorRepository extends JpaRepository<KnowledgeVector, Long> {

    interface VectorPayloadView {
        Long getChunkId();
        String getEmbeddingJson();
    }

    interface IndexedVectorView {
        Long getChunkId();
        String getOwner();
        String getEmbeddingJson();
    }

    @Query("""
        select v.chunkId as chunkId, v.embeddingJson as embeddingJson
        from KnowledgeVector v
        where v.chunkId in :chunkIds
    """)
    List<VectorPayloadView> findPayloadByChunkIds(@Param("chunkIds") Collection<Long> chunkIds);

    @Query("""
        select v.chunkId as chunkId, v.chunk.document.owner as owner, v.embeddingJson as embeddingJson
        from KnowledgeVector v
        where v.chunk.document.active = true
    """)
    Slice<IndexedVectorView> findActiveIndexPage(Pageable pageable);

    @Query("""
        select coalesce(sum(length(v.embeddingJson)), 0)
        from KnowledgeVector v
        join v.chunk c
        join c.document d
        where d.active = true
    """)
    Long sumEmbeddingLength();

    void deleteByChunkIdIn(Collection<Long> chunkIds);
}
