package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findFirstByOwnerAndTitleIgnoreCase(String owner, String title);
}
