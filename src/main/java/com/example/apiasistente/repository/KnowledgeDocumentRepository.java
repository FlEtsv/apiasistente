package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findFirstByOwnerAndTitleIgnoreCase(String owner, String title);

    long countByOwner(String owner);

    long countByOwnerIn(Collection<String> owners);

    @Query("""
        select max(coalesce(d.updatedAt, d.createdAt))
        from KnowledgeDocument d
        where d.owner in :owners
    """)
    Instant findLastUpdateAtByOwners(@Param("owners") Collection<String> owners);
}
