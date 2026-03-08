package com.example.apiasistente.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion para bootstrap del primer usuario administrador.
 */
@Component
@ConfigurationProperties(prefix = "bootstrap.admin")
public class AdminBootstrapProperties {

    private boolean enabled = true;
    private String username = "admin";
    private String password = "";
    private boolean generateRandomPasswordIfEmpty = true;
    private String outputFile = "data/bootstrap-admin.txt";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isGenerateRandomPasswordIfEmpty() {
        return generateRandomPasswordIfEmpty;
    }

    public void setGenerateRandomPasswordIfEmpty(boolean generateRandomPasswordIfEmpty) {
        this.generateRandomPasswordIfEmpty = generateRandomPasswordIfEmpty;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }
}
