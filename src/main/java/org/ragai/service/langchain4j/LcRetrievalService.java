package org.ragai.service.langchain4j;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.List;

/**
 * LangChain4j 侧的向量检索服务。
 *
 * <p>对应 Spring AI 侧 {@code HybridRetrievalService} 的向量分支，
 * 但只负责纯向量召回；混合检索（BM25 + 向量 + RRF）在
 * {@link LcHybridRetrievalService} 中实现。
 */
@Service
public class LcRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(LcRetrievalService.class);

    private final PgVectorEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${app.rag.top-k:6}")
    private int topK;

    @Value("${app.rag.min-score:0.0}")
    private double minScore;

    public LcRetrievalService(LcEmbeddingStoreService storeService, EmbeddingModel embeddingModel) {
        this.embeddingStore = storeService.store();
        this.embeddingModel = embeddingModel;
    }

    /**
     * 纯向量检索。
     *
     * @param question 用户问题
     * @return 召回的内容列表（按相似度降序）
     */
    public List<Content> retrieve(String question) {
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(topK)
                .minScore(minScore)
                .displayName("lc-vector")
                .build();

        List<Content> contents = retriever.retrieve(Query.from(question));
        log.info("向量检索：query={}, hits={}", question, contents.size());
        return contents;
    }

    /**
     * 取底层检索器，供混合检索复用。
     */
    public EmbeddingStoreContentRetriever vectorRetriever(int maxResults) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .displayName("lc-vector")
                .build();
    }
}
