package com.example.apiasistente.monitoring.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;
import org.springframework.ui.Model;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

/**
 * Controlador de enlaces operativos hacia Grafana y Prometheus.
 * <p>
 * Incluye redirecciones directas y una sonda HTTP ligera para diagnostico.
 */
@Controller
@RequestMapping("/ops")
public class MonitoringLinksController {

    private final String grafanaBaseUrl;
    private final String prometheusBaseUrl;
    private final RestClient restClient;

    public MonitoringLinksController(
            @Value("${monitoring.grafana-url:http://192.168.1.61:3000}") String grafanaBaseUrl,
            @Value("${monitoring.prometheus-url:http://192.168.1.61:9090}") String prometheusBaseUrl
    ) {
        this.grafanaBaseUrl = normalizeBaseUrl(grafanaBaseUrl, 3000);
        this.prometheusBaseUrl = normalizeBaseUrl(prometheusBaseUrl, 9090);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .build();
    }

    /**
     * Redirecciona al home de Grafana.
     *
     * @return redireccion absoluta a Grafana
     */
    @GetMapping("/grafana")
    public RedirectView grafana() {
        return redirect(grafanaBaseUrl, "");
    }

    /**
     * Redirecciona a panel de administracion de Grafana.
     *
     * @return redireccion absoluta a Grafana admin
     */
    @GetMapping("/grafana/config")
    public RedirectView grafanaConfig() {
        return redirect(grafanaBaseUrl, "/admin");
    }

    /**
     * Redirecciona al home de Prometheus.
     *
     * @return redireccion absoluta a Prometheus
     */
    @GetMapping("/prometheus")
    public RedirectView prometheus() {
        return redirect(prometheusBaseUrl, "");
    }

    /**
     * Redirecciona a la vista de configuracion de Prometheus.
     *
     * @return redireccion absoluta a /config de Prometheus
     */
    @GetMapping("/prometheus/config")
    public RedirectView prometheusConfig() {
        return redirect(prometheusBaseUrl, "/config");
    }

    /**
     * Ejecuta chequeo remoto de salud para Grafana y Prometheus.
     *
     * @return payload con resultado por servicio
     */
    @GetMapping("/status")
    @ResponseBody
    public MonitoringStatus status() {
        ServiceStatus grafana = probe("grafana", grafanaBaseUrl, "/api/health");
        ServiceStatus prometheus = probe("prometheus", prometheusBaseUrl, "/-/healthy");
        return new MonitoringStatus(Instant.now(), grafana, prometheus);
    }

    /**
     * Renderiza UI de estado con enlaces de monitor.
     *
     * @param model modelo de la vista
     * @return plantilla Thymeleaf de estado operativo
     */
    @GetMapping("/status/ui")
    public String statusPage(Model model) {
        model.addAttribute("grafanaBaseUrl", grafanaBaseUrl);
        model.addAttribute("prometheusBaseUrl", prometheusBaseUrl);
        return "ops_status";
    }

    private RedirectView redirect(String baseUrl, String path) {
        String url = UriComponentsBuilder.fromUriString(baseUrl).path(path).build().toUriString();
        RedirectView view = new RedirectView(url);
        view.setExposeModelAttributes(false);
        return view;
    }

    private ServiceStatus probe(String name, String baseUrl, String path) {
        String url = UriComponentsBuilder.fromUriString(baseUrl).path(path).build().toUriString();
        long startNs = System.nanoTime();
        try {
            int status = restClient.get()
                    .uri(url)
                    .exchange((req, res) -> res.getStatusCode().value());
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            boolean up = status >= 200 && status < 300;
            String error = up ? "" : "http " + status;
            return new ServiceStatus(name, baseUrl, url, up, status, elapsedMs, error);
        } catch (Exception ex) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            return new ServiceStatus(name, baseUrl, url, false, -1, elapsedMs, shortError(ex));
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return factory;
    }

    private String shortError(Exception ex) {
        if (ex == null) return "error";
        String msg = ex.getMessage();
        if (msg == null) {
            return ex.getClass().getSimpleName();
        }
        String trimmed = msg.trim();
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200);
        }
        return ex.getClass().getSimpleName() + ": " + trimmed;
    }

    private String normalizeBaseUrl(String baseUrl, int defaultPort) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            return "http://localhost:" + defaultPort;
        }
        String withScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
                ? trimmed
                : "http://" + trimmed;
        try {
            URI parsed = URI.create(withScheme);
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme();
            String host = parsed.getHost();
            if (host == null || host.isBlank()) {
                return stripTrailingSlash(withScheme);
            }
            int port = parsed.getPort() > 0 ? parsed.getPort() : defaultPort;
            UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                    .scheme(scheme)
                    .host(host)
                    .port(port);
            String path = parsed.getPath();
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                builder.path(path);
            }
            return stripTrailingSlash(builder.build().toUriString());
        } catch (Exception ex) {
            return stripTrailingSlash(withScheme);
        }
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    public record MonitoringStatus(Instant timestamp, ServiceStatus grafana, ServiceStatus prometheus) {}

    public record ServiceStatus(String name, String baseUrl, String url, boolean up, int status, long elapsedMs, String error) {}
}

