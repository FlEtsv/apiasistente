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

        return RagDecision.off("Consulta general sin dependencia de conocimiento externo");
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
