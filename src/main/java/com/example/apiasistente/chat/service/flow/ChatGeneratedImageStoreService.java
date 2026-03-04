package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

/**
 * Almacena imágenes generadas por sesión para servirlas luego en el chat.
 */
@Service
public class ChatGeneratedImageStoreService {

    private final ChatImageGenerationProperties properties;

    public ChatGeneratedImageStoreService(ChatImageGenerationProperties properties) {
        this.properties = properties;
    }

    public String store(String sessionId, String mimeType, byte[] bytes) {
        if (!hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId requerido para guardar imagen.");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("No hay bytes de imagen para guardar.");
        }

        String cleanSessionId = sanitizeSegment(sessionId);
        String extension = extensionForMime(mimeType);
        String imageId = UUID.randomUUID().toString() + extension;
        Path dir = baseDir().resolve(cleanSessionId);
        Path path = dir.resolve(imageId);

        try {
            Files.createDirectories(dir);
            Files.write(path, bytes);
            return imageId;
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar imagen generada.", ex);
        }
    }

    public StoredImage load(String sessionId, String imageId) {
        if (!hasText(sessionId) || !hasText(imageId)) {
            throw new IllegalArgumentException("sessionId e imageId son obligatorios.");
        }
        String cleanSessionId = sanitizeSegment(sessionId);
        String cleanImageId = sanitizeSegment(imageId);
        Path path = baseDir().resolve(cleanSessionId).resolve(cleanImageId);

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new java.util.NoSuchElementException("Imagen generada no encontrada.");
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            String mimeType = Files.probeContentType(path);
            if (!hasText(mimeType)) {
                mimeType = mimeFromFilename(cleanImageId);
            }
            return new StoredImage(hasText(mimeType) ? mimeType : "image/png", bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer imagen generada.", ex);
        }
    }

    private Path baseDir() {
        String configured = properties.getStorageDir();
        if (!hasText(configured)) {
            configured = "data/chat-generated-images";
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private String sanitizeSegment(String value) {
        String clean = value.trim();
        if (clean.contains("..") || clean.contains("/") || clean.contains("\\")) {
            throw new IllegalArgumentException("Ruta de imagen inválida.");
        }
        return clean;
    }

    private String extensionForMime(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return ".jpg";
        }
        if (normalized.contains("webp")) {
            return ".webp";
        }
        if (normalized.contains("gif")) {
            return ".gif";
        }
        return ".png";
    }

    private String mimeFromFilename(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (normalized.endsWith(".webp")) {
            return "image/webp";
        }
        if (normalized.endsWith(".gif")) {
            return "image/gif";
        }
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record StoredImage(String mimeType, byte[] bytes) {
    }
}
