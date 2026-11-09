package org.ragai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        List<UserDef> users,
        String apiKeys,
        boolean formLoginEnabled
) {
    public record UserDef(String username, String password, List<String> roles) {}
}