package org.ragai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
// -100 是 Spring Security 过滤链自己的注册顺序（SecurityProperties.DEFAULT_FILTER_ORDER）。
// 原来标 HIGHEST_PRECEDENCE，这个 filter 就跑在安全链之前，那时 SecurityContextHolder 还没被
// SecurityContextHolderFilter 填过，isSessionAuthenticated() 恒为 false——浏览器登录态在这条路上完全不生效，
// 非白名单路径只剩 X-API-Key 一种走法。挪到 -50 之后两者才都认。
@Order(-50)
public class ApiKeyFilter extends OncePerRequestFilter {

    @Value("${app.security.api-keys:}")
    private String apiKeysRaw;

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${app.rate-limit.rpm:60}")
    private int rateLimitRpm;

    private final Map<String, long[]> rateBuckets = new ConcurrentHashMap<>();

    /** 前端页面与登录动作本身：必须精确匹配，否则 {@code /login} 这种前缀会把 {@code /login-x} 一起放过。 */
    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/",
            "/index.html",
            "/app.js",
            "/style.css",
            "/favicon.ico",
            "/error",
            "/login",
            "/logout",
            "/auth/status"
    );

    /** 成树开放的静态资源与文档；{@code /actuator} 留着是因为 Docker healthcheck 和 Prometheus 抓取都走它。 */
    private static final Set<String> PUBLIC_PREFIX = Set.of(
            "/static/",
            "/webjars/",
            "/swagger-ui",
            "/swagger-resources",
            "/v3/api-docs",
            "/actuator"
    );

    /**
     * 零模型调用、只读的溯源口子：公开。前端页面一加载就调 {@code /rag/diag} 探后端状态，
     * 这类路径挡在鉴权后面等于把状态灯和诊断页打死。
     */
    private static final Set<String> PUBLIC_READ_ONLY_EXACT = Set.of(
            "/rag/chunk",
            "/rag/diag",
            "/bm25/locate",
            "/bm25/df",
            "/bm25/search"
    );

    // /evaluation 和 /ingest 从这里拿掉了：前者里 /evaluation/run 一点就是 61 题真金白银的全量，
    // 后者会按传入的服务器路径重灌文档。它们和 /chat、/rag、/bm25 的区别是「会花钱或会写」，不是「只读」。
    // /chat 同样要鉴权（一次调用 = 一次向量化 + 一次重排 + 一次生成），登录态在 @Order(-50) 之后才认得出来。

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // 限流挪到鉴权之前，并且从此对所有路径生效——原来它写在白名单 return 的后面，
        // 等于「越公开的越不限」，而 /evaluation、/ingest 恰恰在被放行的那批里。
        // /actuator 豁免：Docker healthcheck 每 30s 一次、Prometheus 每 15s 抓一次，让机器面去抢业务额度不值。
        if (rateLimitEnabled && !isMonitoringPlane(path) && !isRateAllowed(request)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate Limit Exceeded\",\"message\":\"Too many requests\"}");
            return;
        }

        if (isPublic(path) || isSessionAuthenticated() || isApiKeyValid(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing valid session or API key\"}");
    }

    private boolean isSessionAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
    }

    private boolean isMonitoringPlane(String path) {
        return path.startsWith("/actuator");
    }

    private boolean isPublic(String path) {
        return PUBLIC_EXACT.contains(path)
                || PUBLIC_READ_ONLY_EXACT.contains(path)
                || PUBLIC_PREFIX.stream().anyMatch(path::startsWith);
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