package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Buffer circular en memoria para eventos de alerta de monitor.
 * <p>
 * Mantiene solo los eventos recientes para consultas operativas de baja latencia.
 */
@Service
public class MonitoringAlertStore {

    private final Deque<MonitoringAlertDto> events = new ArrayDeque<>();
    private final int maxEvents;

    public MonitoringAlertStore(@Value("${monitoring.alerts.max-events:200}") int maxEvents) {
        this.maxEvents = Math.max(1, maxEvents);
    }

    /**
     * Registra un nuevo evento al inicio del buffer.
     *
     * @param event evento de alerta o recuperacion
     */
    public void record(MonitoringAlertDto event) {
        if (event == null) return;
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > maxEvents) {
                events.removeLast();
            }
        }
    }

    /**
     * Devuelve eventos recientes aplicando filtro temporal y limite.
     *
     * @param since fecha minima opcional
     * @param limit maximo de eventos a devolver
     * @return lista en orden descendente (mas reciente primero)
     */
    public List<MonitoringAlertDto> recent(Instant since, int limit) {
        int safeLimit = limit <= 0 ? maxEvents : Math.min(limit, maxEvents);
        List<MonitoringAlertDto> out = new ArrayList<>(safeLimit);
        synchronized (events) {
            for (MonitoringAlertDto e : events) {
                if (since != null && e.timestamp() != null && e.timestamp().isBefore(since)) {
                    continue;
                }
                out.add(e);
                if (out.size() >= safeLimit) break;
            }
        }
        return out;
    }
}


