package com.example.apiasistente.rag.service;

import com.example.apiasistente.chat.repository.ChatMessageSourceRepository;
import com.example.apiasistente.rag.config.RagMaintenanceProperties;
import com.example.apiasistente.rag.dto.RagMaintenanceCaseDecisionRequest;
import com.example.apiasistente.rag.dto.RagMaintenanceCaseDto;
import com.example.apiasistente.rag.dto.RagMaintenanceConfigRequest;
import com.example.apiasistente.rag.dto.RagMaintenanceCorpusDto;
import com.example.apiasistente.rag.dto.RagMaintenanceEventDto;
import com.example.apiasistente.rag.dto.RagMaintenanceRunDto;
import com.example.apiasistente.rag.dto.RagMaintenanceStatusDto;
import com.example.apiasistente.rag.entity.KnowledgeChunk;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.entity.RagMaintenanceAction;
import com.example.apiasistente.rag.entity.RagMaintenanceCase;
import com.example.apiasistente.rag.entity.RagMaintenanceCaseStatus;
import com.example.apiasistente.rag.entity.RagMaintenanceIssueType;
import com.example.apiasistente.rag.entity.RagMaintenanceSeverity;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import com.example.apiasistente.rag.repository.RagMaintenanceCaseRepository;
import com.example.apiasistente.rag.util.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Robot que mantiene el corpus RAG compacto, auditable y con cola de decision.
 */
@Service
public class RagMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(RagMaintenanceService.class);
    private static final int MIN_INTERVAL_SECONDS = 15;
    private static final int MAX_INTERVAL_SECONDS = 86400;
    private static final int DEFAULT_STATUS_EVENTS = 10;
    private static final int MAX_CASES_RETURNED = 50;
    private static final EnumSet<RagMaintenanceCaseStatus> ACTIVE_CASE_STATUSES = EnumSet.of(
            RagMaintenanceCaseStatus.OPEN,
            RagMaintenanceCaseStatus.AI_REVIEWED
    );
    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "y", "o", "u", "en", "por", "para", "con", "sin", "del", "al",
            "que", "como", "donde", "cuando", "cual", "cuales", "quien", "quienes", "porque", "sobre",
            "the", "and", "or", "for", "with", "from", "this", "that", "those", "these", "into", "your", "you"
    );

    private final RagMaintenanceProperties properties;
    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeVectorRepository vectorRepo;
    private final RagMaintenanceCaseRepository caseRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final RagMaintenanceAdvisorService advisorService;
    private final RagService ragService;
    private final RagVectorIndexService vectorIndexService;
    private final int ragChunkSize;
    private final int ragChunkOverlap;

    private final Deque<RagMaintenanceEventDto> events = new ArrayDeque<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean dryRun = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong intervalMs = new AtomicLong(180000);

    private volatile Instant lastStartedAt;
    private volatile Instant lastCompletedAt;
    private volatile String currentStep = "Idle";
    private volatile String currentDocumentTitle;
    private volatile RagMaintenanceRunDto lastRun = RagMaintenanceRunDto.empty();
    private volatile RagMaintenanceCorpusDto lastKnownCorpus;

    public RagMaintenanceService(RagMaintenanceProperties properties,
                                 KnowledgeDocumentRepository docRepo,
                                 KnowledgeChunkRepository chunkRepo,
                                 KnowledgeVectorRepository vectorRepo,
                                 RagMaintenanceCaseRepository caseRepo,
                                 ChatMessageSourceRepository sourceRepo,
                                 RagMaintenanceAdvisorService advisorService,
                                 RagVectorIndexService vectorIndexService,
                                 RagService ragService,
                                 @Value("${rag.chunk.size:900}") int ragChunkSize,
                                 @Value("${rag.chunk.overlap:150}") int ragChunkOverlap) {
        this.properties = properties;
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.vectorRepo = vectorRepo;
        this.caseRepo = caseRepo;
        this.sourceRepo = sourceRepo;
        this.advisorService = advisorService;
        this.vectorIndexService = vectorIndexService;
        this.ragService = ragService;
        this.ragChunkSize = ragChunkSize;
        this.ragChunkOverlap = ragChunkOverlap;
        this.dryRun.set(properties.isDryRun());
        this.intervalMs.set(clampIntervalMillis(properties.getIntervalMs()));
    }

    @Scheduled(fixedDelayString = "${rag.maintenance.tick-ms:15000}")
    public void scheduledSweep() {
        if (!properties.isEnabled() || paused.get() || running.get()) {
            return;
        }

        Instant nextRunAt = computeNextRunAt();
        if (nextRunAt != null && Instant.now().isBefore(nextRunAt)) {
            return;
        }

        runSweep("AUTO", false);
    }

    @Scheduled(fixedDelayString = "${rag.maintenance.tick-ms:15000}", initialDelayString = "${rag.maintenance.tick-ms:15000}")
    public void scheduledCaseFollowUp() {
        if (!properties.isEnabled() || paused.get() || running.get()) {
            return;
        }
        processPendingCases();
    }

    public RagMaintenanceStatusDto status() {
        RagMaintenanceCorpusDto corpus = lastKnownCorpus;
        if (corpus == null) {
            corpus = snapshotCorpusQuietly();
            lastKnownCorpus = corpus;
        }
        return new RagMaintenanceStatusDto(
                properties.isEnabled(),
                paused.get(),
                dryRun.get(),
                running.get(),
                intervalMs.get(),
                lastStartedAt,
                lastCompletedAt,
                computeNextRunAt(),
                currentStep,
                currentDocumentTitle,
                corpus,
                lastRun,
                recentEvents(DEFAULT_STATUS_EVENTS)
        );
    }

    public List<RagMaintenanceCaseDto> listCases(boolean includeResolved) {
        List<RagMaintenanceCase> cases;
        if (includeResolved) {
            cases = caseRepo.findAll(PageRequest.of(
                    0,
                    MAX_CASES_RETURNED,
                    Sort.by(Sort.Order.desc("createdAt"))
            )).getContent();
        } else {
            cases = caseRepo.findTop50ByStatusInOrderByCreatedAtDesc(ACTIVE_CASE_STATUSES);
        }
        return cases.stream().map(this::toDto).toList();
    }

    @Transactional
    public RagMaintenanceCaseDto decideCase(Long caseId,
                                            RagMaintenanceCaseDecisionRequest request,
                                            String actor) {
        if (caseId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "caseId es obligatorio.");
        }
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "action es obligatorio.");
        }

        RagMaintenanceCase ragCase = caseRepo.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Caso no encontrado."));

        RagMaintenanceAction action = parseAction(request.getAction());
        String contentOverride = trimToNull(request.getProposedContent());
        String notes = trimToNull(request.getNotes());
        String resolvedBy = trimToNull(actor) == null ? "admin" : actor.trim();

        if (action == RagMaintenanceAction.KEEP) {
            markResolved(ragCase, RagMaintenanceAction.KEEP, resolvedBy, notes == null ? "Admin decidio conservar el documento." : notes);
            return toDto(caseRepo.save(ragCase));
        }

        executeCaseAction(
                ragCase,
                action,
                contentOverride,
                resolvedBy,
                notes == null ? "Admin ejecuto una accion manual." : notes,
                true
        );
        return toDto(caseRepo.save(ragCase));
    }

    public RagMaintenanceStatusDto pause() {
        if (paused.compareAndSet(false, true)) {
            currentStep = running.get() ? currentStep : "Pausado";
            recordEvent("INFO", "PAUSED", "Robot pausado", "El barrido automatico queda detenido hasta reanudarlo.");
        }
        return status();
    }

    public RagMaintenanceStatusDto resume() {
        if (paused.compareAndSet(true, false)) {
            currentStep = running.get() ? currentStep : "Idle";
            recordEvent("INFO", "RESUMED", "Robot reanudado", "El barrido automatico vuelve a ejecutarse en segundo plano.");
        }
        return status();
    }

    public RagMaintenanceStatusDto updateConfig(RagMaintenanceConfigRequest request) {
        if (request == null) {
            return status();
        }

        if (request.getDryRun() != null) {
            boolean nextDryRun = request.getDryRun();
            boolean previous = dryRun.getAndSet(nextDryRun);
            if (previous != nextDryRun) {
                recordEvent(
                        "INFO",
                        "CONFIG_DRY_RUN",
                        "Modo del robot actualizado",
                        nextDryRun ? "Ahora solo analiza y no borra datos." : "Ahora puede ejecutar limpieza real."
                );
            }
        }

        if (request.getIntervalSeconds() != null) {
            long nextIntervalMs = clampIntervalMillis(request.getIntervalSeconds().longValue() * 1000L);
            long previous = intervalMs.getAndSet(nextIntervalMs);
            if (previous != nextIntervalMs) {
                recordEvent(
                        "INFO",
                        "CONFIG_INTERVAL",
                        "Intervalo actualizado",
                        "Nuevo intervalo automatico: " + (nextIntervalMs / 1000L) + " s."
                );
            }
        }

        return status();
    }

    public RagMaintenanceStatusDto runManualSweep() {
        return runSweep("MANUAL", true);
    }

    RagMaintenanceStatusDto runSweepBlockingForTest(String trigger) {
        return runSweep(trigger, true);
    }

    private RagMaintenanceStatusDto runSweep(String trigger, boolean allowWhenPaused) {
        if (!allowWhenPaused && paused.get()) {
            return status();
        }
        if (!running.compareAndSet(false, true)) {
            return status();
        }

        Instant startedAt = Instant.now();
        RagMaintenanceCorpusDto before = snapshotCorpusQuietly();
        RunAccumulator acc = new RunAccumulator(trigger, dryRun.get(), startedAt, before);

        lastStartedAt = startedAt;
        currentStep = "Preparando barrido";
        currentDocumentTitle = null;
        recordEvent(
                "INFO",
                "RUN_STARTED",
                "Barrido " + trigger.toLowerCase(Locale.ROOT),
                dryRun.get()
                        ? "Analizando corpus y cola de decisiones sin aplicar cambios."
                        : "Escaneando corpus y gestionando la cola de decisiones."
        );

        try {
            currentStep = "Escaneando corpus";
            Map<String, Set<String>> fingerprintsByOwner = new HashMap<>();
            Long beforeId = null;

            while (true) {
                List<KnowledgeDocument> docs = docRepo.findSweepPage(
                        beforeId,
                        PageRequest.of(0, Math.max(1, properties.getPageSize()))
                );
                if (docs.isEmpty()) {
                    break;
                }

                beforeId = docs.get(docs.size() - 1).getId();
                for (KnowledgeDocument doc : docs) {
                    if (doc == null || doc.getId() == null) {
                        continue;
                    }
                    currentStep = "Analizando documento";
                    currentDocumentTitle = doc.getTitle();
                    try {
                        processDocument(doc, fingerprintsByOwner, acc);
                    } catch (Exception docError) {
                        recordEvent(
                                "ERROR",
                                "DOC_ERROR",
                                safeTitle(doc),
                                "Error revisando documento: " + safeMessage(docError)
                        );
                        log.warn("Error revisando documento RAG id={} title='{}'", doc.getId(), doc.getTitle(), docError);
                    }
                }
            }

            currentStep = "Procesando cola";
            processPendingCases(acc);

            Instant completedAt = Instant.now();
            RagMaintenanceCorpusDto after = dryRun.get() ? before : snapshotCorpusQuietly();
            lastCompletedAt = completedAt;
            lastKnownCorpus = after;
            lastRun = acc.success(completedAt, after);
            recordEvent("INFO", "RUN_FINISHED", "Barrido completado", lastRun.summary());
        } catch (Exception e) {
            Instant completedAt = Instant.now();
            lastCompletedAt = completedAt;
            lastKnownCorpus = snapshotCorpusQuietly();
            lastRun = acc.failure(completedAt, safeMessage(e));
            recordEvent("ERROR", "RUN_FAILED", "Barrido fallido", safeMessage(e));
            log.warn("Fallo el barrido de mantenimiento RAG", e);
        } finally {
            currentDocumentTitle = null;
            currentStep = paused.get() ? "Pausado" : "Idle";
            running.set(false);
        }

        return status();
    }

    private void processDocument(KnowledgeDocument doc,
                                 Map<String, Set<String>> fingerprintsByOwner,
                                 RunAccumulator acc) {
        // El documento completo se reconstituye desde chunks porque ya no persistimos `content` como fuente canonica.
        List<KnowledgeChunk> chunks = new ArrayList<>(chunkRepo.findActiveByDocumentIdOrderByChunkIndexAsc(doc.getId()));
        chunks.sort(Comparator.comparingInt(KnowledgeChunk::getChunkIndex));
        acc.recordScan(chunks.size());

        DocumentAssessment assessment = assessDocument(doc, chunks, fingerprintsByOwner);
        Optional<RagMaintenanceCase> activeCase = findActiveCase(doc.getId());

        if (assessment == null) {
            activeCase.ifPresent(existing -> {
                markResolved(existing, RagMaintenanceAction.KEEP, "robot", "El documento volvio a estar apto.");
                caseRepo.save(existing);
                recordEvent("INFO", "CASE_RESOLVED", safeTitle(doc), "Documento saneado o corregido manualmente.");
            });
            return;
        }

        RagMaintenanceCase ragCase = activeCase.orElseGet(RagMaintenanceCase::new);
        boolean resetDecisionState = shouldResetDecisionState(ragCase, assessment);
        boolean isNewCase = ragCase.getId() == null;

        hydrateCase(ragCase, doc, assessment, resetDecisionState || isNewCase);
        appendAudit(ragCase, "ROBOT", "Hallazgo detectado: " + assessment.summary());
        ragCase = caseRepo.save(ragCase);

        if (isNewCase) {
            acc.recordCaseOpened();
            recordEvent(
                    assessment.severity() == RagMaintenanceSeverity.CRITICAL ? "WARN" : "INFO",
                    "CASE_OPENED",
                    safeTitle(doc),
                    assessment.summary()
            );
        }

        if (assessment.severity() == RagMaintenanceSeverity.CRITICAL) {
            reviewCaseWithAi(ragCase, true, acc);
        }
    }

    private DocumentAssessment assessDocument(KnowledgeDocument doc,
                                              List<KnowledgeChunk> chunks,
                                              Map<String, Set<String>> fingerprintsByOwner) {
        String owner = normalizeOwner(doc.getOwner());
        long usageCount = sourceRepo.countByChunk_Document_Id(doc.getId());
        Instant lastUsedAt = sourceRepo.findLastUsedAtByDocumentId(doc.getId());
        String documentText = RagService.rebuildDocumentText(chunks);
        CleanContentResult cleaned = cleanContent(documentText);
        String normalizedFingerprint = fingerprint(cleaned.content());
        String snippet = snippet(documentText);
        String proposedContent = buildIdealStructure(doc, chunks, cleaned.content());
        List<KnowledgeChunk> prunableChunks = findPrunableChunks(chunks);
        boolean malformedStructure = hasMalformedStructure(doc, chunks, cleaned.content());

        if (chunks.isEmpty()) {
            return new DocumentAssessment(
                    RagMaintenanceSeverity.CRITICAL,
                    RagMaintenanceIssueType.LOW_VALUE_CONTENT,
                    "Documento sin chunks persistidos; no sirve para retrieval.",
                    RagMaintenanceAction.DELETE,
                    snippet,
                    null,
                    usageCount,
                    lastUsedAt
            );
        }

        if (normalizedFingerprint.isBlank() || !hasMeaningfulDocument(cleaned.content())) {
            RagMaintenanceIssueType issueType = looksUnreadable(documentText)
                    ? RagMaintenanceIssueType.ILLEGIBLE_CONTENT
                    : RagMaintenanceIssueType.LOW_VALUE_CONTENT;
            return new DocumentAssessment(
                    RagMaintenanceSeverity.CRITICAL,
                    issueType,
                    "Contenido ilegible, demasiado pobre o inservible para una base RAG.",
                    RagMaintenanceAction.DELETE,
                    snippet,
                    null,
                    usageCount,
                    lastUsedAt
            );
        }

        Set<String> seenFingerprints = fingerprintsByOwner.computeIfAbsent(owner, key -> new HashSet<>());
        if (seenFingerprints.contains(normalizedFingerprint)) {
            return new DocumentAssessment(
                    RagMaintenanceSeverity.WARNING,
                    RagMaintenanceIssueType.DUPLICATE_DOCUMENT,
                    "Documento duplicado dentro del mismo owner; probablemente sobra en la ingesta.",
                    RagMaintenanceAction.DELETE,
                    snippet,
                    proposedContent,
                    usageCount,
                    lastUsedAt
            );
        }
        seenFingerprints.add(normalizedFingerprint);

        if (isUnused(doc, usageCount, lastUsedAt)) {
            return new DocumentAssessment(
                    RagMaintenanceSeverity.WARNING,
                    RagMaintenanceIssueType.UNUSED_DOCUMENT,
                    "Documento sin uso relevante y envejecido; conviene revisar si sigue aportando valor.",
                    RagMaintenanceAction.DELETE,
                    snippet,
                    proposedContent,
                    usageCount,
                    lastUsedAt
            );
        }

        if (malformedStructure || cleaned.changed() || (!prunableChunks.isEmpty() && prunableChunks.size() < chunks.size())) {
            return new DocumentAssessment(
                    RagMaintenanceSeverity.WARNING,
                    RagMaintenanceIssueType.BAD_STRUCTURE,
                    "Documento util pero mal estructurado para la base canonica documents/chunks/vectors; requiere reestructuracion.",
                    RagMaintenanceAction.RESTRUCTURE,
                    snippet,
                    proposedContent,
                    usageCount,
                    lastUsedAt
            );
        }

        return null;
    }

    private boolean shouldResetDecisionState(RagMaintenanceCase ragCase, DocumentAssessment assessment) {
        return ragCase.getId() != null
                && (ragCase.getIssueType() != assessment.issueType()
                || ragCase.getSeverity() != assessment.severity()
                || ragCase.getRecommendedAction() != assessment.recommendedAction());
    }

    private void hydrateCase(RagMaintenanceCase ragCase,
                             KnowledgeDocument doc,
                             DocumentAssessment assessment,
                             boolean resetDecisionState) {
        ragCase.setDocumentId(doc.getId());
        ragCase.setOwner(normalizeOwner(doc.getOwner()));
        ragCase.setDocumentTitle(safeTitle(doc));
        ragCase.setSeverity(assessment.severity());
        ragCase.setIssueType(assessment.issueType());
        ragCase.setRecommendedAction(assessment.recommendedAction());
        ragCase.setUsageCount(assessment.usageCount());
        ragCase.setLastUsedAt(assessment.lastUsedAt());
        ragCase.setSummary(assessment.summary());
        ragCase.setOriginalSnippet(assessment.originalSnippet());
        ragCase.setProposedContent(trimToNull(assessment.proposedContent()));

        if (resetDecisionState || ragCase.getStatus() == null || ragCase.getStatus() == RagMaintenanceCaseStatus.RESOLVED) {
            ragCase.setStatus(RagMaintenanceCaseStatus.OPEN);
            ragCase.setAiSuggestedAction(null);
            ragCase.setAiReason(null);
            ragCase.setAiDecidedAt(null);
            ragCase.setAiModel(null);
            ragCase.setFinalAction(null);
            ragCase.setResolvedAt(null);
            ragCase.setResolvedBy(null);
            ragCase.setAutoApplyAt(null);
            ragCase.setAdminDueAt(initialAdminDueAt(assessment.severity()));
        } else if (ragCase.getAdminDueAt() == null) {
            ragCase.setAdminDueAt(initialAdminDueAt(assessment.severity()));
        }
    }

    private Instant initialAdminDueAt(RagMaintenanceSeverity severity) {
        Instant now = Instant.now();
        if (severity == RagMaintenanceSeverity.CRITICAL) {
            return now;
        }
        return now.plus(Duration.ofHours(Math.max(1, properties.getWarningReviewHours())));
    }

    private void processPendingCases() {
        processPendingCases(null);
    }

    @Transactional
    private void processPendingCases(RunAccumulator acc) {
        Instant now = Instant.now();

        List<RagMaintenanceCase> overdueForAi = caseRepo.findTop100ByStatusAndAdminDueAtBeforeOrderByAdminDueAtAsc(
                RagMaintenanceCaseStatus.OPEN,
                now
        );
        for (RagMaintenanceCase ragCase : overdueForAi) {
            reviewCaseWithAi(ragCase, ragCase.getSeverity() == RagMaintenanceSeverity.CRITICAL, acc);
        }

        List<RagMaintenanceCase> readyToApply = caseRepo.findTop100ByStatusAndAutoApplyAtBeforeOrderByAutoApplyAtAsc(
                RagMaintenanceCaseStatus.AI_REVIEWED,
                now
        );
        for (RagMaintenanceCase ragCase : readyToApply) {
            autoApplyCase(ragCase, acc);
        }
    }

    private void reviewCaseWithAi(RagMaintenanceCase ragCase,
                                  boolean autoExecute,
                                  RunAccumulator acc) {
        if (ragCase == null || ragCase.getStatus() == RagMaintenanceCaseStatus.EXECUTED || ragCase.getStatus() == RagMaintenanceCaseStatus.RESOLVED) {
            return;
        }

        RagMaintenanceAdvisorService.Advice advice = advisorService.advise(ragCase);
        ragCase.setAiSuggestedAction(advice.action());
        ragCase.setAiReason(advice.reason());
        ragCase.setAiModel(advice.model());
        ragCase.setAiDecidedAt(Instant.now());
        if (trimToNull(advice.normalizedContent()) != null) {
            ragCase.setProposedContent(advice.normalizedContent().trim());
        }
        appendAudit(ragCase, "AI", "Decision IA: " + advice.action() + " - " + advice.reason());
        recordEvent(
                "INFO",
                "AI_DECISION",
                ragCase.getDocumentTitle(),
                "La IA sugirio " + advice.action() + "."
        );
        if (acc != null) {
            acc.recordAiReview();
        }

        if (advice.action() == RagMaintenanceAction.KEEP) {
            markResolved(ragCase, RagMaintenanceAction.KEEP, "robot-ai", "La IA decidio conservar el documento.");
            caseRepo.save(ragCase);
            return;
        }

        if (autoExecute) {
            executeCaseAction(
                    ragCase,
                    advice.action(),
                    advice.normalizedContent(),
                    "robot-ai",
                    "Ejecucion automatica tras decision de la IA.",
                    false
            );
        } else {
            ragCase.setStatus(RagMaintenanceCaseStatus.AI_REVIEWED);
            ragCase.setAutoApplyAt(Instant.now().plus(Duration.ofHours(Math.max(1, properties.getAiAutoApplyHours()))));
            caseRepo.save(ragCase);
        }
    }

    private void autoApplyCase(RagMaintenanceCase ragCase,
                               RunAccumulator acc) {
        if (ragCase == null || ragCase.getStatus() != RagMaintenanceCaseStatus.AI_REVIEWED) {
            return;
        }
        RagMaintenanceAction action = ragCase.getAiSuggestedAction();
        if (action == null || action == RagMaintenanceAction.KEEP) {
            markResolved(ragCase, RagMaintenanceAction.KEEP, "robot", "No se requirio auto-aplicar cambios.");
            caseRepo.save(ragCase);
            return;
        }

        executeCaseAction(
                ragCase,
                action,
                ragCase.getProposedContent(),
                "robot",
                "Auto-aplicado tras 1 dia sin respuesta administrativa.",
                false
        );
        if (acc != null) {
            acc.recordAutoExecution();
        }
    }

    private void executeCaseAction(RagMaintenanceCase ragCase,
                                   RagMaintenanceAction action,
                                   String proposedContentOverride,
                                   String actor,
                                   String auditMessage,
                                   boolean manualAction) {
        RagMaintenanceAction safeAction = action == null ? RagMaintenanceAction.KEEP : action;
        switch (safeAction) {
            case KEEP -> markResolved(ragCase, RagMaintenanceAction.KEEP, actor, auditMessage);
            case DELETE -> {
                if (!dryRun.get()) {
                    ragService.deleteDocumentById(ragCase.getDocumentId());
                }
                ragCase.setStatus(RagMaintenanceCaseStatus.EXECUTED);
                ragCase.setFinalAction(RagMaintenanceAction.DELETE);
                ragCase.setResolvedAt(Instant.now());
                ragCase.setResolvedBy(actor);
                ragCase.setAutoApplyAt(null);
                appendAudit(ragCase, actorLabel(actor), auditMessage);
                recordEvent(
                        manualAction ? "WARN" : "INFO",
                        "CASE_DELETE",
                        ragCase.getDocumentTitle(),
                        manualAction ? "Documento eliminado por decision administrativa." : "Documento eliminado automaticamente."
                );
            }
            case RESTRUCTURE -> {
                String content = firstNonBlank(trimToNull(proposedContentOverride), trimToNull(ragCase.getProposedContent()));
                if (content == null) {
                    throw new ResponseStatusException(BAD_REQUEST, "No hay proposedContent para reestructurar.");
                }
                if (!dryRun.get()) {
                    ragService.upsertDocumentForOwner(ragCase.getOwner(), ragCase.getDocumentTitle(), content);
                }
                ragCase.setStatus(RagMaintenanceCaseStatus.EXECUTED);
                ragCase.setFinalAction(RagMaintenanceAction.RESTRUCTURE);
                ragCase.setResolvedAt(Instant.now());
                ragCase.setResolvedBy(actor);
                ragCase.setAutoApplyAt(null);
                ragCase.setProposedContent(content);
                appendAudit(ragCase, actorLabel(actor), auditMessage);
                recordEvent(
                        "INFO",
                        "CASE_RESTRUCTURE",
                        ragCase.getDocumentTitle(),
                        manualAction ? "Documento reestructurado por decision administrativa." : "Documento reestructurado automaticamente."
                );
            }
        }
        caseRepo.save(ragCase);
    }

    private void markResolved(RagMaintenanceCase ragCase,
                              RagMaintenanceAction finalAction,
                              String actor,
                              String message) {
        ragCase.setStatus(RagMaintenanceCaseStatus.RESOLVED);
        ragCase.setFinalAction(finalAction);
        ragCase.setResolvedAt(Instant.now());
        ragCase.setResolvedBy(actor);
        ragCase.setAutoApplyAt(null);
        appendAudit(ragCase, actorLabel(actor), message);
    }

    private Optional<RagMaintenanceCase> findActiveCase(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return caseRepo.findFirstByDocumentIdAndStatusInOrderByCreatedAtDesc(documentId, ACTIVE_CASE_STATUSES);
    }

    private RagMaintenanceCaseDto toDto(RagMaintenanceCase ragCase) {
        return new RagMaintenanceCaseDto(
                ragCase.getId(),
                ragCase.getDocumentId(),
                ragCase.getOwner(),
                ragCase.getDocumentTitle(),
                enumName(ragCase.getSeverity()),
                enumName(ragCase.getIssueType()),
                enumName(ragCase.getStatus()),
                enumName(ragCase.getRecommendedAction()),
                enumName(ragCase.getAiSuggestedAction()),
                enumName(ragCase.getFinalAction()),
                ragCase.getUsageCount(),
                ragCase.getLastUsedAt(),
                ragCase.getCreatedAt(),
                ragCase.getUpdatedAt(),
                ragCase.getAdminDueAt(),
                ragCase.getAiDecidedAt(),
                ragCase.getAutoApplyAt(),
                ragCase.getResolvedAt(),
                ragCase.getSummary(),
                ragCase.getOriginalSnippet(),
                ragCase.getProposedContent(),
                ragCase.getAiReason(),
                ragCase.getAiModel(),
                ragCase.getResolvedBy(),
                ragCase.getAuditLog()
        );
    }

    private RagMaintenanceAction parseAction(String raw) {
        try {
            return RagMaintenanceAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Accion invalida: " + raw);
        }
    }

    private boolean isUnused(KnowledgeDocument doc, long usageCount, Instant lastUsedAt) {
        if (usageCount > 0 || lastUsedAt != null) {
            return false;
        }
        Instant reference = doc.getUpdatedAt() != null ? doc.getUpdatedAt() : doc.getCreatedAt();
        if (reference == null) {
            return false;
        }
        return reference.isBefore(Instant.now().minus(Duration.ofDays(Math.max(1, properties.getUnusedDaysThreshold()))));
    }

    /**
     * La propuesta estructurada ya no mete wrappers como "Titulo:" dentro del cuerpo.
     * El titulo vive en `documents.title`; aqui solo devolvemos el texto que deberia reintegrarse para chunking.
     */
    private String buildIdealStructure(KnowledgeDocument doc, List<KnowledgeChunk> chunks, String cleanedContent) {
        String base = trimToNull(cleanedContent);
        if (base == null) {
            base = trimToNull(RagService.rebuildDocumentText(chunks));
        }
        if (base == null) {
            return null;
        }
        return base;
    }

    /**
     * Aqui codificamos la estructura objetivo que acordasteis:
     * - documents con source y fingerprint.
     * - chunks append-only con hash, token_count, created_at, source y tags.
     * Cuando alguna de estas piezas falta o el chunking no es coherente, abrimos caso warning.
     */
    private boolean hasMalformedStructure(KnowledgeDocument doc, List<KnowledgeChunk> chunks, String cleanedContent) {
        if (doc == null) {
            return true;
        }
        if (!hasText(doc.getSource()) || !hasText(doc.getContentFingerprint())) {
            return true;
        }
        if (chunks.isEmpty()) {
            return true;
        }

        List<String> expectedChunks = TextChunker.chunk(
                cleanedContent == null ? "" : cleanedContent,
                Math.max(50, ragChunkSize),
                Math.max(0, ragChunkOverlap)
        );
        int expectedCount = Math.max(1, expectedChunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            if (chunk == null) {
                return true;
            }
            if (chunk.getChunkIndex() != i) {
                return true;
            }
            if (!hasText(chunk.getText()) || !hasText(chunk.getHash()) || !hasText(chunk.getSource())) {
                return true;
            }
            if (chunk.getTokenCount() <= 0 || !chunk.getHash().equalsIgnoreCase(sha256(chunk.getText()))) {
                return true;
            }
        }

        return Math.abs(chunks.size() - expectedCount) > 1;
    }

    private List<KnowledgeChunk> findPrunableChunks(List<KnowledgeChunk> chunks) {
        Set<String> seenFingerprints = new LinkedHashSet<>();
        List<KnowledgeChunk> prunable = new ArrayList<>();

        for (KnowledgeChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }

            String fingerprint = fingerprint(chunk.getText());
            int compactChars = compactLength(chunk.getText());
            int tokens = Math.max(chunk.getTokenCount(), informativeTokenCount(fingerprint));
            boolean lowValue = fingerprint.isBlank()
                    || (compactChars < properties.getMinChunkChars() && tokens < properties.getMinChunkTokens());

            if (lowValue) {
                prunable.add(chunk);
                continue;
            }

            if (!seenFingerprints.add(fingerprint)) {
                prunable.add(chunk);
            }
        }

        return prunable;
    }

    private CleanContentResult cleanContent(String content) {
        String original = content == null ? "" : content.trim();
        if (original.isBlank()) {
            return new CleanContentResult("", false);
        }

        String[] rawParagraphs = original.split("(?m)\\n\\s*\\n+");
        List<String> cleanedParagraphs = new ArrayList<>(rawParagraphs.length);
        Set<String> seenParagraphs = new LinkedHashSet<>();

        for (String rawParagraph : rawParagraphs) {
            String[] rawLines = rawParagraph.split("\\R");
            Map<String, Integer> lineCopies = new HashMap<>();
            List<String> keptLines = new ArrayList<>(rawLines.length);

            for (String rawLine : rawLines) {
                String line = collapseSpaces(rawLine);
                if (line.isBlank()) {
                    continue;
                }

                String lineFingerprint = fingerprint(line);
                if (lineFingerprint.isBlank()) {
                    continue;
                }

                int copies = lineCopies.getOrDefault(lineFingerprint, 0);
                if (copies >= Math.max(1, properties.getMaxLineCopies())) {
                    continue;
                }

                lineCopies.put(lineFingerprint, copies + 1);
                keptLines.add(line);
            }

            String paragraph = String.join("\n", keptLines).trim();
            String paragraphFingerprint = fingerprint(paragraph);
            if (paragraph.isBlank() || paragraphFingerprint.isBlank()) {
                continue;
            }

            if (!seenParagraphs.add(paragraphFingerprint)) {
                continue;
            }

            cleanedParagraphs.add(paragraph);
        }

        String cleaned = String.join("\n\n", cleanedParagraphs).trim();
        return new CleanContentResult(cleaned, !cleaned.equals(original));
    }

    private boolean hasMeaningfulDocument(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return compactLength(content) >= properties.getMinDocumentChars()
                && informativeTokenCount(fingerprint(content)) >= properties.getMinDocumentTokens();
    }

    private boolean looksUnreadable(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        int visible = 0;
        int alnum = 0;
        int weird = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            visible++;
            if (Character.isLetterOrDigit(ch)) {
                alnum++;
            } else if (!".,:;!?()[]{}<>#/-_\"'".contains(String.valueOf(ch))) {
                weird++;
            }
        }
        if (visible == 0) {
            return true;
        }
        double alnumRatio = alnum / (double) visible;
        double weirdRatio = weird / (double) visible;
        return alnumRatio < 0.40 || weirdRatio > 0.35;
    }

    private RagMaintenanceCorpusDto snapshotCorpusQuietly() {
        try {
            long totalDocuments = docRepo.countByActiveTrue();
            long totalChunks = chunkRepo.countActive();
            long documentBytes = safeLong(docRepo.sumMetadataLength());
            long chunkTextBytes = safeLong(chunkRepo.sumTextLength());
            long embeddingBytes = safeLong(vectorRepo.sumEmbeddingLength()) + Math.max(0L, vectorIndexService.estimateIndexBytes());
            long totalBytes = documentBytes + chunkTextBytes + embeddingBytes;

            return new RagMaintenanceCorpusDto(
                    totalDocuments,
                    totalChunks,
                    documentBytes,
                    chunkTextBytes,
                    embeddingBytes,
                    totalBytes
            );
        } catch (Exception e) {
            log.debug("No se pudo calcular snapshot de corpus RAG", e);
            return lastKnownCorpus == null ? RagMaintenanceCorpusDto.empty() : lastKnownCorpus;
        }
    }

    private Instant computeNextRunAt() {
        if (!properties.isEnabled() || paused.get() || running.get()) {
            return null;
        }
        if (lastCompletedAt == null) {
            return Instant.now();
        }
        return lastCompletedAt.plusMillis(intervalMs.get());
    }

    private List<RagMaintenanceEventDto> recentEvents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<RagMaintenanceEventDto> out = new ArrayList<>(safeLimit);
        synchronized (events) {
            for (RagMaintenanceEventDto event : events) {
                out.add(event);
                if (out.size() >= safeLimit) {
                    break;
                }
            }
        }
        return out;
    }

    private void recordEvent(String level, String type, String title, String message) {
        RagMaintenanceEventDto event = new RagMaintenanceEventDto(Instant.now(), level, type, title, message);
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > Math.max(1, properties.getMaxEvents())) {
                events.removeLast();
            }
        }
    }

    private void appendAudit(RagMaintenanceCase ragCase, String actor, String message) {
        String line = "[" + Instant.now() + "] " + actor + " - " + message;
        String current = ragCase.getAuditLog();
        if (current == null || current.isBlank()) {
            ragCase.setAuditLog(line);
            return;
        }
        ragCase.setAuditLog(current + "\n" + line);
    }

    private String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String clean = collapseSpaces(content);
        int max = Math.max(80, properties.getSnippetChars());
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, max).trim() + "...";
    }

    private static String actorLabel(String actor) {
        return trimToNull(actor) == null ? "SYSTEM" : actor.trim();
    }

    private static String normalizeOwner(String owner) {
        String normalized = owner == null ? "" : owner.trim();
        return normalized.isBlank() ? RagService.GLOBAL_OWNER : normalized;
    }

    private static String safeTitle(KnowledgeDocument doc) {
        if (doc == null || doc.getTitle() == null || doc.getTitle().isBlank()) {
            return "Documento";
        }
        return doc.getTitle().trim();
    }

    private static String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "Error sin detalle.";
        }
        return error.getMessage().trim();
    }

    private static String collapseSpaces(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String fingerprint(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int informativeTokenCount(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return 0;
        }
        String[] parts = normalizedText.split("\\s+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            String token = part == null ? "" : part.trim();
            if (token.length() < 3) {
                continue;
            }
            if (STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens.size();
    }

    private static int compactLength(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.replaceAll("\\s+", "").length();
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String clean = trimToNull(value);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible.", e);
        }
    }

    private static long clampIntervalMillis(long candidateMs) {
        long min = MIN_INTERVAL_SECONDS * 1000L;
        long max = MAX_INTERVAL_SECONDS * 1000L;
        if (candidateMs < min) {
            return min;
        }
        if (candidateMs > max) {
            return max;
        }
        return candidateMs;
    }

    private record CleanContentResult(String content, boolean changed) {
    }

    private record DocumentAssessment(RagMaintenanceSeverity severity,
                                      RagMaintenanceIssueType issueType,
                                      String summary,
                                      RagMaintenanceAction recommendedAction,
                                      String originalSnippet,
                                      String proposedContent,
                                      long usageCount,
                                      Instant lastUsedAt) {
    }

    private static final class RunAccumulator {
        private final String trigger;
        private final boolean dryRun;
        private final Instant startedAt;
        private final RagMaintenanceCorpusDto before;
        private long scannedDocuments;
        private long scannedChunks;
        private long deletedDocuments;
        private long rebuiltDocuments;
        private long deletedChunks;
        private long estimatedBytesFreed;
        private long openedCases;
        private long aiReviews;
        private long autoExecutions;

        private RunAccumulator(String trigger,
                               boolean dryRun,
                               Instant startedAt,
                               RagMaintenanceCorpusDto before) {
            this.trigger = trigger == null ? "AUTO" : trigger;
            this.dryRun = dryRun;
            this.startedAt = startedAt;
            this.before = before == null ? RagMaintenanceCorpusDto.empty() : before;
        }

        private void recordScan(int chunkCount) {
            scannedDocuments++;
            scannedChunks += Math.max(0, chunkCount);
        }

        private void recordCaseOpened() {
            openedCases++;
        }

        private void recordAiReview() {
            aiReviews++;
        }

        private void recordAutoExecution() {
            autoExecutions++;
        }

        private RagMaintenanceRunDto success(Instant completedAt, RagMaintenanceCorpusDto after) {
            long actualFreed = Math.max(0L, before.totalBytes() - (after == null ? before.totalBytes() : after.totalBytes()));
            long freed = dryRun ? estimatedBytesFreed : actualFreed;
            return new RagMaintenanceRunDto(
                    trigger,
                    dryRun ? "DRY_RUN" : "COMPLETED",
                    startedAt,
                    completedAt,
                    scannedDocuments,
                    scannedChunks,
                    rebuiltDocuments,
                    deletedDocuments,
                    deletedChunks,
                    0,
                    0,
                    0,
                    freed,
                    summary()
            );
        }

        private RagMaintenanceRunDto failure(Instant completedAt, String message) {
            return new RagMaintenanceRunDto(
                    trigger,
                    "FAILED",
                    startedAt,
                    completedAt,
                    scannedDocuments,
                    scannedChunks,
                    rebuiltDocuments,
                    deletedDocuments,
                    deletedChunks,
                    0,
                    0,
                    0,
                    estimatedBytesFreed,
                    "Fallo durante el barrido: " + (message == null ? "sin detalle" : message)
            );
        }

        private String summary() {
            if (scannedDocuments == 0) {
                return "No habia documentos para revisar.";
            }
            return "Escaneados " + scannedDocuments
                    + " docs, casos abiertos " + openedCases
                    + ", decisiones IA " + aiReviews
                    + ", autoacciones " + autoExecutions + ".";
        }
    }
}
