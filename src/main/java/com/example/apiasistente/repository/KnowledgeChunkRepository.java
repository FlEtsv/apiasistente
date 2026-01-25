// repository/KnowledgeChunkRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {}
