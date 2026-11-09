package org.ragai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量化（text-embedding-v3）和重排（gte-rerank-v2）的 token 用量日志。
 * <p>
 * 挂在 HTTP 层而不是 service 里的原因：查询向量化发生在 PgVectorStore.similaritySearch 内部，
 * 应用代码拿不到它的 EmbeddingResponse；想拿到就得自己包一层 EmbeddingModel bean，
 * 而 embed(String)/embed(List&lt;String&gt;)/embedForResponse 这一串默认方法要重写才对，
 * 只要 EmbeddingOptions 传错，实际调用的向量模型就可能和建库时不是同一个，501 条向量全部作废。
 * 拦在 RestClient 上只读响应，不改任何请求，也不会碰坏调用方解析。
 * <p>
 * 数字来源是响应 JSON 的 usage 字段，和百炼后台按模型出的计费用的是同一个字段，
 * 所以这里记的能直接和后台明细对上；usage 缺失时原样打印，不猜。
 */
@Configuration
public class ModelUsageLogConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AtomicLong EMBED_CALLS = new AtomicLong();
    private static final AtomicLong EMBED_TOKENS = new AtomicLong();
    private static final AtomicLong RERANK_CALLS = new AtomicLong();
    private static final AtomicLong RERANK_TOKENS = new AtomicLong();

    /** 累计值，给长任务收尾时对总数用；重启清零。 */
    public static String totals() {
        return "向量化 次数=" + EMBED_CALLS.get() + " tokens=" + EMBED_TOKENS.get()
                + "  重排 次数=" + RERANK_CALLS.get() + " tokens=" + RERANK_TOKENS.get();
    }

    @Bean
    public RestClientCustomizer modelUsageLogCustomizer() {
        return builder -> builder.requestInterceptor(new UsageInterceptor());
    }

    static final class UsageInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            String kind = kindOf(request.getURI().getPath());
            ClientHttpResponse response = execution.execute(request, body);
            if (kind == null) {
                // 和计费无关的请求原样放行，一个字节都不动
                return response;
            }
            byte[] raw;
            try {
                raw = response.getBody().readAllBytes();
            } catch (IOException e) {
                log(kind, "http=" + safeStatus(response) + "  读取响应失败："
                        + e.getClass().getSimpleName() + " " + e.getMessage(), 0L);
                throw e;
            }
            try {
                logResponse(kind, body, response, raw);
            } catch (RuntimeException e) {
                // 日志本身不能把检索链路搞挂
                log(kind, "http=" + safeStatus(response) + "  用量解析失败："
                        + e.getClass().getSimpleName() + " " + e.getMessage(), 0L);
            }
            return new BufferedResponse(response, raw);
        }

        /** 只认这两类路径；生成（text-generation）由 ChatController 自己打，别重复计。 */
        private static String kindOf(String path) {
            if (path == null) {
                return null;
            }
            String p = path.toLowerCase();
            if (p.contains("/embeddings/")) {
                return "向量化";
            }
            if (p.contains("rerank")) {
                return "重排";
            }
            return null;
        }

        private void logResponse(String kind, byte[] requestBody, ClientHttpResponse response, byte[] raw) {
            StringBuilder info = new StringBuilder();
            info.append("http=").append(safeStatus(response));
            JsonNode reqRoot = safeRead(new String(requestBody, StandardCharsets.UTF_8));
            if (reqRoot != null) {
                info.append("  model=").append(text(reqRoot.path("model")))
                        .append("  输入条数=").append(inputCount(reqRoot));
            }
            JsonNode usageNode = safeRead(new String(raw, StandardCharsets.UTF_8));
            usageNode = usageNode == null ? null : usageNode.path("usage");
            if (usageNode != null && usageNode.isObject()) {
                info.append("  usage{");
                boolean first = true;
                var fields = usageNode.fields();
                while (fields.hasNext()) {
                    var f = fields.next();
                    if (!first) info.append(", ");
                    info.append(f.getKey()).append('=').append(f.getValue().asText());
                    first = false;
                }
                info.append('}');
                log(kind, info.toString(), usageNode.path("total_tokens").asLong(-1));
            } else {
                info.append("  响应里没有 usage 字段");
                log(kind, info.toString(), 0L);
            }
        }

        private static void log(String kind, String detail, long tokens) {
            AtomicLong calls = "向量化".equals(kind) ? EMBED_CALLS : RERANK_CALLS;
            AtomicLong sum = "向量化".equals(kind) ? EMBED_TOKENS : RERANK_TOKENS;
            long n = calls.incrementAndGet();
            long acc = tokens > 0 ? sum.addAndGet(tokens) : sum.get();
            System.out.println("[模型用量] " + kind + "  第" + n + "次  " + detail
                    + "  累计tokens=" + acc + (tokens > 0 ? "" : "  ⚠️本次未计入"));
        }

        /** 请求体里的条数是计费口径的直接因子（重排按文档数算），值得留在日志里。 */
        private static String inputCount(JsonNode reqRoot) {
            JsonNode input = reqRoot.path("input");
            JsonNode texts = input.path("texts");
            if (texts.isArray()) return String.valueOf(texts.size());
            JsonNode docs = input.path("documents");
            if (docs.isArray()) return String.valueOf(docs.size());
            return "?";
        }

        private static JsonNode safeRead(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (IOException e) {
                return null;
            }
        }

        private static String text(JsonNode node) {
            return node == null || node.isMissingNode() || node.isNull() ? "?" : node.asText();
        }

        private static String safeStatus(ClientHttpResponse response) {
            try {
                return response.getStatusCode().value() + " " + response.getStatusText();
            } catch (IOException | RuntimeException e) {
                return "?";
            }
        }
    }

    /** 响应体已经被读走，这里用字节数组重放，保证调用方照常反序列化。 */
    static final class BufferedResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
