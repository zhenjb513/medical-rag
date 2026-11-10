package org.ragai.service.langchain4j;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.ragai.service.Bm25IndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 侧的混合检索：BM25 关键词召回 + 向量语义召回 → RRF 融合。
 *
 * <p>对应 Spring AI 侧 {@code HybridRetrievalService}，但实现方式不同：
 * Spring AI 侧是手工实现 RRF，LangChain4j 侧直接用官方的
 * {@link ReciprocalRankFuser}。
 *
 * <p>BM25 部分复用项目已有的 {@link Bm25IndexService}（它与框架无关，
 * 只依赖 Lucene 风格的倒排索引），检索结果转成 {@link Content} 后
 * 与向量结果一起交给 RRF 融合。
 */
@Service
public class LcHybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(LcHybridRetrievalService.class);

    private final LcRetrievalService vectorRetrieval;
    private final Bm25IndexService bm25IndexService;

    @Value("${app.rag.hybrid-top-k:8}")
    private int hybridTopK;

    @Value("${app.rag.rrf-k:60}")
    private int rrfK;

    public LcHybridRetrievalService(LcRetrievalService vectorRetrieval,
                                    Bm25IndexService bm25IndexService) {
        this.vectorRetrieval = vectorRetrieval;
        this.bm25IndexService = bm25IndexService;
    }

    /**
     * 混合检索：BM25 + 向量，RRF 融合后取前 N。
     *
     * @param question 用户问题
     * @return 融合后的内容列表
     */
    public List<Content> retrieve(String question) {
        List<List<Content>> candidates = new ArrayList<>();

        // ① 向量召回
        ContentRetriever vectorRetriever = vectorRetrieval.vectorRetriever(hybridTopK);
        List<Content> vectorHits = vectorRetriever.retrieve(Query.from(question));
        candidates.add(vectorHits);

        // ② BM25 关键词召回
        List<Content> bm25Hits = bm25(question, hybridTopK);
        candidates.add(bm25Hits);

        // ③ RRF 融合
        List<Content> fused = ReciprocalRankFuser.fuse(candidates, rrfK);
        List<Content> result = fused.size() > hybridTopK ? fused.subList(0, hybridTopK) : fused;

        log.info("混合检索：query={}, vector={}, bm25={}, fused={}",
                question, vectorHits.size(), bm25Hits.size(), result.size());
        return result;
    }

    private List<Content> bm25(String question, int limit) {
        try {
            List<Bm25IndexService.Hit> hits = bm25IndexService.search(question, limit);
            List<Content> contents = new ArrayList<>();
            for (Bm25IndexService.Hit hit : hits) {
                TextSegment segment = TextSegment.from(hit.content());
                contents.add(Content.from(segment));
            }
            return contents;
        } catch (Exception e) {
            log.warn("BM25 召回失败，退回纯向量：{}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
