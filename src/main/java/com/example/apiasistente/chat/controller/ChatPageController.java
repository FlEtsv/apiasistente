package com.example.apiasistente.chat.controller;

import com.example.apiasistente.setup.service.SetupConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador para Chat Page.
 */
@Controller
public class ChatPageController {

    private final SetupConfigService setupConfigService;
    private final String grafanaBaseUrl;
    private final String prometheusBaseUrl;

    public ChatPageController(
            SetupConfigService setupConfigService,
            @Value("${monitoring.grafana-url:http://192.168.1.61:3000}") String grafanaBaseUrl,
            @Value("${monitoring.prometheus-url:http://192.168.1.61:9090}") String prometheusBaseUrl
    ) {
        this.setupConfigService = setupConfigService;
        this.grafanaBaseUrl = normalizeBaseUrl(grafanaBaseUrl, 3000);
        this.prometheusBaseUrl = normalizeBaseUrl(prometheusBaseUrl, 9090);
    }

    @GetMapping("/chat")
    public String chatPage(Model model) {
        if (!setupConfigService.isConfigured()) {
            return "redirect:/setup";
        }
        model.addAttribute("grafanaBaseUrl", grafanaBaseUrl);
        model.addAttribute("prometheusBaseUrl", prometheusBaseUrl);
        return "chat";
    }

    private String normalizeBaseUrl(String raw, int defaultPort) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return "http://localhost:" + defaultPort;
        }
        String withScheme = value.startsWith("http://") || value.startsWith("https://")
                ? value
                : "http://" + value;
        try {
            java.net.URI uri = java.net.URI.create(withScheme);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return trimSlash(withScheme);
            }
            int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;
            String path = uri.getPath();
            org.springframework.web.util.UriComponentsBuilder b = org.springframework.web.util.UriComponentsBuilder
                    .newInstance()
                    .scheme(scheme)
                    .host(host)
                    .port(port);
            if (path != null && !path.isBlank() && !"/".equals(path)) {
                b.path(path);
            }
            return trimSlash(b.build().toUriString());
        } catch (Exception ex) {
            return trimSlash(withScheme);
        }
    }

    private String trimSlash(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        return input.endsWith("/") ? input.substring(0, input.length() - 1) : input;
    }
}

