package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.KnowledgeDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio del nivel documents del RAG.
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findFirstByOwnerAndTitleIgnoreCaseAndActiveTrue(String owner, String title);

    long countByActiveTrue();

    long countByOwnerAndActiveTrue(String owner);

    long countByOwnerInAndActiveTrue(Collection<String> owners);

    @Query("""
        select max(coalesce(d.updatedAt, d.createdAt))
        from KnowledgeDocument d
        where d.active = true
          and d.owner in :owners
    """)
    Instant findLastUpdateAtByOwners(@Param("owners") Collection<String> owners);

    @Query("""
        select max(coalesce(d.updatedAt, d.createdAt))
        from KnowledgeDocument d
        where d.active = true
    """)
    Instant findLastActiveUpdateAt();

    @Query("""
        select d
        from KnowledgeDocument d
        where d.active = true
          and (:beforeId is null or d.id < :beforeId)
        order by d.id desc
    """)
    List<KnowledgeDocument> findSweepPage(@Param("beforeId") Long beforeId, Pageable pageable);

    @Query("""
        select d
        from KnowledgeDocument d
        where d.active = true
        order by coalesce(d.createdAt, d.updatedAt) asc, d.id asc
    """)
    List<KnowledgeDocument> findOldestActive(Pageable pageable);

    @Query("""
        select coalesce(sum(length(d.title) + length(d.owner) + length(d.source) + length(coalesce(d.referenceUrl, ''))), 0)
        from KnowledgeDocument d
        where d.active = true
    """)
    Long sumMetadataLength();

    @Query("""
        select count(d)
        from KnowledgeDocument d
        where d.active = true
          and d.owner in :owners
          and (
            lower(d.title) like concat('%', lower(:term), '%')
            or lower(d.source) like concat('%', lower(:term), '%')
            or lower(coalesce(d.referenceUrl, '')) like concat('%', lower(:term), '%')
          )
    """)
    long countActiveMetadataMatches(@Param("owners") Collection<String> owners, @Param("term") String term);

    /**
     * Devuelve true si ALGUNO de los terminos dados aparece en el metadata activo.
     * Una sola query en lugar de N queries individuales (patron batch).
     * Usa REGEXP de MySQL para evaluar todos los terminos en una sola pasada.
     */
    @Query(value = """
        SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
        FROM knowledge_documents d
        WHERE d.active = 1
          AND d.owner IN :owners
          AND (
            REGEXP_LIKE(d.title,  :pattern, 'i')
            OR REGEXP_LIKE(d.source, :pattern, 'i')
            OR REGEXP_LIKE(IFNULL(d.reference_url, ''), :pattern, 'i')
          )
        LIMIT 1
    """, nativeQuery = true)
    boolean existsAnyActiveMetadataMatch(@Param("owners") Collection<String> owners,
                                         @Param("pattern") String pattern);
}


