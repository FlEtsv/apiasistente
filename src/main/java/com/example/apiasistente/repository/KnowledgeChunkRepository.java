// repository/KnowledgeChunkRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.KnowledgeChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    @Query("select c.id as id, c.embeddingJson as embeddingJson from KnowledgeChunk c")
    Page<ChunkEmbeddingView> findEmbeddingPage(Pageable pageable);

    @Query("select c from KnowledgeChunk c join fetch c.document where c.id in :ids")
    List<KnowledgeChunk> findWithDocumentByIdIn(@Param("ids") List<Long> ids);

    interface ChunkEmbeddingView {
        Long getId();
        String getEmbeddingJson();
    }
}
