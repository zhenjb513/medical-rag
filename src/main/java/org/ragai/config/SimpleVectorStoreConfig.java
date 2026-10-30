package org.ragai.config;

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
 * 激活条件：容器中没有其它 VectorStore Bean（即 PgVectorConfig 因 enabled=false 未生效）。
 * 特点：零外部依赖，无需安装 pgvector 扩展；但非持久化，重启后向量清空，由 RagInitializer 重新入库。
 */
@Configuration
@EnableConfigurationProperties(PgVectorConfigProperties.class)
@ConditionalOnProperty(name = "app.pgvector.enabled", havingValue = "false", matchIfMissing = true)
public class SimpleVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
