package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 重排序（gte-rerank-v2）。
 * <p>
 * 向量分和 BM25 分都是「提问和块各自编码完再比」，块里到底有没有回答这个问题，
 * 它们其实看不准；重排是把提问和候选块拼成一对一起送进模型，所以能分辨
 * 「这段讲血压」和「这段给了血压目标值」的区别——这正是首位命中率的瓶颈。
 * <p>
 * <b>框架迁移说明</b>：本分支（langchain4j-exp）已剥离 Spring AI Alibaba。
 * 原 {@code RerankModel}（spring-ai-alibaba）改为 LangChain4j 的
 * {@link ScoringModel}，实现见 {@link DashScopeScoringModel}。
 * 对外 API（{@code rerank(String, List<Document>)}）保持不变，调用方无需改动。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final DashScopeScoringModel scoringModel;

    /** gte-rerank（不带 v2）已下线，自动配置的默认值还是它，必须显式覆盖 */
    @Value("${app.retrieval.rerank-model:gte-rerank-v2}")
    private String model = "gte-rerank-v2";

    public RerankService(DashScopeScoringModel scoringModel) {
        this.scoringModel = scoringModel;
    }

    public boolean isAvailable() {
        return true;
    }

    public String getModelName() {
        return model;
    }

    /**
     * 返回按重排分降序的候选；分数写进 Document 的 metadata "score" 键，
     * 打印的和用于排序的是同一个数。
     */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();

        List<Document> ranked;
        try {
            List<TextSegment> segments = new ArrayList<>();
            for (Document d : candidates) {
                segments.add(TextSegment.from(d.text()));
            }
            Response<List<Double>> response = scoringModel.scoreAll(segments, query);
            List<Double> scores = response.content();

            ranked = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                Document d = candidates.get(i);
                double score = (scores != null && i < scores.size()) ? scores.get(i) : 0.0;
                ranked.add(withScore(d, score));
            }
            ranked.sort(Comparator.comparingDouble(
                    (Document d) -> d.metadata().getDouble("score") == null ? 0.0
                            : d.metadata().getDouble("score")).reversed());
        } catch (RuntimeException e) {
            log.warn("[重排] 调用失败，保持原顺序：{} {}", e.getClass().getSimpleName(), e.getMessage());
            return candidates;
        }

        if (ranked.size() < candidates.size()) {
            // topN 若被服务端截断，没返回的候选补在末尾，不能让重排把召回池变小
            List<Document> returned = new ArrayList<>(ranked);
            for (Document d : candidates) {
                if (!containsId(returned, d.metadata().getString("id"))) returned.add(d);
            }
            ranked = returned;
        }
        return ranked;
    }

    /** 把分数写进副本的 metadata，不改动原对象（原对象可能来自不可变集合）。 */
    private Document withScore(Document d, double score) {
        var metadata = d.metadata() == null
                ? new dev.langchain4j.data.document.Metadata()
                : d.metadata().copy();
        metadata.put("score", score);
        return Document.from(d.text(), metadata);
    }

    private static boolean containsId(List<Document> docs, String id) {
        for (Document d : docs) {
            String docId = d.metadata().getString("id");
            if (id == null ? docId == null : id.equals(docId)) return true;
        }
        return false;
    }
}
