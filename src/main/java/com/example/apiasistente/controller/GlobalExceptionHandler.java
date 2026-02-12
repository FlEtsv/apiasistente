package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ApiError;
import com.example.apiasistente.util.RequestIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, "La peticion no es valida", details, req, ex, false);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "No tienes permisos para esta operacion", List.of(), req, ex, false);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        return build(resolved, ex.getReason(), List.of(), req, ex, resolved.is5xxServerError());
    }

    @ExceptionHandler({HttpClientErrorException.class, HttpServerErrorException.class})
    public ResponseEntity<ApiError> handleHttpClient(HttpStatusCodeException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        HttpStatus resolved = status == null ? HttpStatus.BAD_GATEWAY : status;
        String message = ex.getResponseBodyAsString();
        return build(resolved, message == null || message.isBlank() ? ex.getMessage() : message, List.of(), req, ex, resolved.is5xxServerError());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        Throwable cause = ex.getCause();
        if (cause instanceof HttpStatusCodeException hse) {
            return handleHttpClient(hse, req);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), List.of(), req, ex, true);
    }

    @ExceptionHandler({NoSuchElementException.class})
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), List.of(), req, ex, false);
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of(), req, ex, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno procesando la peticion", List.of(), req, ex, true);
    }

    private ResponseEntity<ApiError> build(HttpStatus status,
                                           String message,
                                           List<String> details,
                                           HttpServletRequest req,
                                           Exception ex,
                                           boolean logStack) {
        String errorId = RequestIdHolder.ensure();
        String path = req != null ? req.getRequestURI() : "";
        String method = req != null ? req.getMethod() : "";
        String query = req != null ? req.getQueryString() : "";
        String handler = resolveHandler(req);

        if (logStack) {
            log.error("errorId={} status={} method={} path={} query={} handler={} msg={}",
                    errorId, status.value(), method, path, query, handler, message, ex);
        } else {
            log.warn("errorId={} status={} method={} path={} query={} handler={} msg={}",
                    errorId, status.value(), method, path, query, handler, message);
        }

        ApiError body = new ApiError(
                errorId,
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                Instant.now(),
                details == null ? Collections.emptyList() : details
        );
        return ResponseEntity.status(status).body(body);
    }

    private String resolveHandler(HttpServletRequest req) {
        if (req == null) return "";
        Object handler = req.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod hm) {
            return hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        }
        return handler == null ? "" : handler.toString();
    }

    private String formatFieldError(FieldError err) {
        String code = err.getCode() == null ? "" : err.getCode();
        return err.getField() + ": " + err.getDefaultMessage() + (code.isEmpty() ? "" : " (" + code + ")");
    }
}
