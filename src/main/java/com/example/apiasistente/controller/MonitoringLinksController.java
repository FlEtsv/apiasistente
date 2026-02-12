package com.example.apiasistente.controller;

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

import java.time.Instant;

@Controller
@RequestMapping("/ops")
public class MonitoringLinksController {

    private final String grafanaBaseUrl;
    private final String prometheusBaseUrl;
    private final RestClient restClient;

    public MonitoringLinksController(
            @Value("${monitoring.grafana-url:http://localhost:3000}") String grafanaBaseUrl,
            @Value("${monitoring.prometheus-url:http://localhost:9090}") String prometheusBaseUrl
    ) {
        this.grafanaBaseUrl = normalizeBaseUrl(grafanaBaseUrl);
        this.prometheusBaseUrl = normalizeBaseUrl(prometheusBaseUrl);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .build();
    }

    @GetMapping("/grafana")
    public RedirectView grafana() {
        return redirect(grafanaBaseUrl, "");
    }

    @GetMapping("/grafana/config")
    public RedirectView grafanaConfig() {
        return redirect(grafanaBaseUrl, "/admin");
    }

    @GetMapping("/prometheus")
    public RedirectView prometheus() {
        return redirect(prometheusBaseUrl, "");
    }

    @GetMapping("/prometheus/config")
    public RedirectView prometheusConfig() {
        return redirect(prometheusBaseUrl, "/config");
    }

    @GetMapping("/status")
    @ResponseBody
    public MonitoringStatus status() {
        ServiceStatus grafana = probe("grafana", grafanaBaseUrl, "/api/health");
        ServiceStatus prometheus = probe("prometheus", prometheusBaseUrl, "/-/healthy");
        return new MonitoringStatus(Instant.now(), grafana, prometheus);
    }

    @GetMapping("/status/ui")
    public String statusPage(Model model) {
        model.addAttribute("grafanaBaseUrl", grafanaBaseUrl);
        model.addAttribute("prometheusBaseUrl", prometheusBaseUrl);
        return "ops_status";
    }

    private RedirectView redirect(String baseUrl, String path) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl).path(path).build().toUriString();
        RedirectView view = new RedirectView(url);
        view.setExposeModelAttributes(false);
        return view;
    }

    private ServiceStatus probe(String name, String baseUrl, String path) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl).path(path).build().toUriString();
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

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            return "http://localhost";
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "http://" + trimmed;
    }

    public record MonitoringStatus(Instant timestamp, ServiceStatus grafana, ServiceStatus prometheus) {}

    public record ServiceStatus(String name, String baseUrl, String url, boolean up, int status, long elapsedMs, String error) {}
}
