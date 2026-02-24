package com.example.apiasistente.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, unique=true, length=80)
    private String username;

    @Column(name="password_hash", nullable=false, length=255)
    private String passwordHash;

    @Column(nullable=false)
    private boolean enabled = true;

    @Column(name="created_at", nullable=false)
    private Instant createdAt = Instant.now();

    @Column(length = 16, unique = true)
    private String apiKeyPrefix;

    @Column(length = 60) // BCrypt
    private String apiKeyHash;
    // AppUser.java
    @Column(name = "api_key_sha256", length = 64, unique = true)
    private String apiKeySha256;

    // ejemplo: "CHAT,RAG_WRITE"
    @Column(name = "api_scopes", length = 255)
    private String apiScopes;

    // Permisos de producto/vistas (ej: CHAT,RAG,MONITOR,API_KEYS).
    @Column(name = "granted_permissions", length = 255)
    private String grantedPermissions;

    public String getApiKeySha256() {
        return apiKeySha256;
    }

    public void setApiKeySha256(String apiKeySha256) {
        this.apiKeySha256 = apiKeySha256;
    }

    public String getApiScopes() {
        return apiScopes;
    }

    public void setApiScopes(String apiScopes) {
        this.apiScopes = apiScopes;
    }

    public String getGrantedPermissions() {
        return grantedPermissions;
    }

    public void setGrantedPermissions(String grantedPermissions) {
        this.grantedPermissions = grantedPermissions;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }

    public void setId(long id) {
        this.id = id;
    }
}
