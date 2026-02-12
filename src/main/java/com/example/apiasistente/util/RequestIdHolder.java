package com.example.apiasistente.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Mantiene un requestId por hilo y lo propaga al MDC para logging.
 */
public final class RequestIdHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final String MDC_KEY = "requestId";

    private RequestIdHolder() {
    }

    public static String get() {
        String id = CURRENT.get();
        if (id != null && !id.isBlank()) {
            return id;
        }
        String mdcId = MDC.get(MDC_KEY);
        return (mdcId == null || mdcId.isBlank()) ? null : mdcId;
    }

    public static String ensure() {
        String id = get();
        if (id == null || id.isBlank()) {
            id = generate();
            set(id);
        }
        return id;
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static void set(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            clear();
            return;
        }
        CURRENT.set(requestId);
        MDC.put(MDC_KEY, requestId);
    }

    public static Scope use(String requestId) {
        String previous = get();
        set(requestId);
        return new Scope(previous);
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null || previous.isBlank()) {
                clear();
                return;
            }
            set(previous);
        }
    }
}
