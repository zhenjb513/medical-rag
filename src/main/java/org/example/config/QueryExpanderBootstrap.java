package org.example.config;

import org.example.service.QueryExpander;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.retrieval.query-expander")
public class QueryExpanderBootstrap implements ApplicationRunner {

    private List<Map<String, Object>> rules;

    public void setRules(List<Map<String, Object>> rules) {
        this.rules = rules;
    }

    public List<Map<String, Object>> getRules() {
        return rules;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rules != null && !rules.isEmpty()) {
            QueryExpander.configure(rules);
        }
    }
}