package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Normaliza y prepara adjuntos del turno.
 * Extrae texto, filtra tipos utiles y puede generar un puente visual/documental previo al prompt final.
 */
@Service
public class ChatMediaService {

    private static final Logger log = LoggerFactory.getLogger(ChatMediaService.class);

    private static final int MAX_MEDIA_ITEMS = 4;
    private static final int MAX_MEDIA_TEXT_CHARS = 18_000;
    private static final int MAX_VISUAL_CONTEXT_CHARS = 3_200;

    private final OllamaClient ollama;
    private final ChatModelSelector modelSelector;

    public ChatMediaService(OllamaClient ollama, ChatModelSelector modelSelector) {
        this.ollama = ollama;
        this.modelSelector = modelSelector;
    }

    /**
     * Convierte adjuntos crudos en una representacion segura y acotada para el pipeline.
     */
    public List<PreparedMedia> prepareMedia(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }

        List<PreparedMedia> prepared = new ArrayList<>();
        for (ChatMediaInput raw : media) {
            if (raw == null) {
                continue;
            }
            if (prepared.size() >= MAX_MEDIA_ITEMS) {
                break;
            }

            String name = sanitizeName(raw.getName());
            String mimeType = sanitizeMimeType(raw.getMimeType());
            String base64 = sanitizeBase64(raw.getBase64());
            String directText = sanitizeText(raw.getText(), MAX_MEDIA_TEXT_CHARS);

            // Conserva la imagen solo si realmente es un mime visual con contenido util.
            String imageBase64 = "";
            if (isImageMime(mimeType) && hasText(base64)) {
                imageBase64 = base64;
            }

            // Prioriza texto ya provisto; si no existe, intenta extraerlo segun el tipo de archivo.
            String documentText = "";
            if (hasText(directText)) {
                documentText = directText;
            } else if (hasText(base64) && isPdfMime(mimeType)) {
                documentText = sanitizeText(extractPdfText(base64), MAX_MEDIA_TEXT_CHARS);
            } else if (hasText(base64) && isTextMime(mimeType)) {
                documentText = sanitizeText(decodeBase64AsText(base64), MAX_MEDIA_TEXT_CHARS);
            }

            if (!hasText(imageBase64) && !hasText(documentText)) {
                continue;
            }

            prepared.add(new PreparedMedia(name, mimeType, imageBase64, documentText));
        }

        return Collections.unmodifiableList(prepared);
    }

    /**
     * Indica si el turno trae al menos una imagen usable.
     */
    public boolean hasImageMedia(List<PreparedMedia> media) {
        return media != null && media.stream().anyMatch(item -> hasText(item.imageBase64()));
    }

    /**
     * Indica si el turno trae texto documental usable.
     */
    public boolean hasDocumentMedia(List<PreparedMedia> media) {
        return media != null && media.stream().anyMatch(item -> hasText(item.documentText()));
    }

    /**
     * Ejecuta un analisis visual/documental intermedio para resumir imagenes antes del prompt final.
     */
    public String buildVisualBridgeContext(String userText,
                                           List<PreparedMedia> media,
                                           String requestedModel) {
        if (media == null || media.isEmpty()) {
            return "";
        }

        List<String> images = media.stream()
                .map(PreparedMedia::imageBase64)
                .filter(this::hasText)
                .toList();
        if (images.isEmpty()) {
            return "";
        }
        long documentCount = media.stream().filter(item -> hasText(item.documentText())).count();
        String visualModel = "";

        StringBuilder prompt = new StringBuilder();
        prompt.append("Analiza el material visual/documental y extrae hechos verificables.\n");
        prompt.append("Responde SOLO con:\n");
        prompt.append("1) Observaciones clave\n");
        prompt.append("2) Datos concretos\n");
        prompt.append("3) Incertidumbres o limites\n\n");

        for (PreparedMedia item : media) {
            if (!hasText(item.documentText())) {
                continue;
            }
            // Inserta texto de apoyo para que el modelo visual pueda correlacionar imagen y documento.
            String text = item.documentText();
            if (text.length() > 2200) {
                text = text.substring(0, 2200);
            }
            prompt.append("Documento '").append(item.name()).append("':\n");
            prompt.append(text).append("\n\n");
        }

        prompt.append("Pregunta del usuario: ").append(userText);

        try {
            visualModel = modelSelector.resolveVisualModel(requestedModel);
            log.info(
                    "visual_bridge_start model={} requestedModel={} imageCount={} documentCount={} promptPreview={}",
                    visualModel,
                    requestedModel == null ? "" : requestedModel,
                    images.size(),
                    documentCount,
                    preview(userText)
            );
            // El puente visual no responde al usuario: solo resume hechos reutilizables por el turno final.
            String bridge = ollama.chat(
                    List.of(new OllamaClient.Message("user", prompt.toString(), images)),
                    visualModel
            );

            if (!hasText(bridge)) {
                return "";
            }
            String clean = bridge.trim();
            if (clean.length() > MAX_VISUAL_CONTEXT_CHARS) {
                clean = clean.substring(0, MAX_VISUAL_CONTEXT_CHARS);
            }
            log.info(
                    "visual_bridge_done model={} imageCount={} documentCount={} bridgeChars={}",
                    visualModel,
                    images.size(),
                    documentCount,
                    clean.length()
            );
            return clean;
        } catch (Exception ex) {
            log.warn(
                    "visual_bridge_failed model={} imageCount={} documentCount={} cause={}",
                    hasText(visualModel) ? visualModel : "unresolved",
                    images.size(),
                    documentCount,
                    ex.getMessage()
            );
            return "";
        }
    }

    /**
     * Normaliza el nombre mostrado del adjunto.
     */
    private String sanitizeName(String name) {
        String clean = hasText(name) ? name.trim() : "archivo";
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    /**
     * Normaliza el mime type para decisiones de procesamiento posteriores.
     */
    private String sanitizeMimeType(String mimeType) {
        String clean = hasText(mimeType) ? mimeType.trim().toLowerCase(Locale.ROOT) : "application/octet-stream";
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    /**
     * Recorta y limpia texto extraido o enviado directamente por el cliente.
     */
    private String sanitizeText(String text, int maxChars) {
        if (!hasText(text)) {
            return "";
        }
        String clean = text.replaceAll("\\u0000", "").trim();
        if (clean.length() > maxChars) {
            clean = clean.substring(0, maxChars);
        }
        return clean;
    }

    /**
     * Limpia payload base64 removiendo prefijos data URI, espacios y longitud excesiva.
     */
    private String sanitizeBase64(String value) {
        if (!hasText(value)) {
            return "";
        }
        String clean = value.trim();
        int comma = clean.indexOf(',');
        if (comma >= 0) {
            clean = clean.substring(comma + 1);
        }
        clean = clean.replaceAll("\\s+", "");
        if (clean.length() > 12_000_000) {
            clean = clean.substring(0, 12_000_000);
        }
        return clean;
    }

    /**
     * Detecta tipos MIME de imagen.
     */
    private boolean isImageMime(String mimeType) {
        return hasText(mimeType) && mimeType.startsWith("image/");
    }

    /**
     * Detecta PDFs para intentar extraccion de texto.
     */
    private boolean isPdfMime(String mimeType) {
        return "application/pdf".equalsIgnoreCase(mimeType);
    }

    /**
     * Detecta tipos textuales que pueden decodificarse como UTF-8.
     */
    private boolean isTextMime(String mimeType) {
        if (!hasText(mimeType)) {
            return false;
        }
        return mimeType.startsWith("text/")
                || mimeType.contains("json")
                || mimeType.contains("xml")
                || mimeType.contains("csv")
                || mimeType.contains("javascript");
    }

    /**
     * Extrae texto de un PDF base64 y lo deja ya sanitizado para el prompt.
     */
    private String extractPdfText(String base64) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) {
            return "";
        }

        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return sanitizeText(stripper.getText(document), MAX_MEDIA_TEXT_CHARS);
        } catch (Exception ex) {
            log.warn("No se pudo extraer texto PDF: {}", ex.getMessage());
            return "";
        }
    }

    /**
     * Decodifica base64 textual a UTF-8.
     */
    private String decodeBase64AsText(String base64) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Decodifica base64 devolviendo arreglo vacio cuando el payload es invalido.
     */
    private byte[] decodeBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    /**
     * Ayuda local para validar texto util.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String preview(String value) {
        if (!hasText(value)) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= 120) {
            return clean;
        }
        return clean.substring(0, 120).trim() + "...";
    }

    /**
     * Representa un adjunto ya listo para el pipeline de chat.
     */
    public record PreparedMedia(String name, String mimeType, String imageBase64, String documentText) {
    }
}


