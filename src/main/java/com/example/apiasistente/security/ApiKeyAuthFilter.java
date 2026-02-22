package com.example.apiasistente.security;

import com.example.apiasistente.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_API_KEY_ID = "ext.apiKeyId";
    public static final String ATTR_SPECIAL_KEY = "ext.specialModeEnabled";
    public static final String ATTR_API_KEY_LABEL = "ext.apiKeyLabel";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String token = req.getHeader("X-API-KEY");

        if (token == null || token.isBlank()) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring("Bearer ".length()).trim();
            }
        }

        if (token != null && !token.isBlank() && SecurityContextHolder.getContext().getAuthentication() == null) {
            ApiKeyService.ApiKeyAuthResult authResult = apiKeyService.authenticate(token);
            if (authResult != null && authResult.username() != null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        authResult.username(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_EXT"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                req.setAttribute(ATTR_API_KEY_ID, authResult.apiKeyId());
                req.setAttribute(ATTR_SPECIAL_KEY, authResult.specialModeEnabled());
                req.setAttribute(ATTR_API_KEY_LABEL, authResult.label());
            }
        }

        chain.doFilter(req, res);
    }
}
