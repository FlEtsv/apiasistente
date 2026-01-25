// repository/KnowledgeDocumentRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {}
