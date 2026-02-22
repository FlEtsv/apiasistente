package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.MonitoringAlertDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Service
public class MonitoringAlertStore {

    private final Deque<MonitoringAlertDto> events = new ArrayDeque<>();
    private final int maxEvents;

    public MonitoringAlertStore(@Value("${monitoring.alerts.max-events:200}") int maxEvents) {
        this.maxEvents = Math.max(1, maxEvents);
    }

    public void record(MonitoringAlertDto event) {
        if (event == null) return;
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > maxEvents) {
                events.removeLast();
            }
        }
    }

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
