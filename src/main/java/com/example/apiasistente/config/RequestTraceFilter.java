package com.example.apiasistente.config;

import com.example.apiasistente.util.RequestIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Traza cada request con duracion, handler y status.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        long startNs = System.nanoTime();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        String remote = request.getRemoteAddr();

        String user = resolveUser();
        String requestId = RequestIdHolder.get();

        log.debug("req id={} method={} path={} query={} remote={} user={}",
                requestId, method, path, safe(query), remote, user);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            int status = response.getStatus();
            String handler = resolveHandler(request);
            String pattern = resolvePattern(request);

            if (status >= 500) {
                log.error("res id={} status={} ms={} method={} path={} pattern={} handler={}",
                        requestId, status, elapsedMs, method, path, safe(pattern), safe(handler));
            } else if (status >= 400) {
                log.warn("res id={} status={} ms={} method={} path={} pattern={} handler={}",
                        requestId, status, elapsedMs, method, path, safe(pattern), safe(handler));
            } else {
                log.info("res id={} status={} ms={} method={} path={} pattern={} handler={}",
                        requestId, status, elapsedMs, method, path, safe(pattern), safe(handler));
            }
        }
    }

    private String resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "anon";
        }
        return auth.getName();
    }

    private String resolveHandler(HttpServletRequest request) {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod hm) {
            return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        }
        return handler == null ? "" : handler.toString();
    }

    private String resolvePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "" : pattern.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
