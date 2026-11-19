package org.ragai.service;

import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重排响应解析守门（2026-11-19 补）。
 * <p>
 * 起因：#35 实测重排把答案块埋到 42 名，抓真实原始响应发现——块原文里的引用标记 [1] 含 ']'，
 * 早期用非贪婪正则 {@code "results":\[(.*?)\]} 抠数组会在第一个 ']' 截断，50 个块只解析出 9 个分数，
 * 其余 41 个吃默认满分 1.0，降序后假满分把真答案（真实最高分才 0.638）埋掉。
 * 这里钉住：改用 Jackson 正经解析后，含 ']' 的原文、乱序 index、缺块都不再丢分或假满分。
 * 纯静态解析逻辑，不连库不打模型。
 */
class DashScopeScoringModelTest {

    @SuppressWarnings("unchecked")
    private static List<Double> parse(String json, int size) throws Exception {
        Method m = DashScopeScoringModel.class.getDeclaredMethod("parseScores", String.class, int.class);
        m.setAccessible(true);
        Response<List<Double>> r = (Response<List<Double>>) m.invoke(new DashScopeScoringModel(), json, size);
        return r.content();
    }

    /** 拼一个百炼形状的响应；withDocs=true 时退回含引用标记 [1] 的原文（生产曾因此截断）。 */
    private static String resp(int n, double[] scores, boolean withDocs) {
        StringBuilder sb = new StringBuilder("{\"output\":{\"results\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"relevance_score\":").append(scores[i]);
            if (withDocs) {
                sb.append(",\"document\":{\"text\":\"我国高血压患病人数已达 2.45 亿 [1]。指南[2]指出目标值。\"}");
            }
            sb.append('}');
        }
        sb.append("]},\"usage\":{\"total_tokens\":100}}");
        return sb.toString();
    }

    @Test
    @DisplayName("回归：原文含 [1]/] 的响应（return_documents:true 形状）分数全恢复，不再吃默认满分")
    void bracketsInDocumentsNoLongerTruncate() throws Exception {
        int n = 50;
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) scores[i] = Math.round((0.90 - i * 0.01) * 1000.0) / 1000.0;
        List<Double> got = parse(resp(n, scores, true), n);
        for (int i = 0; i < n; i++) {
            assertEquals(scores[i], got.get(i), 1e-9,
                    "第 " + i + " 块分数被丢/被默认值顶替（旧正则会在第一个 ] 截断）");
        }
        long fakeFull = got.stream().filter(s -> Math.abs(s - 1.0) < 1e-9).count();
        assertEquals(0, fakeFull, "不该再有吃默认满分的块");
    }

    @Test
    @DisplayName("return_documents:false（生产现状）只有 index+score，分数全恢复")
    void noDocumentsParsesCleanly() throws Exception {
        double[] scores = {0.638, 0.445, 0.392, 0.18};
        List<Double> got = parse(resp(4, scores, false), 4);
        for (int i = 0; i < 4; i++) assertEquals(scores[i], got.get(i), 1e-9);
    }

    @Test
    @DisplayName("results 按分数降序、index 乱序时，按 index 映射回请求位置而不是按出现顺序")
    void outOfOrderIndexMapsByIndex() throws Exception {
        // 请求位置 0..3，响应按分降序给出：index2=0.9, index0=0.5, index3=0.3, index1=0.1
        String json = "{\"output\":{\"results\":["
                + "{\"index\":2,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.5},"
                + "{\"index\":3,\"relevance_score\":0.3},"
                + "{\"index\":1,\"relevance_score\":0.1}]}}";
        List<Double> got = parse(json, 4);
        assertEquals(0.5, got.get(0), 1e-9);
        assertEquals(0.1, got.get(1), 1e-9);
        assertEquals(0.9, got.get(2), 1e-9);
        assertEquals(0.3, got.get(3), 1e-9);
    }

    @Test
    @DisplayName("缺返回的序号默认 0.0 沉底，绝不顶假满分把真答案埋掉")
    void missingIndexSinksNotPromotes() throws Exception {
        // 只返回 index0 的分，index1/2 没返回
        String json = "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.7}]}}";
        List<Double> got = parse(json, 3);
        assertEquals(0.7, got.get(0), 1e-9);
        assertEquals(0.0, got.get(1), 1e-9, "缺块应沉底");
        assertEquals(0.0, got.get(2), 1e-9, "缺块应沉底");
    }

    @Test
    @DisplayName("没有 results / JSON 损坏时返回等分（保持原顺序），不抛异常")
    void malformedFallsBackToEqualScores() throws Exception {
        List<Double> a = parse("{\"output\":{}}", 3);
        assertEquals(3, a.size());
        assertTrue(a.stream().allMatch(s -> Math.abs(s - a.get(0)) < 1e-9), "应等分");
        List<Double> b = parse("不是合法JSON", 3);
        assertEquals(3, b.size());
        assertTrue(b.stream().allMatch(s -> Math.abs(s - b.get(0)) < 1e-9), "应等分");
    }
}