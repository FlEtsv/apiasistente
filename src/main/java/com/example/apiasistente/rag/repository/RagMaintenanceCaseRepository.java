package com.example.apiasistente.rag.repository;

import com.example.apiasistente.rag.entity.RagMaintenanceCase;
import com.example.apiasistente.rag.entity.RagMaintenanceCaseStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Query("""
            select count(c)
            from RagMaintenanceCase c
            where c.status = :status
              and (c.adminDueAt is null or c.adminDueAt > :now)
            """)
    long countEligibleForBacklogAcceleration(@Param("status") RagMaintenanceCaseStatus status,
                                             @Param("now") Instant now);

    @Query("""
            select c
            from RagMaintenanceCase c
            where c.status = :status
              and (c.adminDueAt is null or c.adminDueAt > :now)
            order by
              case when c.adminDueAt is null then 0 else 1 end,
              c.adminDueAt asc,
              c.createdAt asc
            """)
    List<RagMaintenanceCase> findEligibleForBacklogAcceleration(@Param("status") RagMaintenanceCaseStatus status,
                                                                @Param("now") Instant now,
                                                                Pageable pageable);
}
