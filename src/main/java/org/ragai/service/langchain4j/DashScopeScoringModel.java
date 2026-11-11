package org.ragai.service.langchain4j;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 DashScope gte-rerank-v2 的 {@link ScoringModel} 实现。
 *
 * <p>LangChain4j 官方没有 DashScope 的 ScoringModel 集成（只有 Cohere/Jina/ONYX），
 * 所以这里直接用 HTTP 调百炼的 rerank 接口，包成 LangChain4j 的
 * {@link ScoringModel} 抽象，供 {@code ReRankingContentAggregator} 使用。
 *
 * <p>接口：POST https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
 * 请求体：{"model":"gte-rerank-v2","input":{"query":..., "documents":[...]},"parameters":{"return_documents":true}}
 * 响应体：{"output":{"results":[{"index":0,"relevance_score":0.97}, ...]}}
 */
@Component
public class DashScopeScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeScoringModel.class);

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private static final Pattern RESULTS_PATTERN =
            Pattern.compile("\"results\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern INDEX_PATTERN = Pattern.compile("\"index\"\\s*:\\s*(\\d+)");
    private static final Pattern SCORE_PATTERN = Pattern.compile("\"relevance_score\"\\s*:\\s*([0-9.]+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // LangChain4j 社区 DashScope 模块的配置键，与 application-local.yml 一致
    @Value("${langchain4j.community.dashscope.chat-model.api-key:}")
    private String apiKey;

    @Value("${app.retrieval.rerank-model:gte-rerank-v2}")
    private String model;

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[重排] 未配置 DASHSCOPE_API_KEY，返回等分（保持原顺序）");
            List<Double> equal = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                equal.add(1.0);
            }
            return Response.from(equal, new TokenUsage(0, 0));
        }

        // 组装 documents 数组
        StringBuilder docs = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                docs.append(',');
            }
            docs.append("\"").append(escapeJson(segments.get(i).text())).append("\"");
        }

        String body = "{\"model\":\"" + model + "\","
                + "\"input\":{\"query\":\"" + escapeJson(query) + "\",\"documents\":[" + docs + "]},"
                + "\"parameters\":{\"return_documents\":true,\"top_n\":" + segments.size() + "}}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[重排] DashScope 返回 {}：{}", response.statusCode(), truncate(response.body()));
                return equalScores(segments.size());
            }

            return parseScores(response.body(), segments.size());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[重排] 调用失败，返回等分：{}", e.getMessage());
            return equalScores(segments.size());
        }
    }

    private Response<List<Double>> parseScores(String json, int size) {
        double[] scores = equalScoresArray(size);

        Matcher resultsMatcher = RESULTS_PATTERN.matcher(json);
        if (!resultsMatcher.find()) {
            log.warn("[重排] 响应里找不到 results 字段，返回等分");
            return equalScores(size);
        }
        String resultsBlock = resultsMatcher.group(1);

        // results 是对象数组，逐个匹配 index 和 relevance_score
        Matcher indexMatcher = INDEX_PATTERN.matcher(resultsBlock);
        Matcher scoreMatcher = SCORE_PATTERN.matcher(resultsBlock);
        while (indexMatcher.find() && scoreMatcher.find()) {
            int index = Integer.parseInt(indexMatcher.group(1));
            double score = Double.parseDouble(scoreMatcher.group(1));
            if (index >= 0 && index < size) {
                scores[index] = score;
            }
        }

        List<Double> list = new ArrayList<>();
        for (double s : scores) {
            list.add(s);
        }
        return Response.from(list, new TokenUsage(0, 0));
    }

    private Response<List<Double>> equalScores(int size) {
        List<Double> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(1.0);
        }
        return Response.from(list, new TokenUsage(0, 0));
    }

    private double[] equalScoresArray(int size) {
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) {
            arr[i] = 1.0;
        }
        return arr;
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
