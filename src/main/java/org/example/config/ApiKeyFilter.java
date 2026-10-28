package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${app.security.api-keys:}")
    private String apiKeysRaw;

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${app.rate-limit.rpm:60}")
    private int rateLimitRpm;

    private final Map<String, long[]> rateBuckets = new ConcurrentHashMap<>();

    private static final Set<String> WHITELIST = Set.of(
            "/actuator",
            "/static",
            "/error",
            "/favicon.ico",
            "/login",
            "/logout",
            "/auth"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        if (isWhitelisted(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isSessionAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isApiKeyValid(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing valid session or API key\"}");
            return;
        }

        if (rateLimitEnabled && !isRateAllowed(request)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate Limit Exceeded\",\"message\":\"Too many requests\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSessionAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }

    private boolean isApiKeyValid(HttpServletRequest request) {
        if (apiKeysRaw == null || apiKeysRaw.isBlank()) {
            return false;
        }
        Set<String> allowed = Set.of(apiKeysRaw.split(","));
        String headerKey = request.getHeader("X-API-Key");
        String queryKey = request.getParameter("apiKey");
        return (headerKey != null && allowed.contains(headerKey))
                || (queryKey != null && allowed.contains(queryKey));
    }

    private boolean isRateAllowed(HttpServletRequest request) {
        String clientId = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        long windowMs = Duration.ofMinutes(1).toMillis();

        long[] bucket = rateBuckets.computeIfAbsent(clientId, k -> new long[]{now, 0});
        synchronized (bucket) {
            if (now - bucket[0] > windowMs) {
                bucket[0] = now;
                bucket[1] = 0;
            }
            bucket[1]++;
            if (bucket[1] > rateLimitRpm) {
                return false;
            }
            return true;
        }
    }
}