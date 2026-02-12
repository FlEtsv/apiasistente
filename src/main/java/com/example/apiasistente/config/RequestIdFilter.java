package com.example.apiasistente.config;

import com.example.apiasistente.util.RequestIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Genera o reusa un X-Request-Id por peticion y lo pone en el MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = resolveRequestId(request);
        response.setHeader(HEADER, requestId);

        try (var ignored = RequestIdHolder.use(requestId)) {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdHolder.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming != null && !incoming.isBlank()) {
            return incoming.trim();
        }
        return RequestIdHolder.generate();
    }
}
