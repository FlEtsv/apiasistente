package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP client for ComfyUI API (`POST /prompt`) supporting txt2img and img2img.
 */
@Component
public class ChatImageGeneratorClient {

    private static final int MIN_IMAGE_SIZE = 256;
    private static final int MAX_IMAGE_SIZE = 2048;
    private static final String MODELS_PATH = "/models";
    private static final long CHECKPOINT_CACHE_TTL_MS = 30_000L;
    private static final Logger log = LoggerFactory.getLogger(ChatImageGeneratorClient.class);

    private final ChatImageGenerationProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CheckpointCatalog checkpointCatalog = CheckpointCatalog.empty();

    public ChatImageGeneratorClient(ChatImageGenerationProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                .build();
    }

    public GeneratedImage generate(String prompt, String model, String referenceImageDataUri) {
        String endpoint = resolveEndpoint(properties.getBaseUrl(), properties.getPromptPath());
        if (endpoint.isBlank()) {
            throw new IllegalStateException("No hay endpoint de generacion de imagen configurado.");
        }

        boolean explicitCheckpointRequested = looksLikeCheckpoint(model);
        String requestedCheckpoint = resolveCheckpoint(model);
        List<String> availableCheckpoints = resolveAvailableCheckpoints();
        String selectedCheckpoint = selectCheckpoint(
                requestedCheckpoint,
                model,
                availableCheckpoints,
                explicitCheckpointRequested
        );
        logCheckpointSelection(requestedCheckpoint, selectedCheckpoint, availableCheckpoints, explicitCheckpointRequested);

        try {
            return executeGenerate(
                    endpoint,
                    prompt,
                    selectedCheckpoint,
                    referenceImageDataUri,
                    model,
                    false,
                    requestedCheckpoint
            );
        } catch (IllegalStateException ex) {
            String fallbackCheckpoint = selectRetryFallbackCheckpoint(selectedCheckpoint, availableCheckpoints);
            if (shouldRetryWithFallback(ex, selectedCheckpoint, fallbackCheckpoint)) {
                log.warn(
                        "chat_image_provider_checkpoint_fallback requested={} fallback={} reason={}",
                        selectedCheckpoint,
                        fallbackCheckpoint,
                        safeMessage(ex)
                );
                return executeGenerate(
                        endpoint,
                        prompt,
                        fallbackCheckpoint,
                        referenceImageDataUri,
                        model,
                        true,
                        requestedCheckpoint
                );
            }
            throw ex;
        }
    }

    private GeneratedImage executeGenerate(String endpoint,
                                           String prompt,
                                           String checkpoint,
                                           String referenceImageDataUri,
                                           String requestedModel,
                                           boolean fallbackApplied,
                                           String requestedCheckpoint) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> requestPayload = buildComfyRequest(prompt, checkpoint, requestId, referenceImageDataUri);
        byte[] requestBody = serializeRequest(requestPayload);
        log.info(
                "chat_image_provider_request requestId={} endpoint={} model={} checkpoint={} mode={} fallback={}",
                requestId,
                endpoint,
                requestedModel,
                checkpoint,
                hasText(referenceImageDataUri) ? "img2img" : "txt2img",
                fallbackApplied
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .timeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            byte[] bodyBytes = response.body() == null ? new byte[0] : response.body();
            String responseBody = new String(bodyBytes, StandardCharsets.UTF_8);

            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                        "Generacion de imagen fallo. Status=" + status + " Body=" + preview(responseBody)
                );
            }

            JsonNode payload = objectMapper.readTree(bodyBytes);
            GeneratedImage image = decodeImage(payload);
            log.info(
                    "chat_image_provider_response requestId={} status={} bytes={} checkpoint={} fallback={}",
                    requestId,
                    status,
                    image.bytes().length,
                    checkpoint,
                    fallbackApplied
            );
            return new GeneratedImage(
                    image.mimeType(),
                    image.bytes(),
                    checkpoint,
                    fallbackApplied,
                    requestedCheckpoint
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar imagen: " + safeMessage(ex), ex);
        }
    }

    private byte[] serializeRequest(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo serializar la solicitud a ComfyUI.", ex);
        }
    }

    private Map<String, Object> buildComfyRequest(String prompt,
                                                  String checkpoint,
                                                  String requestId,
                                                  String referenceImageDataUri) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", requestId);
        body.put("prompt", buildPromptGraph(prompt, checkpoint, referenceImageDataUri));

        String convertFormat = normalize(properties.getConvertFormat());
        if (convertFormat != null) {
            Map<String, Object> convertOutput = new LinkedHashMap<>();
            convertOutput.put("format", convertFormat);
            if ("jpeg".equals(convertFormat) || "jpg".equals(convertFormat) || "webp".equals(convertFormat)) {
                convertOutput.put("options", Map.of("quality", clampInt(properties.getConvertQuality(), 1, 100)));
            }
            body.put("convert_output", convertOutput);
        }
        return body;
    }

    private Map<String, Object> buildPromptGraph(String prompt, String checkpoint, String referenceImageDataUri) {
        String referenceInput = normalizeReferenceImageInput(referenceImageDataUri);
        boolean img2img = hasText(referenceInput);
        long seed = resolveSeed(properties.getSeed());
        int width = clampInt(properties.getWidth(), MIN_IMAGE_SIZE, MAX_IMAGE_SIZE);
        int height = clampInt(properties.getHeight(), MIN_IMAGE_SIZE, MAX_IMAGE_SIZE);
        int steps = clampInt(properties.getSteps(), 1, 100);
        double cfgScale = clampDouble(properties.getCfgScale(), 0.0d, 20.0d, 1.0d);
        double denoise = img2img
                ? clampDouble(properties.getImg2imgDenoise(), 0.0d, 1.0d, 0.75d)
                : clampDouble(properties.getDenoise(), 0.0d, 1.0d, 1.0d);
        String samplerName = firstNonBlank(properties.getSamplerName(), "euler");
        String scheduler = firstNonBlank(properties.getScheduler(), "simple");
        String negativePrompt = properties.getNegativePrompt() == null ? "" : properties.getNegativePrompt().trim();
        String latentClassType = usesSd3LatentNode(checkpoint) ? "EmptySD3LatentImage" : "EmptyLatentImage";

        Map<String, Object> promptGraph = new LinkedHashMap<>();
        promptGraph.put("6", node(
                "CLIPTextEncode",
                Map.of(
                        "text", prompt == null ? "" : prompt,
                        "clip", List.of("30", 1)
                )
        ));
        promptGraph.put("8", node(
                "VAEDecode",
                Map.of(
                        "samples", List.of("31", 0),
                        "vae", List.of("30", 2)
                )
        ));
        promptGraph.put("9", node(
                "SaveImage",
                Map.of(
                        "filename_prefix", "ChatImage",
                        "images", List.of("8", 0)
                )
        ));
        promptGraph.put("30", node(
                "CheckpointLoaderSimple",
                Map.of("ckpt_name", checkpoint)
        ));

        if (img2img) {
            promptGraph.put("37", node(
                    "LoadImage",
                    Map.of(
                            "image", referenceInput,
                            "upload", "image"
                    )
            ));
            promptGraph.put("38", node(
                    "VAEEncode",
                    Map.of(
                            "pixels", List.of("37", 0),
                            "vae", List.of("30", 2)
                    )
            ));
        } else {
            promptGraph.put("27", node(
                    latentClassType,
                    Map.of(
                            "width", width,
                            "height", height,
                            "batch_size", 1
                    )
            ));
        }

        Map<String, Object> samplerInputs = new LinkedHashMap<>();
        samplerInputs.put("seed", seed);
        samplerInputs.put("steps", steps);
        samplerInputs.put("cfg", cfgScale);
        samplerInputs.put("sampler_name", samplerName);
        samplerInputs.put("scheduler", scheduler);
        samplerInputs.put("denoise", denoise);
        samplerInputs.put("model", List.of("30", 0));
        samplerInputs.put("positive", List.of("6", 0));
        samplerInputs.put("negative", List.of("33", 0));
        samplerInputs.put("latent_image", List.of(img2img ? "38" : "27", 0));
        promptGraph.put("31", node("KSampler", samplerInputs));

        promptGraph.put("33", node(
                "CLIPTextEncode",
                Map.of(
                        "text", negativePrompt,
                        "clip", List.of("30", 1)
                )
        ));
        return promptGraph;
    }

    private boolean usesSd3LatentNode(String checkpoint) {
        String ckpt = normalize(checkpoint);
        if (ckpt == null) {
            return false;
        }
        String lower = ckpt.toLowerCase(Locale.ROOT);
        return lower.contains("flux") || lower.contains("sd3");
    }

    private Map<String, Object> node(String classType, Map<String, Object> inputs) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("inputs", inputs);
        node.put("class_type", classType);
        return node;
    }

    private GeneratedImage decodeImage(JsonNode payload) {
        JsonNode images = payload.path("images");
        if (!images.isArray() || images.isEmpty()) {
            throw new IllegalStateException("Respuesta de ComfyUI sin imagenes en el campo `images`.");
        }

        String encoded = firstText(images);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("ComfyUI devolvio una imagen vacia.");
        }

        String mimeType = mimeFromFilename(firstText(payload.path("filenames")));
        String body = encoded.trim();
        if (body.startsWith("http://") || body.startsWith("https://") || body.startsWith("s3://")) {
            throw new IllegalStateException(
                    "ComfyUI devolvio URL en `images`; se esperaba base64 sin upload async."
            );
        }

        if (body.startsWith("data:")) {
            int comma = body.indexOf(',');
            if (comma <= 5) {
                throw new IllegalStateException("Data URI de imagen invalida.");
            }
            String meta = body.substring(5, comma).toLowerCase(Locale.ROOT);
            body = body.substring(comma + 1);
            int sep = meta.indexOf(';');
            if (sep > 0) {
                mimeType = meta.substring(0, sep);
            } else if (!meta.isBlank()) {
                mimeType = meta;
            }
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(body);
            if (bytes.length == 0) {
                throw new IllegalStateException("ComfyUI devolvio base64 vacio.");
            }
            return new GeneratedImage(
                    mimeType == null ? "image/png" : mimeType,
                    bytes,
                    null,
                    false,
                    null
            );
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("El payload de imagen no es base64 valido.", ex);
        }
    }

    private String resolveEndpoint(String baseUrl, String promptPath) {
        String base = normalize(baseUrl);
        if (base == null) {
            return "";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String path = normalize(promptPath);
        if (path == null) {
            path = "/prompt";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private String resolveCheckpoint(String requestedModel) {
        String configured = normalize(properties.getCheckpoint());
        String requested = normalize(requestedModel);
        if (requested != null && requested.endsWith(".safetensors")) {
            return requested;
        }
        if (configured != null) {
            return configured;
        }
        if (requested != null) {
            return requested;
        }
        throw new IllegalStateException("No hay checkpoint de imagen configurado para ComfyUI.");
    }

    /**
     * Carga checkpoints disponibles de ComfyUI y los cachea brevemente para no consultar `/models` en cada request.
     */
    private List<String> resolveAvailableCheckpoints() {
        long now = System.currentTimeMillis();
        CheckpointCatalog cached = checkpointCatalog;
        if (!cached.isExpired(now)) {
            return cached.checkpoints();
        }

        List<String> fetched = fetchAvailableCheckpoints();
        if (!fetched.isEmpty()) {
            checkpointCatalog = new CheckpointCatalog(now, fetched);
            return fetched;
        }

        if (!cached.checkpoints().isEmpty()) {
            return cached.checkpoints();
        }

        checkpointCatalog = new CheckpointCatalog(now, fetched);
        return fetched;
    }

    private List<String> fetchAvailableCheckpoints() {
        String modelsEndpoint = resolveEndpoint(properties.getBaseUrl(), MODELS_PATH);
        if (modelsEndpoint.isBlank()) {
            return List.of();
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(modelsEndpoint))
                .GET()
                .timeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                .header("Accept", "application/json")
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug(
                        "chat_image_provider_models_unavailable endpoint={} status={}",
                        modelsEndpoint,
                        response.statusCode()
                );
                return List.of();
            }

            JsonNode payload = objectMapper.readTree(response.body());
            JsonNode checkpoints = payload.path("checkpoints");
            if (!checkpoints.isArray()) {
                return List.of();
            }

            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode item : checkpoints) {
                if (item == null || !item.isTextual()) {
                    continue;
                }
                String checkpoint = normalize(item.asText());
                if (checkpoint != null) {
                    values.add(checkpoint);
                }
            }
            return List.copyOf(values);
        } catch (Exception ex) {
            log.warn(
                    "chat_image_provider_models_failed endpoint={} cause={}",
                    modelsEndpoint,
                    safeMessage(ex)
            );
            return List.of();
        }
    }

    private String selectCheckpoint(String requestedCheckpoint,
                                    String requestedModel,
                                    List<String> availableCheckpoints,
                                    boolean explicitCheckpointRequested) {
        if (availableCheckpoints == null || availableCheckpoints.isEmpty()) {
            return requestedCheckpoint;
        }

        String configuredCheckpoint = normalize(properties.getCheckpoint());

        if (explicitCheckpointRequested) {
            String candidate = findCheckpointByExact(availableCheckpoints, requestedCheckpoint);
            if (candidate != null) {
                return candidate;
            }
            candidate = findCheckpointByNormalizedKey(availableCheckpoints, requestedCheckpoint);
            if (candidate != null) {
                return candidate;
            }
            return requestedCheckpoint;
        }

        String candidate = findCheckpointByExact(availableCheckpoints, requestedCheckpoint);
        if (candidate != null) {
            return candidate;
        }
        candidate = findCheckpointByNormalizedKey(availableCheckpoints, requestedCheckpoint);
        if (candidate != null) {
            return candidate;
        }
        candidate = findCheckpointByExact(availableCheckpoints, configuredCheckpoint);
        if (candidate != null) {
            return candidate;
        }
        candidate = findCheckpointByNormalizedKey(availableCheckpoints, configuredCheckpoint);
        if (candidate != null) {
            return candidate;
        }
        candidate = findCheckpointByNormalizedKey(availableCheckpoints, requestedModel);
        if (candidate != null) {
            return candidate;
        }

        return availableCheckpoints.get(0);
    }

    private void logCheckpointSelection(String requestedCheckpoint,
                                        String selectedCheckpoint,
                                        List<String> availableCheckpoints,
                                        boolean explicitCheckpointRequested) {
        int available = availableCheckpoints == null ? 0 : availableCheckpoints.size();
        if (available == 0) {
            return;
        }

        if (requestedCheckpoint != null && requestedCheckpoint.equalsIgnoreCase(selectedCheckpoint)) {
            log.info(
                    "chat_image_checkpoint_selected requested={} selected={} explicit={} available={}",
                    requestedCheckpoint,
                    selectedCheckpoint,
                    explicitCheckpointRequested,
                    available
            );
            return;
        }

        log.warn(
                "chat_image_checkpoint_adjusted requested={} selected={} explicit={} available={} catalog={}",
                requestedCheckpoint,
                selectedCheckpoint,
                explicitCheckpointRequested,
                available,
                previewCheckpoints(availableCheckpoints)
        );
    }

    private String selectRetryFallbackCheckpoint(String failedCheckpoint, List<String> availableCheckpoints) {
        String configuredCheckpoint = normalize(properties.getCheckpoint());
        if (availableCheckpoints == null || availableCheckpoints.isEmpty()) {
            return configuredCheckpoint;
        }

        String candidate = findCheckpointByExact(availableCheckpoints, configuredCheckpoint);
        if (candidate == null) {
            candidate = findCheckpointByNormalizedKey(availableCheckpoints, configuredCheckpoint);
        }
        if (candidate != null && !candidate.equalsIgnoreCase(failedCheckpoint)) {
            return candidate;
        }

        for (String checkpoint : availableCheckpoints) {
            if (checkpoint != null && !checkpoint.equalsIgnoreCase(failedCheckpoint)) {
                return checkpoint;
            }
        }
        return configuredCheckpoint;
    }

    private String findCheckpointByExact(List<String> availableCheckpoints, String value) {
        String expected = normalize(value);
        if (expected == null) {
            return null;
        }
        for (String checkpoint : availableCheckpoints) {
            if (checkpoint != null && checkpoint.equalsIgnoreCase(expected)) {
                return checkpoint;
            }
        }
        return null;
    }

    private String findCheckpointByNormalizedKey(List<String> availableCheckpoints, String value) {
        String expectedKey = normalizeCheckpointKey(value);
        if (expectedKey == null) {
            return null;
        }
        for (String checkpoint : availableCheckpoints) {
            String checkpointKey = normalizeCheckpointKey(checkpoint);
            if (checkpointKey != null && checkpointKey.equals(expectedKey)) {
                return checkpoint;
            }
        }
        return null;
    }

    private String normalizeCheckpointKey(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".safetensors")) {
            lower = lower.substring(0, lower.length() - ".safetensors".length());
        }
        String collapsed = lower.replaceAll("[^a-z0-9]", "");
        return collapsed.isBlank() ? null : collapsed;
    }

    private String previewCheckpoints(List<String> checkpoints) {
        if (checkpoints == null || checkpoints.isEmpty()) {
            return "[]";
        }
        int limit = Math.min(5, checkpoints.size());
        List<String> sample = new ArrayList<>(checkpoints.subList(0, limit));
        if (checkpoints.size() > limit) {
            sample.add("...");
        }
        return sample.toString();
    }

    private boolean looksLikeCheckpoint(String value) {
        String normalized = normalize(value);
        return normalized != null && normalized.toLowerCase(Locale.ROOT).endsWith(".safetensors");
    }

    private String normalizeReferenceImageInput(String value) {
        if (!hasText(value)) {
            return null;
        }
        String clean = value.trim();
        if (clean.regionMatches(true, 0, "data:", 0, 5)) {
            int commaIndex = clean.indexOf(',');
            if (commaIndex >= 0 && commaIndex + 1 < clean.length()) {
                clean = clean.substring(commaIndex + 1).trim();
            }
        }
        return clean.replaceAll("\\s+", "");
    }

    private boolean shouldRetryWithFallback(IllegalStateException error,
                                            String requestedCheckpoint,
                                            String fallbackCheckpoint) {
        if (!hasText(requestedCheckpoint) || !hasText(fallbackCheckpoint)) {
            return false;
        }
        if (requestedCheckpoint.equalsIgnoreCase(fallbackCheckpoint)) {
            return false;
        }
        String message = normalize(error == null ? null : error.getMessage());
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("value_not_in_list")
                || (lower.contains("prompt_outputs_failed_validation")
                && (lower.contains("ckpt_name") || lower.contains("checkpointloadersimple")));
    }

    private long resolveSeed(long configured) {
        if (configured >= 0L) {
            return configured;
        }
        return ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String primary, String fallback) {
        String p = normalize(primary);
        return p != null ? p : fallback;
    }

    private String firstText(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
            return null;
        }
        JsonNode first = arrayNode.get(0);
        if (first == null || !first.isTextual()) {
            return null;
        }
        return first.asText();
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
        return "image/png";
    }

    private String preview(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String clean = body.replaceAll("\\s+", " ").trim();
        if (clean.length() <= 320) {
            return clean;
        }
        return clean.substring(0, 320).trim() + "...";
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().replaceAll("\\s+", " ").trim();
    }

    private record CheckpointCatalog(long loadedAtMillis, List<String> checkpoints) {

        private static CheckpointCatalog empty() {
            return new CheckpointCatalog(0L, List.of());
        }

        private boolean isExpired(long now) {
            return loadedAtMillis <= 0 || (now - loadedAtMillis) >= CHECKPOINT_CACHE_TTL_MS;
        }
    }

    public record GeneratedImage(
            String mimeType,
            byte[] bytes,
            String checkpoint,
            boolean fallbackApplied,
            String requestedCheckpoint
    ) {
    }
}
