package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.KnowledgeDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /** Busqueda de metadata sin filtro de propietario: busca en todo el corpus compartido. */
    @Query("""
        select count(d)
        from KnowledgeDocument d
        where d.active = true
          and (
            lower(d.title) like concat('%', lower(:term), '%')
            or lower(d.source) like concat('%', lower(:term), '%')
            or lower(coalesce(d.referenceUrl, '')) like concat('%', lower(:term), '%')
          )
    """)
    long countActiveMetadataMatchesAll(@Param("term") String term);

    @Transactional
    @Modifying
    @Query(value = "TRUNCATE TABLE documents", nativeQuery = true)
    void truncateAll();
}


