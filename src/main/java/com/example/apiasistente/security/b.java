// ApiKeyAuthFilter.java
package com.example.apiasistente.security;

import com.example.apiasistente.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final AppUserRepository userRepo;
    private final PasswordEncoder encoder;

    public ApiKeyAuthFilter(AppUserRepository userRepo, PasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.encoder = encoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = extractKey(request);
        if (key != null && key.startsWith("ak_")) {
            String prefix = parsePrefix(key);
            if (prefix != null) {
                userRepo.findByApiKeyPrefix(prefix).ifPresent(user -> {
                    if (user.getApiKeyHash() != null && encoder.matches(key, user.getApiKeyHash())) {
                        var auth = new UsernamePasswordAuthenticationToken(
                                user.getUsername(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                });
            }
        }

        chain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest req) {
        String bearer = req.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) return bearer.substring(7).trim();
        String x = req.getHeader("X-API-KEY");
        return (x == null || x.isBlank()) ? null : x.trim();
    }

    private String parsePrefix(String key) {
        // ak_<prefix>_<resto>
        String[] parts = key.split("_", 3);
        if (parts.length < 3) return null;
        return parts[1];
    }
}
