package org.ragai.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口闸门的两条规矩：会花钱或会写库的一律要凭据，只读免费的公开；限流对所有非监控路径生效。
 * <p>
 * 钉住这几个形状的原因是历史上两处都反着来：{@code /evaluation} 和 {@code /ingest} 整片在前缀白名单里
 * （{@code startsWith}），而限流写在白名单 return 之后——等于「越公开的越不限」，
 * 未登录点一次 {@code /evaluation/run} 就是 61 题真金白银的全量。
 */
class ApiKeyFilterTest {

    private ApiKeyFilter filter(String apiKeys, boolean rateLimitEnabled, int rpm) {
        ApiKeyFilter f = new ApiKeyFilter();
        ReflectionTestUtils.setField(f, "apiKeysRaw", apiKeys);
        ReflectionTestUtils.setField(f, "rateLimitEnabled", rateLimitEnabled);
        ReflectionTestUtils.setField(f, "rateLimitRpm", rpm);
        return f;
    }

    /** @return true 表示放行到下游链，false 表示被这个 filter 拦下 */
    private boolean passThrough(ApiKeyFilter f, String path, String apiKeyHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        if (apiKeyHeader != null) {
            req.addHeader("X-API-Key", apiKeyHeader);
        }
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> chained.set(true);
        f.doFilter(req, new MockHttpServletResponse(), chain);
        return chained.get();
    }

    private int statusCode(ApiKeyFilter f, String path) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, (rq, rs) -> {
        });
        return res.getStatus();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("只读免费的溯源口子公开：不配 key 也能核对引用")
    void readOnlyEndpointsArePublic() throws Exception {
        ApiKeyFilter f = filter("", true, 60);
        for (String path : List.of("/rag/diag", "/rag/chunk", "/bm25/locate", "/bm25/df", "/bm25/search")) {
            assertTrue(passThrough(f, path, null), path + " 应当免凭据放行");
        }
    }

    @Test
    @DisplayName("登录页和前端裸文件必须免凭据，否则页面自己就进不去")
    void pageAssetsArePublic() throws Exception {
        ApiKeyFilter f = filter("", true, 60);
        // 老版白名单是 startsWith("/login")，顺手把 /app.js 这类也盖住过；改成精确匹配后必须逐条列出来，
        // 漏一条就是浏览器拿 401、页面退化成没样式的裸 HTML 且所有按钮失效。
        for (String path : List.of("/", "/index.html", "/login", "/logout", "/app.js", "/style.css", "/auth/status")) {
            assertTrue(passThrough(f, path, null), path + " 应当免凭据放行");
        }
    }

    @Test
    @DisplayName("会花钱或会写的口子一律要凭据")
    void paidAndWriteEndpointsNeedAuth() throws Exception {
        ApiKeyFilter f = filter("", true, 60);
        for (String path : List.of("/evaluation/run", "/evaluation/summary", "/evaluation/retrieve",
                "/ingest/pdf", "/bm25/rebuild", "/chat", "/chat/stream")) {
            assertEquals(401, statusCode(f, path), path + " 未带凭据必须 401");
        }
    }

    @Test
    @DisplayName("X-API-Key 放行；api-keys 没配时任何 key 都不放行")
    void apiKeyGrantsAccessOnlyWhenConfigured() throws Exception {
        assertTrue(passThrough(filter("k1,k2", true, 60), "/evaluation/run", "k2"));
        assertFalse(passThrough(filter("k1,k2", true, 60), "/evaluation/run", "nope"));
        assertFalse(passThrough(filter("", true, 60), "/evaluation/run", "k1"), "没配 api-keys 等于这条路关着");
    }

    @Test
    @DisplayName("浏览器登录态算凭据（filter 挪到安全链之后才认得出来）")
    void sessionGrantsAccess() throws Exception {
        ApiKeyFilter f = filter("", true, 60);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertTrue(passThrough(f, "/evaluation/run", null), "已登录会话应当放行");

        // anonymousUser 不算
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", "n/a", List.of()));
        assertEquals(401, statusCode(f, "/evaluation/run"));
    }

    @Test
    @DisplayName("公开清单走精确匹配，不靠前缀吞")
    void exactMatchNotPrefixMatch() throws Exception {
        ApiKeyFilter f = filter("", true, 60);
        assertTrue(passThrough(f, "/login", null));
        assertEquals(401, statusCode(f, "/login-as-admin"), "/login 前缀不该把邻居路径一起放行");
        assertEquals(401, statusCode(f, "/evaluation"));
    }

    @Test
    @DisplayName("限流对公开路径也生效，只豁免监控面")
    void rateLimitAppliesToPublicPathsButNotMonitoringPlane() throws Exception {
        ApiKeyFilter f = filter("", true, 2);
        // 当年限流写在白名单 return 之后，公开路径等于不限流
        assertEquals(200, statusCode(f, "/rag/diag"));
        assertEquals(200, statusCode(f, "/rag/diag"));
        assertEquals(429, statusCode(f, "/rag/diag"), "超过 rpm 应当限流");

        // Docker healthcheck 每 30s、Prometheus 每 15s，机器面不占业务额度
        for (int i = 0; i < 5; i++) {
            assertEquals(200, statusCode(f, "/actuator/prometheus"), "监控面第 " + (i + 1) + " 次不该被限流");
        }
    }

    @Test
    @DisplayName("关掉限流开关时一个都不拦")
    void rateLimitSwitchOff() throws Exception {
        ApiKeyFilter f = filter("", false, 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, statusCode(f, "/rag/diag"));
        }
    }
}
