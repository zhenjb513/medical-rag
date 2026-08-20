package org.example.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内存向量存储（SimpleVectorStore）——默认启用，开箱即用。
 *
 * 激活条件：
 *   - 容器里没有其它 VectorStore Bean（也就是 PgVectorConfig 因为 enabled=false 没生效）
 *
 * 优点：
 *   ✅ 零依赖：不需要 PostgreSQL、不需要安装 pgvector 扩展、不需要 CREATE EXTENSION
 *   ✅ 立刻生效：DashScope text-embedding-v3 输出 1536 维 → 直接写入内存字典
 *   ✅ 语义相似度检索算法（Cosine）和 QuestionAnswerAdvisor 完全兼容
 *
 * 缺点（仅一个）：
 *   ❌ 非持久化：应用重启后已入库的向量会清空，RagInitializer 会重新扫描 PDF 切分再次向量化
 */
@Configuration
@EnableConfigurationProperties(PgVectorConfigProperties.class)
@ConditionalOnProperty(name = "app.pgvector.enabled", havingValue = "false", matchIfMissing = true)
public class SimpleVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        System.out.println();
        System.out.println("[SimpleVectorStoreConfig] ✅ 使用内存向量库 SimpleVectorStore（默认）");
        System.out.println("                          - 无需安装 pgvector 扩展 / PostgreSQL 版本无关");
        System.out.println("                          - 语义相似度检索：COSINE");
        System.out.println("                          - 注意：应用重启后向量会清空，RagInitializer 会重新向量化 PDF");
        System.out.println("                          - 未来若启用持久化 PgVector：在 application.yml 设置 app.pgvector.enabled=true 即可");
        System.out.println();
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}