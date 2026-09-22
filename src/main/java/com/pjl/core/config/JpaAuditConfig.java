package com.pjl.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

@Configuration
public class JpaAuditConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        // TODO: Replace with SecurityContext principal once auth is implemented
        return () -> Optional.of("system");
    }
}
