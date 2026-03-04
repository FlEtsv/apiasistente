package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.RagMaintenanceCase;
import com.example.apiasistente.rag.entity.RagMaintenanceCaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio de la cola de decision del robot RAG.
 */
public interface RagMaintenanceCaseRepository extends JpaRepository<RagMaintenanceCase, Long> {

    Optional<RagMaintenanceCase> findFirstByDocumentIdAndStatusInOrderByCreatedAtDesc(Long documentId,
                                                                                       Collection<RagMaintenanceCaseStatus> statuses);

    List<RagMaintenanceCase> findTop50ByStatusInOrderByCreatedAtDesc(Collection<RagMaintenanceCaseStatus> statuses);

    List<RagMaintenanceCase> findTop100ByStatusAndAdminDueAtBeforeOrderByAdminDueAtAsc(RagMaintenanceCaseStatus status,
                                                                                        Instant adminDueAt);

    List<RagMaintenanceCase> findTop100ByStatusOrderByAdminDueAtAscCreatedAtAsc(RagMaintenanceCaseStatus status);

    List<RagMaintenanceCase> findTop100ByStatusAndAutoApplyAtBeforeOrderByAutoApplyAtAsc(RagMaintenanceCaseStatus status,
                                                                                          Instant autoApplyAt);

    long countByStatus(RagMaintenanceCaseStatus status);

    long countByStatusIn(Collection<RagMaintenanceCaseStatus> statuses);
}
