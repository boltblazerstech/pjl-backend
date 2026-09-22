package com.pjl.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        // Run a lightweight query to keep the Aiven free-tier database awake
        jdbcTemplate.execute("SELECT 1");
        
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "database", "connected"
        ));
    }
}
