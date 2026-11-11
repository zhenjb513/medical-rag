package org.ragai.service.langchain4j;

import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * LangChain4j 侧的向量存储服务。
 *
 * <p>定位：本分支（langchain4j-exp）用 LangChain4j 重写 RAG 链路，与 spring-ai-dashscope
 * 分支并行演进，master 负责融合。这里用 {@link PgVectorEmbeddingStore} 作为
 * {@code EmbeddingStore<TextSegment>} 的实现，对接同一套 PgVector 库。
 *
 * <p>与 Spring AI 侧的 PgVectorStore 是两套独立实现，但落到同一张表，
 * 因此两边写入的数据可以互相检索到——这正是 master 融合时能对齐的基础。
 */
@Service
public class LcEmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(LcEmbeddingStoreService.class);

    @Value("${app.pgvector.jdbc-url}")
    private String jdbcUrl;

    @Value("${app.pgvector.username}")
    private String username;

    @Value("${app.pgvector.password}")
    private String password;

    @Value("${app.pgvector.dimensions:1024}")
    private int dimensions;

    @Value("${app.pgvector.lc-table-name:lc_document_embeddings}")
    private String table;

    @Value("${app.pgvector.lc-create-table:true}")
    private boolean createTable;

    private PgVectorEmbeddingStore embeddingStore;

    @PostConstruct
    void init() {
        // 从 jdbc:postgresql://host:port/dbname 里取出 host/port/dbname，
        // PgVectorEmbeddingStore 的 builder 需要分开传。
        String rest = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");
        String hostPort = rest.split("/", 2)[0];
        String dbName = rest.contains("/") ? rest.split("/", 2)[1].split("\\?")[0] : "postgres";
        String[] hp = hostPort.split(":", 2);
        String host = hp[0];
        int port = hp.length > 1 ? Integer.parseInt(hp[1]) : 5432;

        this.embeddingStore = PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(dbName)
                .user(username)
                .password(password)
                .table(table)
                .dimension(dimensions)
                .createTable(createTable)
                .build();

        log.info("LangChain4j PgVectorEmbeddingStore 就绪：table={}, dimension={}", table, dimensions);
    }

    /**
     * 把 {@link PgVectorEmbeddingStore} 注册为 Spring Bean，供 {@code RagController} 等直接注入。
     * <p>不能只在 {@code init()} 里 new 出来藏字段里——那样只有本服务能用，
     * 别的 Bean 注入会报 "No qualifying bean"。
     */
    @Bean
    public PgVectorEmbeddingStore pgVectorEmbeddingStore() {
        return embeddingStore;
    }

    public PgVectorEmbeddingStore store() {
        return embeddingStore;
    }
}
