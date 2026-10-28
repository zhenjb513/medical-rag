package org.example.config;

import org.example.service.QueryExpander;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryExpanderBootstrap implements ApplicationRunner {

    @Value("${app.retrieval.query-expander.rules:}")
    private List<Map<String, Object>> rulesConfig;

    @Override
    public void run(ApplicationArguments args) {
        QueryExpander.configure(rulesConfig);
    }
}