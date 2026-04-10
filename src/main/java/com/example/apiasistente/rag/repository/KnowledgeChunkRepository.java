package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio del nivel chunks del RAG.
 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.active = true
    """)
    long countActive();

    /**
     * Carga chunks junto a su documento para el tramo final del retrieval.
     */
    @Query("""
        select c
        from KnowledgeChunk c
        join fetch c.document d
        where d.active = true
          and c.id in :ids
    """)
    List<KnowledgeChunk> findActiveWithDocumentByIdIn(@Param("ids") List<Long> ids);

    /**
     * Variante sin filtrar estado usada por mantenimiento y borrado duro.
     */
    @Query("""
        select c
        from KnowledgeChunk c
        join fetch c.document d
        where c.id in :ids
    """)
    List<KnowledgeChunk> findWithDocumentByIdIn(@Param("ids") List<Long> ids);

    void deleteByDocument_Id(Long documentId);

    @Query("select c.id from KnowledgeChunk c where c.document.id = :docId")
    List<Long> findIdsByDocumentId(@Param("docId") Long docId);

    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.active = true
          and c.document.owner = :owner
    """)
    long countActiveByOwner(@Param("owner") String owner);

    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.active = true
          and c.document.owner in :owners
    """)
    long countActiveByOwners(@Param("owners") List<String> owners);

    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.id = :documentId
    """)
    long countByDocumentId(@Param("documentId") Long documentId);

    @Query("""
        select c
        from KnowledgeChunk c
        where c.document.id = :documentId
          and c.document.active = true
        order by c.chunkIndex asc
    """)
    List<KnowledgeChunk> findActiveByDocumentIdOrderByChunkIndexAsc(@Param("documentId") Long documentId);

    @Query("""
        select coalesce(sum(length(c.text)), 0)
        from KnowledgeChunk c
        where c.document.active = true
    """)
    Long sumTextLength();

    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.active = true
          and c.document.owner in :owners
          and c.tags is not null
          and lower(c.tags) like concat('%', lower(:term), '%')
    """)
    long countActiveTagMatches(@Param("owners") List<String> owners, @Param("term") String term);

    /** Busqueda de tags sin filtro de propietario: busca en todo el corpus compartido. */
    @Query("""
        select count(c)
        from KnowledgeChunk c
        where c.document.active = true
          and c.tags is not null
          and lower(c.tags) like concat('%', lower(:term), '%')
    """)
    long countActiveTagMatchesAll(@Param("term") String term);
}


