package com.example.apiasistente.chat.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reune heuristicas ligeras para clasificar la intencion del turno y decidir si
 * el flujo debe activar RAG, exigir evidencia de la base o quedarse en chat directo.
 */
public final class ChatPromptSignals {

    /**
     * Resume el tipo general de peticion para el selector de modelo y el prompt.
     */
    public enum IntentRoute {
        SMALL_TALK,
        TEXT_RENDER,
        TASK_SIMPLE,
        FACTUAL_TECH
    }

    /**
     * Define si el turno no usa RAG, si debe usarlo o si solo lo prefiere cuando hay evidencia.
     */
    public enum RagMode {
        OFF,
        REQUIRED,
        PREFERRED
    }

    /**
     * Categoria de intencion mas especifica para ajustar estilo de salida y guardrails.
     */
    public enum IntentCategory {
        SMALL_TALK,
        TEXT_RENDER,
        TASK_EXECUTION,
        FACT_LOOKUP,
        ANALYSIS_AUDIT,
        HOME_AUTOMATION,
        UNKNOWN
    }

    /**
     * Nivel de detalle esperado en la respuesta.
     */
    public enum ResponseStyle {
        BRIEF,
        STANDARD,
        DETAILED
    }

    private static final Pattern SMALL_TALK_PATTERN = Pattern.compile(
            "^(hola+|buenas|hey|hello|gracias|ok|vale|jaja+|buenos\\s+d(?:i|\\u00ed)as|buenas\\s+noches|qu(?:e|\\u00e9)\\s+tal)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern CONCRETE_REQUEST_HINTS = Pattern.compile(
            "\\b(qu(?:e|\\u00e9)|c(?:o|\\u00f3)mo|por\\s+qu(?:e|\\u00e9)|define|compara|explica|gu(?:i|\\u00ed)a|error|stack\\s*trace|stacktrace|endpoint|api|config|docker|sql|log|resumen|resume|documento|doc|chunk|fuente|actualidad|noticia)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern RAG_REQUIRED_CONTEXT_HINTS = Pattern.compile(
            "\\b(seg(?:u|\\u00fa)n|seg(?:u|\\u00fa)n\\s+mis\\s+datos|seg(?:u|\\u00fa)n\\s+nuestros\\s+datos|" +
                    "mi\\s+(?:sistema|proyecto|documento|base|endpoint|ruta|log)|mis\\s+(?:datos|logs|documentos)|" +
                    "nuestro\\s+(?:sistema|proyecto|documento|endpoint)|nuestra\\s+(?:base|documentacion)|" +
                    "que\\s+dijimos|que\\s+pas(?:o|\\u00f3)|ayer|hoy|anoche|documento|documentacion|doc|archivo|adjunto|base|sistema|proyecto|" +
                    "endpoint|payload|ruta|path|log|logs|trace|stack\\s*trace|stacktrace|error|permiso|sesion|registro|api\\s*key|chunk|fuente|monitor)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DATE_HINTS = Pattern.compile(
            "\\b(hoy|ayer|anoche|esta\\s+semana|este\\s+mes|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern PATH_OR_FILE_HINTS = Pattern.compile(
            "(?:/[\\w./:-]+)|(?:\\b[\\w.-]+\\.(?:log|md|txt|json|yml|yaml|xml|java|properties)\\b)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern INTERNAL_NAME_HINTS = Pattern.compile(
            "\\b[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]+)+\\b"
    );

    private static final Pattern RAG_PREFERRED_HINTS = Pattern.compile(
            "\\b(compara(?:r|tiva)?|defin(?:e|icion)|arquitectura|diagnostica(?:r)?|troubleshoot|debug|" +
                    "stack|spring|java|docker|sql|jwt|oauth|cache|latencia|rendimiento|prometheus|grafana|monitor(?:eo|ing)?|" +
                    "embeddings?|chunking|retrieval|rerank|rag)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TASK_EXECUTION_HINTS = Pattern.compile(
            "\\b(redacta(?:r)?|escribe(?:r)?|traduce(?:r)?|corrige(?:r)?|mejora(?:r)?|resume(?:r)?|resume\\s+en|" +
                    "genera(?:r)?|crea(?:r)?|haz(?:me)?|planifica(?:r)?|organiza(?:r)?|estructura(?:r)?|prepara(?:r)?|monta(?:r)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern QUESTION_LIKE_HINTS = Pattern.compile(
            "\\b(qu(?:e|\\u00e9)|c(?:o|\\u00f3)mo|por\\s+qu(?:e|\\u00e9)|cu(?:a|\\u00e1)l|d(?:o|\\u00f3)nde|cu(?:a|\\u00e1)ndo|qu(?:i|\\u00e9)n|why|what|how|which|when|where)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern AUDIT_ANALYSIS_HINTS = Pattern.compile(
            "\\b(audita(?:r)?|auditoria|audit|metricas?|cuantificables?|fallos?|errores?|incidente|postmortem|mejora(?:s)?|kpi|fluidez)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern HOME_AUTOMATION_HINTS = Pattern.compile(
            "\\b(domotica|dom\\u00f3tica|casa|hogar|luces?|lamparas?|termostato|calefaccion|aire\\s+acondicionado|persianas?|cerradura|puerta\\s+principal|garaje|alarma|riego|camara(?:s)?|enchufe(?:s)?\\s+inteligentes?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern HOME_AUTOMATION_ACTION_HINTS = Pattern.compile(
            "\\b(enciende(?:r)?|apaga(?:r)?|abre(?:r)?|cierra(?:r)?|sube(?:r)?|baja(?:r)?|activa(?:r)?|desactiva(?:r)?|ajusta(?:r)?|bloquea(?:r)?|desbloquea(?:r)?|programa(?:r)?\\s+escena|modo\\s+ausente|modo\\s+noche)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern HOME_CRITICAL_ACTUATION_HINTS = Pattern.compile(
            "\\b(alarma|cerradura|desbloquea(?:r)?|puerta\\s+principal|garaje|cortina\\s+metalica|gas|caldera|calefaccion\\s+maxima)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern AUTONOMOUS_DECISION_HINTS = Pattern.compile(
            "\\b(decide\\s+tu|decide\\s+por\\s+mi|sin\\s+preguntar|automaticamente|autonom(?:a|o|amente)|toma\\s+decisiones|actua\\s+solo|hazlo\\s+siempre)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern LEARNING_PROCESS_HINTS = Pattern.compile(
            "\\b(aprende(?:r)?|aprendizaje|mejora\\s+continua|del\\s+proceso|retroalimentacion|feedback\\s+loop|memoria\\s+de\\s+sesion)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern BRIEF_RESPONSE_HINTS = Pattern.compile(
            "\\b(breve|corto|resumen\\s+rapido|en\\s+1\\s+linea|en\\s+una\\s+linea|sin\\s+detalle|solo\\s+la\\s+respuesta|directo\\s+al\\s+grano|conciso)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern DETAILED_RESPONSE_HINTS = Pattern.compile(
            "\\b(extenso|detallado|a\\s+fondo|paso\\s+a\\s+paso|completo|exhaustivo|auditoria\\s+extensa|muy\\s+detallado|profundiza)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TEXT_RENDER_ACTION_HINTS = Pattern.compile(
            "\\b(dibuja(?:me|r)?|dibujame|haz(?:me)?|crea|genera|pinta|representa|arma|monta)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TEXT_RENDER_DIRECT_HINTS = Pattern.compile(
            "\\b(quiero\\s+car(?:a|\\u00e1)cter(?:es)?|con\\s+car(?:a|\\u00e1)cter(?:es)?|solo\\s+car(?:a|\\u00e1)cter(?:es)?|en\\s+ascii|ascii\\s*art|sin\\s+emoji(?:s)?|sin\\s+emoticon(?:o|os)|no\\s+emoji(?:s)?|no\\s+emoticon(?:o|os)|en\\s+vez\\s+de\\s+emoji(?:s)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TEXT_RENDER_OUTPUT_HINTS = Pattern.compile(
            "\\b(ascii(?:\\s*art)?|car(?:a|\\u00e1)cter(?:es)?|texto|letras|simbol(?:o|os)|monoespaciad[oa]|emoji(?:s)?|emoticon(?:o|os))\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern FORMAT_REVISION_HINTS = Pattern.compile(
            "\\b(m(?:a|\\u00e1)s\\s+grande|m(?:a|\\u00e1)s\\s+peque(?:n|\\u00f1)o|con\\s+car(?:a|\\u00e1)cter(?:es)?|solo\\s+car(?:a|\\u00e1)cter(?:es)?|en\\s+ascii|sin\\s+emoji(?:s)?|sin\\s+emoticon(?:o|os)|sin\\s+explicaci(?:o|\\u00f3)n|solo\\s+el\\s+dibujo|solo\\s+la\\s+figura)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern COMPLEX_HINTS = Pattern.compile(
            "\\b(compara(?:r)?|analiza(?:r)?|diagnostica(?:r)?|evalua(?:r)?|arquitectura|estrategia|" +
                    "trade\\s*-?\\s*off|optimiza(?:r)?|disena(?:r)?|refactor(?:iza(?:r)?)?|benchmark|" +
                    "implicaciones|riesgos|alternativas|migraci(?:o|\\u00f3)n|escalabilidad)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern MULTI_STEP_HINTS = Pattern.compile(
            "\\b(paso\\s+a\\s+paso|paso\\s*\\d+|step\\s*\\d+|primero|segundo|tercero|luego|despues|" +
                    "a\\s+continuacion|finalmente|checklist|roadmap|plan\\s+de\\s+accion)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern NUMBERED_STEPS = Pattern.compile("(?s).*\\b1\\s*[\\).:-].*\\b2\\s*[\\).:-].*");

    private static final Pattern FILLER_HINTS = Pattern.compile(
            "\\b(en\\s+resumen|en\\s+conclusion|cabe\\s+destacar|es\\s+importante\\s+destacar|" +
                    "vale\\s+la\\s+pena\\s+mencionar|en\\s+definitiva|a\\s+continuacion\\s+te\\s+presento|" +
                    "como\\s+modelo\\s+de\\s+lenguaje|espero\\s+que\\s+te\\s+sea\\s+util)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private ChatPromptSignals() {
    }

    /**
     * Clasifica la intencion del turno usando solo el texto recibido.
     */
    public static IntentRoute routeIntent(String rawText) {
        return routeIntent(rawText, false);
    }

    /**
     * Clasifica la intencion del turno considerando tambien si hay adjuntos documentales.
     */
    public static IntentRoute routeIntent(String rawText, boolean hasDocumentMedia) {
        if (wantsTextRendering(rawText)) {
            return IntentRoute.TEXT_RENDER;
        }
        if (isSmallTalk(rawText)) {
            return IntentRoute.SMALL_TALK;
        }
        if (ragDecision(rawText, hasDocumentMedia).enabled()) {
            return IntentRoute.FACTUAL_TECH;
        }
        return IntentRoute.TASK_SIMPLE;
    }

    public static IntentProfile captureIntent(String rawText) {
        return captureIntent(rawText, false, false);
    }

    public static IntentProfile captureIntent(String rawText, boolean hasDocumentMedia) {
        return captureIntent(rawText, hasDocumentMedia, false);
    }

    /**
     * Captura una intencion estructurada para controlar fluidez, longitud y guardrails operativos.
     */
    public static IntentProfile captureIntent(String rawText,
                                              boolean hasDocumentMedia,
                                              boolean hasImageMedia) {
        String clean = collapseSpaces(rawText);
        String text = clean.toLowerCase(Locale.ROOT);
        List<String> signals = new ArrayList<>();

        if (text.isBlank()) {
            return IntentProfile.unknown("sin-texto", List.of());
        }

        if (hasImageMedia) {
            signals.add("adjunto-imagen");
        }
        if (hasDocumentMedia) {
            signals.add("adjunto-documental");
        }

        boolean textRender = wantsTextRendering(text);
        boolean smallTalk = isSmallTalk(text);
        boolean complex = isComplexQuery(text);
        boolean multiStep = isMultiStepQuery(text);
        boolean auditLike = AUDIT_ANALYSIS_HINTS.matcher(text).find();
        boolean homeAutomation = HOME_AUTOMATION_HINTS.matcher(text).find()
                || HOME_AUTOMATION_ACTION_HINTS.matcher(text).find();
        boolean autonomousRequested = AUTONOMOUS_DECISION_HINTS.matcher(text).find();
        boolean learningRequested = LEARNING_PROCESS_HINTS.matcher(text).find();
        boolean executionLike = TASK_EXECUTION_HINTS.matcher(text).find();
        boolean briefRequested = BRIEF_RESPONSE_HINTS.matcher(text).find();
        boolean detailedRequested = DETAILED_RESPONSE_HINTS.matcher(text).find();
        boolean lookupLike = ragDecision(text, hasDocumentMedia).enabled();

        collectBooleanSignal(signals, textRender, "text-render");
        collectBooleanSignal(signals, smallTalk, "small-talk");
        collectBooleanSignal(signals, complex, "consulta-compleja");
        collectBooleanSignal(signals, multiStep, "multi-step");
        collectBooleanSignal(signals, auditLike, "auditoria");
        collectBooleanSignal(signals, homeAutomation, "domotica");
        collectBooleanSignal(signals, autonomousRequested, "autonomia");
        collectBooleanSignal(signals, learningRequested, "aprendizaje");
        collectBooleanSignal(signals, executionLike, "ejecucion");

        IntentCategory category = resolveIntentCategory(
                textRender,
                smallTalk,
                homeAutomation,
                auditLike,
                complex,
                multiStep,
                executionLike,
                lookupLike
        );
        ResponseStyle responseStyle = resolveResponseStyle(
                clean,
                briefRequested,
                detailedRequested,
                complex,
                multiStep
        );
        boolean requiresConfirmation = homeAutomation && (autonomousRequested || containsCriticalActuation(text));

        String reason = buildReason(
                "intent="
                        + category.name().toLowerCase(Locale.ROOT)
                        + ",style="
                        + responseStyle.name().toLowerCase(Locale.ROOT),
                signals
        );

        return new IntentProfile(
                category,
                responseStyle,
                homeAutomation,
                autonomousRequested,
                learningRequested,
                requiresConfirmation,
                reason,
                signals
        );
    }

    /**
     * Detecta charla ligera para evitar rutas pesadas de modelo o RAG.
     */
    public static boolean isSmallTalk(String rawText) {
        String text = collapseSpaces(rawText).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return true;
        }
        if (wantsTextRendering(text) || isFormatRevision(text)) {
            return false;
        }

        int words = countWords(text);
        if (SMALL_TALK_PATTERN.matcher(text).find() && words <= 8) {
            return true;
        }

        if (words > 6) {
            return false;
        }
        if (CONCRETE_REQUEST_HINTS.matcher(text).find()) {
            return false;
        }
        if (RAG_REQUIRED_CONTEXT_HINTS.matcher(text).find()) {
            return false;
        }
        if (RAG_PREFERRED_HINTS.matcher(text).find()) {
            return false;
        }
        if (text.contains("?") || text.contains("\u00BF")) {
            return false;
        }
        return true;
    }

    /**
     * Decide si el turno debe usar RAG, lo requiere obligatoriamente o puede vivir sin el.
     */
    public static RagDecision ragDecision(String rawText, boolean hasDocumentMedia) {
        String clean = collapseSpaces(rawText);
        String text = clean.toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return RagDecision.off("Texto vacio");
        }
        if (wantsTextRendering(text)) {
            return RagDecision.off("Peticion de dibujo o formato en texto");
        }
        if (isSmallTalk(text)) {
            return RagDecision.off("Conversacion ligera o saludo");
        }
        if (HOME_AUTOMATION_HINTS.matcher(text).find() || HOME_AUTOMATION_ACTION_HINTS.matcher(text).find()) {
            return RagDecision.off("Control domotico: requiere politica/accion, no retrieval por defecto");
        }

        // Estas senales implican dependencia fuerte del conocimiento propio del sistema.
        List<String> requiredSignals = new ArrayList<>();
        if (hasDocumentMedia) {
            requiredSignals.add("adjunto-documental");
        }
        collectSignal(requiredSignals, text, RAG_REQUIRED_CONTEXT_HINTS, "contexto-propio");
        collectSignal(requiredSignals, text, DATE_HINTS, "fecha");
        collectSignal(requiredSignals, clean, PATH_OR_FILE_HINTS, "ruta-o-archivo");
        collectSignal(requiredSignals, clean, INTERNAL_NAME_HINTS, "nombre-interno");

        if (!requiredSignals.isEmpty()) {
            return RagDecision.required(
                    buildReason("La consulta depende de tu base o de artefactos internos", requiredSignals),
                    requiredSignals
            );
        }

        // Estas senales sugieren una consulta tecnica donde RAG ayuda, pero no siempre es imprescindible.
        List<String> preferredSignals = new ArrayList<>();
        collectSignal(preferredSignals, text, RAG_PREFERRED_HINTS, "consulta-tecnica");
        if (!preferredSignals.isEmpty()) {
            return RagDecision.preferred(
                    buildReason("La consulta tecnica puede mejorar con contexto del proyecto", preferredSignals),
                    preferredSignals
            );
        }

        if (isStandaloneTaskRequest(text)) {
            return RagDecision.off("Solicitud ejecutable sin dependencia explicita del corpus");
        }

        // Por defecto se intenta RAG en modo PREFERRED: la compuerta decide si hay contenido relevante.
        // Esto permite que preguntas sobre temas del corpus (ej: "nasa") activen retrieval via metadata probe.
        return RagDecision.preferred("Corpus disponible; la compuerta decidira si hay contenido relevante", List.of());
    }

    /**
     * Atajo historico para saber si el turno activa algun modo de RAG.
     */
    public static boolean needsRag(String rawText) {
        return ragDecision(rawText, false).enabled();
    }

    /**
     * Detecta peticiones para renderizar texto/ASCII en vez de lenguaje natural normal.
     */
    public static boolean wantsTextRendering(String rawText) {
        String text = collapseSpaces(rawText).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }

        boolean directHint = TEXT_RENDER_DIRECT_HINTS.matcher(text).find();
        boolean actionHint = TEXT_RENDER_ACTION_HINTS.matcher(text).find();
        boolean outputHint = TEXT_RENDER_OUTPUT_HINTS.matcher(text).find();
        return directHint || (actionHint && outputHint);
    }

    /**
     * Detecta correcciones cortas de formato apoyadas en el historial reciente.
     */
    public static boolean isFormatRevision(String rawText) {
        String text = collapseSpaces(rawText).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        return countWords(text) <= 10 && FORMAT_REVISION_HINTS.matcher(text).find();
    }

    /**
     * Marca consultas que probablemente necesiten mayor capacidad de razonamiento o respuesta extensa.
     */
    public static boolean isComplexQuery(String rawText) {
        String text = collapseSpaces(rawText);
        if (text.isBlank()) {
            return false;
        }

        boolean hasKeywords = COMPLEX_HINTS.matcher(text).find();
        boolean hasCodeFence = text.contains("```");
        boolean hasNumberedPlan = NUMBERED_STEPS.matcher(text).matches();
        boolean hasManyQuestions = countChar(text, '?') >= 2;
        boolean hasManyClauses = text.contains(";") || text.contains(":");
        int length = text.length();

        return hasKeywords
                || hasCodeFence
                || length >= 260
                || (length >= 160 && (hasManyQuestions || hasManyClauses || hasNumberedPlan));
    }

    /**
     * Detecta solicitudes multi-paso, checklist o planes de accion.
     */
    public static boolean isMultiStepQuery(String rawText) {
        String text = collapseSpaces(rawText);
        if (text.isBlank()) {
            return false;
        }
        return MULTI_STEP_HINTS.matcher(text).find() || NUMBERED_STEPS.matcher(text).matches();
    }

    /**
     * Detecta relleno verbal tipico para activar limpieza posterior de respuesta.
     */
    public static boolean hasLikelyFiller(String rawText) {
        String text = collapseSpaces(rawText);
        if (text.isBlank()) {
            return false;
        }

        int fillerHits = countMatches(FILLER_HINTS, text);
        if (fillerHits > 0) {
            return true;
        }

        return hasRepeatedSentence(text);
    }

    private static IntentCategory resolveIntentCategory(boolean textRender,
                                                        boolean smallTalk,
                                                        boolean homeAutomation,
                                                        boolean auditLike,
                                                        boolean complex,
                                                        boolean multiStep,
                                                        boolean executionLike,
                                                        boolean lookupLike) {
        if (textRender) {
            return IntentCategory.TEXT_RENDER;
        }
        if (smallTalk) {
            return IntentCategory.SMALL_TALK;
        }
        if (homeAutomation) {
            return IntentCategory.HOME_AUTOMATION;
        }
        if (auditLike || complex || multiStep) {
            return IntentCategory.ANALYSIS_AUDIT;
        }
        if (executionLike && !lookupLike) {
            return IntentCategory.TASK_EXECUTION;
        }
        if (lookupLike) {
            return IntentCategory.FACT_LOOKUP;
        }
        return IntentCategory.UNKNOWN;
    }

    private static ResponseStyle resolveResponseStyle(String text,
                                                      boolean briefRequested,
                                                      boolean detailedRequested,
                                                      boolean complex,
                                                      boolean multiStep) {
        if (briefRequested) {
            return ResponseStyle.BRIEF;
        }
        if (detailedRequested || complex || multiStep) {
            return ResponseStyle.DETAILED;
        }
        if (countWords(text) <= 6 && !QUESTION_LIKE_HINTS.matcher(text).find()) {
            return ResponseStyle.BRIEF;
        }
        return ResponseStyle.STANDARD;
    }

    private static boolean isStandaloneTaskRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!TASK_EXECUTION_HINTS.matcher(text).find()) {
            return false;
        }
        if (QUESTION_LIKE_HINTS.matcher(text).find() || text.contains("?") || text.contains("\u00BF")) {
            return false;
        }
        if (RAG_REQUIRED_CONTEXT_HINTS.matcher(text).find() || RAG_PREFERRED_HINTS.matcher(text).find()) {
            return false;
        }
        return true;
    }

    private static boolean containsCriticalActuation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return HOME_CRITICAL_ACTUATION_HINTS.matcher(text).find();
    }

    private static void collectBooleanSignal(List<String> signals, boolean condition, String label) {
        if (condition) {
            signals.add(label);
        }
    }

    /**
     * Agrega una etiqueta de senal si el patron aparece en el texto.
     */
    private static void collectSignal(List<String> signals, String text, Pattern pattern, String label) {
        if (pattern.matcher(text).find()) {
            signals.add(label);
        }
    }

    /**
     * Construye una razon legible para logs y telemetria de routing.
     */
    private static String buildReason(String base, List<String> signals) {
        if (signals == null || signals.isEmpty()) {
            return base;
        }
        return base + ": " + String.join(", ", signals);
    }

    /**
     * Cuenta ocurrencias de un patron regex.
     */
    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Detecta frases repetidas como sintoma de relleno o salida de baja calidad.
     */
    private static boolean hasRepeatedSentence(String text) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        if (sentences.length < 2) {
            return false;
        }

        Map<String, Integer> seen = new HashMap<>();
        for (String sentence : sentences) {
            String normalized = normalizeSentence(sentence);
            if (normalized.length() < 24) {
                continue;
            }
            int current = seen.getOrDefault(normalized, 0) + 1;
            if (current >= 2) {
                return true;
            }
            seen.put(normalized, current);
        }
        return false;
    }

    /**
     * Normaliza una frase para compararla sin ruido de mayusculas o puntuacion.
     */
    private static String normalizeSentence(String sentence) {
        return collapseSpaces(sentence)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", "");
    }

    /**
     * Colapsa espacios para aplicar heuristicas sobre texto estable.
     */
    private static String collapseSpaces(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Cuenta ocurrencias de un caracter concreto.
     */
    private static int countChar(String text, char token) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == token) {
                count++;
            }
        }
        return count;
    }

    /**
     * Cuenta palabras para reglas heuristicas simples.
     */
    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

    /**
     * Resultado estructurado de captura de intencion del turno.
     */
    public record IntentProfile(IntentCategory category,
                                ResponseStyle responseStyle,
                                boolean homeAutomation,
                                boolean autonomousDecisionRequested,
                                boolean learningRequested,
                                boolean requiresConfirmation,
                                String reason,
                                List<String> signals) {

        public IntentProfile {
            category = category == null ? IntentCategory.UNKNOWN : category;
            responseStyle = responseStyle == null ? ResponseStyle.STANDARD : responseStyle;
            reason = reason == null ? "" : reason.trim();
            signals = signals == null ? List.of() : List.copyOf(signals);
        }

        public static IntentProfile unknown(String reason, List<String> signals) {
            return new IntentProfile(
                    IntentCategory.UNKNOWN,
                    ResponseStyle.STANDARD,
                    false,
                    false,
                    false,
                    false,
                    reason,
                    signals
            );
        }
    }

    /**
     * Describe el resultado del router de RAG junto con las senales que activaron la decision.
     */
    public record RagDecision(RagMode mode, String reason, List<String> signals) {

        /**
         * Normaliza el resultado para que siempre tenga modo, razon y lista de senales validas.
         */
        public RagDecision {
            mode = mode == null ? RagMode.OFF : mode;
            reason = reason == null ? "" : reason.trim();
            signals = signals == null ? List.of() : List.copyOf(signals);
        }

        /**
         * Indica si la decision activa cualquier ruta de RAG.
         */
        public boolean enabled() {
            return mode != RagMode.OFF;
        }

        /**
         * Indica si la ausencia de evidencia debe forzar fallback en vez de chat libre.
         */
        public boolean requiresEvidence() {
            return mode == RagMode.REQUIRED;
        }

        /**
         * Fabrica una decision sin uso de RAG.
         */
        public static RagDecision off(String reason) {
            return new RagDecision(RagMode.OFF, reason, List.of());
        }

        /**
         * Fabrica una decision de RAG obligatorio.
         */
        public static RagDecision required(String reason, List<String> signals) {
            return new RagDecision(RagMode.REQUIRED, reason, signals);
        }

        /**
         * Fabrica una decision de RAG preferido pero no obligatorio.
         */
        public static RagDecision preferred(String reason, List<String> signals) {
            return new RagDecision(RagMode.PREFERRED, reason, signals);
        }
    }
}
