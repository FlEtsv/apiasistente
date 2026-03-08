package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.monitoring.dto.MonitoringStackStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Gestiona inspeccion y activacion del stack Docker (API + Prometheus + Grafana).
 * <p>
 * Separa dos responsabilidades de alto nivel:
 * diagnostico no destructivo ({@link #status()}) y activacion idempotente ({@link #ensureUp()}).
 */
@Service
public class MonitoringStackService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringStackService.class);
    private static final int INSPECT_TIMEOUT_MS = 12_000;
    private static final int OUTPUT_LIMIT_CHARS = 3000;

    private final String projectDirConfig;
    private final String composeFileConfig;
    private final String startScriptConfig;
    private final long commandTimeoutMs;
    private final List<String> stackServices;
    private final String apiContainerName;
    private final String prometheusContainerName;
    private final String grafanaContainerName;
    private final String selfApiHealthUrl;
    private final RestClient selfApiClient;
    private final String grafanaHealthUrl;
    private final String prometheusHealthUrl;
    private final RestClient externalMonitorClient;

    public MonitoringStackService(
            @Value("${monitoring.stack.project-dir:.}") String projectDirConfig,
            @Value("${monitoring.stack.compose-file:docker-compose.yml}") String composeFileConfig,
            @Value("${monitoring.stack.start-script:}") String startScriptConfig,
            @Value("${monitoring.stack.command-timeout-ms:180000}") long commandTimeoutMs,
            @Value("${monitoring.stack.services:api,prometheus,grafana}") String stackServicesConfig,
            @Value("${monitoring.stack.api-container-name:apiasistente}") String apiContainerName,
            @Value("${monitoring.stack.prometheus-container-name:apiasistente_prometheus}") String prometheusContainerName,
            @Value("${monitoring.stack.grafana-container-name:apiasistente_grafana}") String grafanaContainerName,
            @Value("${monitoring.grafana-url:http://192.168.1.61:3000}") String grafanaBaseUrl,
            @Value("${monitoring.prometheus-url:http://192.168.1.61:9090}") String prometheusBaseUrl,
            @Value("${server.port:8082}") int serverPort
    ) {
        this.projectDirConfig = blankToDefault(projectDirConfig, ".");
        this.composeFileConfig = blankToDefault(composeFileConfig, "docker-compose.yml");
        this.startScriptConfig = startScriptConfig == null ? "" : startScriptConfig.trim();
        this.commandTimeoutMs = Math.max(15_000L, commandTimeoutMs);
        this.stackServices = parseServices(stackServicesConfig);
        this.apiContainerName = blankToDefault(apiContainerName, "apiasistente");
        this.prometheusContainerName = blankToDefault(prometheusContainerName, "apiasistente_prometheus");
        this.grafanaContainerName = blankToDefault(grafanaContainerName, "apiasistente_grafana");
        this.selfApiHealthUrl = "http://localhost:" + Math.max(1, serverPort) + "/actuator/health";
        this.selfApiClient = RestClient.builder()
                .requestFactory(simpleHttpFactory(1200, 1200))
                .build();
        this.grafanaHealthUrl = buildHealthUrl(grafanaBaseUrl, "/api/health");
        this.prometheusHealthUrl = buildHealthUrl(prometheusBaseUrl, "/-/healthy");
        this.externalMonitorClient = RestClient.builder()
                .requestFactory(simpleHttpFactory(1500, 1500))
                .build();
    }

    /**
     * Inspecciona estado actual del stack sin ejecutar cambios.
     *
     * @return estado operativo y diagnostico de entorno Docker
     */
    public MonitoringStackStatusDto status() {
        return inspect(false, "", "");
    }

    /**
     * Intenta dejar operativo el stack de observabilidad usando script o compose.
     *
     * @return estado posterior de activacion con salida del comando ejecutado
     */
    public MonitoringStackStatusDto ensureUp() {
        MonitoringStackStatusDto before = inspect(false, "", "");
        if (!before.dockerInstalled()) {
            return withAction(before, false, "Docker CLI no disponible en este servidor.", "", "");
        }
        if (!before.dockerReachable()) {
            return withAction(before, false, "Docker instalado pero el daemon no responde.", "", "");
        }
        if (!before.composeAvailable()) {
            return withAction(before, false, "Docker Compose no disponible.", "", "");
        }
        if (!before.composeFileFound()) {
            return withAction(
                    before,
                    false,
                    "No se encontro el archivo compose: " + before.composeFile(),
                    "",
                    ""
            );
        }

        if (before.apiContainerRunning() && before.prometheusContainerRunning() && before.grafanaContainerRunning()) {
            return withAction(before, true, "Stack ya operativo.", "", "");
        }

        List<String> servicesToStart = resolveServicesForLaunch(before);
        ExecutionPlan plan = resolveExecutionPlan(before, servicesToStart);
        if (plan.command().isEmpty()) {
            return withAction(before, false, "No se pudo resolver comando de arranque.", "", "");
        }

        CommandResult run = runCommand(plan.command(), Path.of(before.projectDir()), commandTimeoutMs);
        MonitoringStackStatusDto after = inspect(true, plan.printableCommand(), run.output());
        boolean allRunning = after.apiContainerRunning() && after.prometheusContainerRunning() && after.grafanaContainerRunning();
        boolean success = allRunning;
        String message;
        if (success && run.exitCode() == 0) {
            message = "Stack de observabilidad activo (API + Prometheus + Grafana).";
        } else if (success) {
            message = "Stack operativo; el comando devolvio error, pero los servicios estan arriba.";
        } else {
            message = "No se pudo activar completamente el stack. Revisa salida del comando.";
        }
        return withAction(after, success, message, plan.printableCommand(), run.output());
    }

    private MonitoringStackStatusDto inspect(boolean actionExecuted, String command, String output) {
        Path projectDir = Path.of(projectDirConfig).toAbsolutePath().normalize();
        Path composePath = resolveComposePath(projectDir);

        CommandResult dockerVersion = runCommand(List.of("docker", "--version"), projectDir, INSPECT_TIMEOUT_MS);
        boolean dockerInstalled = dockerVersion.executed() && dockerVersion.exitCode() == 0;

        CommandResult dockerInfo = dockerInstalled
                ? runCommand(List.of("docker", "info", "--format", "{{.ServerVersion}}"), projectDir, INSPECT_TIMEOUT_MS)
                : CommandResult.skipped("docker-not-installed");
        boolean dockerReachable = dockerInfo.executed() && dockerInfo.exitCode() == 0;

        String composeCommand = resolveComposeCommand(projectDir);
        boolean composeAvailable = !composeCommand.isBlank();
        Map<String, String> containerStates = dockerReachable ? readContainerStates(projectDir) : Map.of();
        ComposeServiceState composeState = (dockerReachable && composeAvailable && Files.exists(composePath))
                ? readComposeServiceState(projectDir, composePath, composeCommand)
                : ComposeServiceState.empty();
        boolean grafanaHealthUp = isExternalEndpointUp(grafanaHealthUrl);
        boolean prometheusHealthUp = isExternalEndpointUp(prometheusHealthUrl);

        boolean selfApiUp = isSelfApiHealthy();
        boolean apiPresent = selfApiUp || composeState.hasService("api") || containerStates.containsKey(apiContainerName);
        boolean promPresent = prometheusHealthUp || composeState.hasService("prometheus") || containerStates.containsKey(prometheusContainerName);
        boolean grafanaPresent = grafanaHealthUp || composeState.hasService("grafana") || containerStates.containsKey(grafanaContainerName);

        boolean apiRunning = selfApiUp || composeState.isRunning("api") || isRunning(containerStates.get(apiContainerName));
        boolean promRunning = prometheusHealthUp || composeState.isRunning("prometheus") || isRunning(containerStates.get(prometheusContainerName));
        boolean grafanaRunning = grafanaHealthUp || composeState.isRunning("grafana") || isRunning(containerStates.get(grafanaContainerName));

        String message = summarize(
                dockerInstalled,
                dockerReachable,
                composeAvailable,
                apiRunning,
                promRunning,
                grafanaRunning
        );

        String diagnosticOutput = mergeOutput(output, dockerVersion.output(), dockerInfo.output());

        return new MonitoringStackStatusDto(
                Instant.now(),
                actionExecuted,
                false,
                dockerInstalled,
                dockerReachable,
                composeAvailable,
                composeCommand,
                projectDir.toString(),
                composePath.toString(),
                Files.exists(composePath),
                apiPresent,
                apiRunning,
                promPresent,
                promRunning,
                grafanaPresent,
                grafanaRunning,
                command == null ? "" : command,
                message,
                diagnosticOutput
        );
    }

    private MonitoringStackStatusDto withAction(MonitoringStackStatusDto base,
                                                boolean success,
                                                String message,
                                                String command,
                                                String output) {
        return new MonitoringStackStatusDto(
                Instant.now(),
                true,
                success,
                base.dockerInstalled(),
                base.dockerReachable(),
                base.composeAvailable(),
                base.composeCommand(),
                base.projectDir(),
                base.composeFile(),
                base.composeFileFound(),
                base.apiContainerPresent(),
                base.apiContainerRunning(),
                base.prometheusContainerPresent(),
                base.prometheusContainerRunning(),
                base.grafanaContainerPresent(),
                base.grafanaContainerRunning(),
                command == null ? "" : command,
                blankToDefault(message, base.message()),
                mergeOutput(output, base.output())
        );
    }

    private Path resolveComposePath(Path projectDir) {
        Path configured = Path.of(composeFileConfig);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return projectDir.resolve(configured).normalize();
    }

    private String resolveComposeCommand(Path projectDir) {
        CommandResult plugin = runCommand(List.of("docker", "compose", "version"), projectDir, INSPECT_TIMEOUT_MS);
        if (plugin.executed() && plugin.exitCode() == 0) {
            return "docker compose";
        }
        CommandResult standalone = runCommand(List.of("docker-compose", "version"), projectDir, INSPECT_TIMEOUT_MS);
        if (standalone.executed() && standalone.exitCode() == 0) {
            return "docker-compose";
        }
        return "";
    }

    private List<String> composeBaseCommand(String composeCommand) {
        if (composeCommand == null || composeCommand.isBlank()) {
            return List.of();
        }
        if ("docker compose".equalsIgnoreCase(composeCommand.trim())) {
            return List.of("docker", "compose");
        }
        return List.of("docker-compose");
    }

    private ComposeServiceState readComposeServiceState(Path projectDir, Path composeFile, String composeCommand) {
        List<String> composeBase = composeBaseCommand(composeCommand);
        if (composeBase.isEmpty()) {
            return ComposeServiceState.empty();
        }

        List<String> allServicesCmd = new ArrayList<>(composeBase);
        allServicesCmd.add("-f");
        allServicesCmd.add(composeFile.toString());
        allServicesCmd.add("ps");
        allServicesCmd.add("--services");

        List<String> runningServicesCmd = new ArrayList<>(allServicesCmd);
        runningServicesCmd.add("--filter");
        runningServicesCmd.add("status=running");

        CommandResult all = runCommand(allServicesCmd, projectDir, INSPECT_TIMEOUT_MS);
        CommandResult running = runCommand(runningServicesCmd, projectDir, INSPECT_TIMEOUT_MS);

        if ((!all.executed() || all.exitCode() != 0) && (!running.executed() || running.exitCode() != 0)) {
            return ComposeServiceState.empty();
        }

        Set<String> allNames = parseServiceLines(all.output());
        Set<String> runningNames = parseServiceLines(running.output());
        return new ComposeServiceState(allNames, runningNames);
    }

    private Set<String> parseServiceLines(String output) {
        if (output == null || output.isBlank()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        String[] lines = output.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String normalized = line.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            values.add(normalized);
        }
        return values;
    }

    private ExecutionPlan resolveExecutionPlan(MonitoringStackStatusDto before, List<String> servicesToStart) {
        Path projectDir = Path.of(before.projectDir());
        Path scriptPath = resolveStartScript(projectDir);
        if (scriptPath != null && Files.exists(scriptPath)) {
            List<String> scriptCommand = buildScriptCommand(scriptPath, before, servicesToStart);
            if (!scriptCommand.isEmpty()) {
                return new ExecutionPlan(scriptCommand, String.join(" ", scriptCommand));
            }
        }
        List<String> composeBase = composeBaseCommand(before.composeCommand());
        if (composeBase.isEmpty()) {
            return new ExecutionPlan(List.of(), "");
        }
        List<String> command = new ArrayList<>(composeBase);
        command.add("-f");
        command.add(before.composeFile());
        command.add("up");
        command.add("-d");
        command.addAll(servicesToStart);
        return new ExecutionPlan(command, String.join(" ", command));
    }

    private List<String> resolveServicesForLaunch(MonitoringStackStatusDto before) {
        List<String> selected = new ArrayList<>(stackServices);
        boolean apiRequested = selected.stream().anyMatch(s -> "api".equalsIgnoreCase(s));
        if (apiRequested && !before.apiContainerRunning() && isSelfApiHealthy()) {
            selected.removeIf(s -> "api".equalsIgnoreCase(s));
        }
        if (selected.isEmpty()) {
            selected.add("prometheus");
            selected.add("grafana");
        }
        return selected;
    }

    private Path resolveStartScript(Path projectDir) {
        if (startScriptConfig != null && !startScriptConfig.isBlank()) {
            Path configured = Path.of(startScriptConfig);
            if (configured.isAbsolute()) {
                return configured.normalize();
            }
            return projectDir.resolve(configured).normalize();
        }
        String fallback = isWindows()
                ? "scripts/activate-observability-stack.ps1"
                : "scripts/activate-observability-stack.sh";
        Path fallbackPath = projectDir.resolve(fallback).normalize();
        return Files.exists(fallbackPath) ? fallbackPath : null;
    }

    private List<String> buildScriptCommand(Path scriptPath, MonitoringStackStatusDto before, List<String> servicesToStart) {
        String servicesCsv = String.join(",", servicesToStart);
        String fileName = scriptPath.getFileName() == null ? "" : scriptPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".ps1")) {
            if (isWindows()) {
                return List.of(
                        "powershell",
                        "-NoProfile",
                        "-ExecutionPolicy", "Bypass",
                        "-File", scriptPath.toString(),
                        "-ProjectDir", before.projectDir(),
                        "-ComposeFile", before.composeFile(),
                        "-Services", servicesCsv
                );
            }
            return List.of(
                    "pwsh",
                    "-NoProfile",
                    "-File", scriptPath.toString(),
                    "-ProjectDir", before.projectDir(),
                    "-ComposeFile", before.composeFile(),
                    "-Services", servicesCsv
            );
        }
        if (isWindows()) {
            return List.of(
                    "cmd",
                    "/c",
                    scriptPath.toString(),
                    before.projectDir(),
                    before.composeFile(),
                    servicesCsv
            );
        }
        return List.of(
                "bash",
                scriptPath.toString(),
                before.projectDir(),
                before.composeFile(),
                servicesCsv
        );
    }

    private Map<String, String> readContainerStates(Path projectDir) {
        CommandResult ps = runCommand(
                List.of("docker", "ps", "-a", "--format", "{{.Names}}\t{{.State}}\t{{.Status}}"),
                projectDir,
                INSPECT_TIMEOUT_MS
        );
        if (!ps.executed() || ps.exitCode() != 0 || ps.output().isBlank()) {
            return Map.of();
        }

        Map<String, String> states = new HashMap<>();
        String[] lines = ps.output().replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            if (name.isBlank()) {
                continue;
            }
            String state = parts[1].trim();
            String status = parts.length >= 3 ? parts[2].trim() : "";
            states.put(name, (state + " " + status).trim());
        }
        return states;
    }

    private boolean isRunning(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return false;
        }
        String normalized = rawState.toLowerCase(Locale.ROOT);
        return normalized.contains("running") || normalized.startsWith("up");
    }

    private String summarize(boolean dockerInstalled,
                             boolean dockerReachable,
                             boolean composeAvailable,
                             boolean apiRunning,
                             boolean promRunning,
                             boolean grafanaRunning) {
        if (!dockerInstalled) return "Docker CLI no instalado.";
        if (!dockerReachable) return "Docker daemon no disponible.";
        if (!composeAvailable) return "Docker Compose no disponible.";
        if (apiRunning && promRunning && grafanaRunning) return "Stack listo.";
        return "Stack parcial: API=" + yesNo(apiRunning)
                + ", Prometheus=" + yesNo(promRunning)
                + ", Grafana=" + yesNo(grafanaRunning) + ".";
    }

    private String yesNo(boolean value) {
        return value ? "ON" : "OFF";
    }

    private String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private List<String> parseServices(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("api", "prometheus", "grafana");
        }
        List<String> values = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        return values.isEmpty() ? List.of("api", "prometheus", "grafana") : values;
    }

    private String mergeOutput(String... outputs) {
        StringBuilder sb = new StringBuilder();
        if (outputs != null) {
            for (String output : outputs) {
                if (output == null || output.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(output.trim());
            }
        }
        if (sb.length() <= OUTPUT_LIMIT_CHARS) {
            return sb.toString();
        }
        return sb.substring(0, OUTPUT_LIMIT_CHARS).trim() + "\n... (salida truncada)";
    }

    private CommandResult runCommand(List<String> command, Path workDir, long timeoutMs) {
        if (command == null || command.isEmpty()) {
            return CommandResult.skipped("empty-command");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workDir != null && Files.isDirectory(workDir)) {
                builder.directory(workDir.toFile());
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(buffer);
                } catch (Exception ignored) {
                    // best-effort
                }
            });

            boolean finished = process.waitFor(Math.max(5_000L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            reader.join(2_000L);

            int exit = finished ? process.exitValue() : -1;
            String output = buffer.toString(StandardCharsets.UTF_8);
            return new CommandResult(true, exit, output);
        } catch (Exception ex) {
            log.debug("Error ejecutando comando {}: {}", command, ex.getMessage());
            return new CommandResult(false, -1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isSelfApiHealthy() {
        try {
            int status = selfApiClient.get()
                    .uri(selfApiHealthUrl)
                    .exchange((req, res) -> res.getStatusCode().value());
            return status >= 200 && status < 300;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isExternalEndpointUp(String healthUrl) {
        if (healthUrl == null || healthUrl.isBlank()) {
            return false;
        }
        try {
            int status = externalMonitorClient.get()
                    .uri(healthUrl)
                    .exchange((req, res) -> res.getStatusCode().value());
            return status >= 200 && status < 300;
        } catch (Exception ex) {
            return false;
        }
    }

    private SimpleClientHttpRequestFactory simpleHttpFactory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(200, connectMs));
        factory.setReadTimeout(Math.max(200, readMs));
        return factory;
    }

    private String buildHealthUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            return UriComponentsBuilder.fromUriString(baseUrl.trim()).path(path).build().toUriString();
        } catch (Exception ex) {
            return "";
        }
    }

    private record ComposeServiceState(Set<String> services, Set<String> runningServices) {
        static ComposeServiceState empty() {
            return new ComposeServiceState(Set.of(), Set.of());
        }

        boolean hasService(String service) {
            if (service == null || service.isBlank()) {
                return false;
            }
            return services.contains(service.trim().toLowerCase(Locale.ROOT));
        }

        boolean isRunning(String service) {
            if (service == null || service.isBlank()) {
                return false;
            }
            return runningServices.contains(service.trim().toLowerCase(Locale.ROOT));
        }
    }

    private record ExecutionPlan(List<String> command, String printableCommand) {
    }

    private record CommandResult(boolean executed, int exitCode, String output) {
        static CommandResult skipped(String reason) {
            return new CommandResult(false, -1, reason);
        }
    }
}
