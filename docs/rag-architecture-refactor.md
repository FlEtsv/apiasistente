# Refactorización de Arquitectura RAG

## Resumen

Esta refactorización separa las responsabilidades del pipeline RAG en capas y estrategias intercambiables, siguiendo el principio de Single Responsibility y permitiendo A/B testing de diferentes algoritmos.

## Cambios Principales

### 1. Configuración Centralizada

**Antes:** 14+ parámetros `@Value` dispersos en `RagService`

**Ahora:** `RagRetrievalConfig` con estructura jerárquica:

```yaml
rag:
  top-k: 10
  rerank-candidates: 12
  evidence-threshold: 0.45

  chunking:
    size: 900
    overlap: 150
    strategy: sliding-window

  fusion:
    semantic-weight: 0.80
    lexical-weight: 0.20
    exact-match-boost: 0.12
    coverage-weight: 0.75
    jaccard-weight: 0.25

  mmr:
    lambda: 0.65

  compression:
    max-chunks: 5
    max-snippets-per-chunk: 2
    max-chars-per-chunk: 420

  owner-boost:
    global-boost: 0.03
    user-boost: 0.05
```

### 2. Estrategias Intercambiables

#### `ScoringStrategy`
- **`HybridFusionScorer`** (default): combina vector + lexical + boosts
- Futuras: `BM25Scorer`, `RRFScorer`, `CrossEncoderScorer`

#### `RerankingStrategy`
- **`MmrReranker`** (default): Maximal Marginal Relevance optimizado
- Futuras: `CrossEncoderReranker`, `DiversityReranker`, `NoOpReranker`

#### `CompressionStrategy`
- **`ExtractiveSummarizer`** (default): selección de fragmentos por score
- Futuras: `AbstractiveSummarizer` (LLM), `ContextualCompressor` (LongLLMLingua)

### 3. Utilidad Compartida de Normalización

**`TextNormalizer`** consolida lógica duplicada:
- `normalize(String)` - minúsculas + quita puntuación
- `tokenize(String)` - split + filtrado stopwords
- `estimateTokenCount(String)` - heurística de tokens

Reemplaza duplicación entre `RagService.normalizeSearchText()` y `ChatRagGateService.normalize()`.

## Beneficios

### Flexibilidad
- Cambiar estrategias vía configuración sin modificar código
- A/B testing de algoritmos (MMR vs cross-encoder, extractive vs abstractive)

### Testeabilidad
- Cada estrategia es unit-testeable de forma aislada
- Mocks simples para testing de `RagService`

### Mantenibilidad
- Cambios en scoring no afectan retrieval ni compresión
- Single Responsibility: cada clase tiene una única razón para cambiar

### Performance
- Estrategias optimizadas por separado sin afectar otras capas
- Ejecución paralela futura (semantic + lexical retrieval en paralelo)

## Migración

### Fase Actual (Completada)
✅ Interfaces de estrategias creadas
✅ Configuración centralizada en `RagRetrievalConfig`
✅ Implementaciones default que replican lógica actual:
  - `HybridFusionScorer`
  - `MmrReranker`
  - `ExtractiveSummarizer`
✅ Utilidad `TextNormalizer` compartida

### Próximos Pasos (Pendientes)
1. **Refactorizar `RagService`** para usar las estrategias inyectadas
2. **Actualizar `ChatRagGateService`** para usar `TextNormalizer`
3. **Tests de integración** para validar equivalencia con lógica anterior
4. **Documentar extensión**: guía para agregar nuevas estrategias
5. **Implementar alternativas**: `BM25Scorer`, `CrossEncoderReranker`, etc.

## Arquitectura Resultante

```
┌─────────────────────────────────────────┐
│        RagService (Orchestrator)        │
│  - Ingestion pipeline                   │
│  - Retrieval coordination               │
│  - Strategy composition                 │
└───┬─────────────────────────────────┬───┘
    │                                 │
    v                                 v
┌──────────────────┐        ┌──────────────────┐
│ ScoringStrategy  │        │ RerankingStrategy│
│ ├─ HybridFusion  │        │ ├─ MMR           │
│ ├─ BM25          │        │ ├─ CrossEncoder  │
│ └─ RRF           │        │ └─ Diversity     │
└──────────────────┘        └──────────────────┘
           │
           v
┌──────────────────────────┐
│   CompressionStrategy    │
│ ├─ ExtractiveSummarizer  │
│ ├─ AbstractiveSummarizer │
│ └─ ContextualCompressor  │
└──────────────────────────┘
           │
           v
┌──────────────────────────┐
│    TextNormalizer        │
│  (shared utility)        │
└──────────────────────────┘
```

## Configuración Avanzada (Futura)

### Perfiles por Caso de Uso

**retrieval-quality.yml** (máxima calidad):
```yaml
rag:
  top-k: 20
  rerank-candidates: 30
  fusion:
    semantic-weight: 0.85
  mmr:
    lambda: 0.70  # más diversidad
```

**retrieval-speed.yml** (máxima velocidad):
```yaml
rag:
  top-k: 5
  rerank-candidates: 8
  fusion:
    semantic-weight: 1.0  # solo semántico
    lexical-weight: 0.0
```

### Selección Dinámica de Estrategias

```java
@Configuration
public class RagStrategyConfig {

    @Bean
    @ConditionalOnProperty(name = "rag.scoring.strategy", havingValue = "hybrid")
    public ScoringStrategy hybridScorer(RagRetrievalConfig config) {
        return new HybridFusionScorer(config);
    }

    @Bean
    @ConditionalOnProperty(name = "rag.scoring.strategy", havingValue = "bm25")
    public ScoringStrategy bm25Scorer() {
        return new BM25Scorer();
    }
}
```

## Métricas de Éxito

Post-refactorización, monitorear:
- **Lines of code por clase**: target <300 (antes: RagService 1335)
- **Cyclomatic complexity**: target <15 por método
- **Test coverage**: target >85%
- **Latency P95**: mantener o mejorar respecto a baseline
- **Retrieval quality**: NDCG@5 igual o superior

## Referencias

- Análisis completo: ver output del agente de análisis RAG
- Issue tracking: verificar CLAUDE.md para contexto del proyecto
