package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ServerStatsDto;
import com.example.apiasistente.service.MonitorService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Controller
public class MonitorController {

    @GetMapping("/monitor")
    public String monitorPage() {
        return "monitor";
    }
}

@RestController
@RequestMapping("/api/monitor")
class MonitorApiController {

    private final MonitorService monitorService;

    public MonitorApiController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitorService.snapshot();
    }
}
