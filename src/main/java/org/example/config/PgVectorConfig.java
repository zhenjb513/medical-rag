package org.example.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector 持久化向量库配置。
 * 仅当配置了 app.pgvector.enabled=true 时才会激活。
 *
 * 默认不启用（你的本机是 PostgreSQL 18，目前没有对应 pgvector 预编译 dll），
 * 会走 SimpleVectorStoreConfig（内存版），RAG 功能仍然可用。
 * 当未来你降级到 PostgreSQL 17（+ 安装 pgvector 扩展）时，
 * 只需在 application.yml 设置 app.pgvector.enabled=true 即可切换。
 */
@Configuration
@EnableConfigurationProperties(PgVectorConfigProperties.class)
@ConditionalOnProperty(name = "app.pgvector.enabled", havingValue = "true")
public class PgVectorConfig {

    @Bean("pgVectorDataSource")
    public DataSource pgVectorDataSource(PgVectorConfigProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("PgVectorHikariPool");
        cfg.setJdbcUrl(props.getJdbcUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        cfg.setDriverClassName(props.getDriverClassName());
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        cfg.setIdleTimeout(60_000);
        System.out.println("[PgVectorConfig] ✅ 已启用 PgVector 持久化向量存储");
        System.out.println("                JDBC URL = " + props.getJdbcUrl()
                + "  user=" + props.getUsername());
        return new HikariDataSource(cfg);
    }

    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }

    @Bean
    public VectorStore vectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            EmbeddingModel embeddingModel,
            PgVectorConfigProperties props) {
        PgVectorStore.PgVectorStoreBuilder builder = PgVectorStore
                .builder(pgVectorJdbcTemplate, embeddingModel)
                .schemaName(props.getSchemaName())
                .vectorTableName(props.getTableName())
                .dimensions(props.getDimensions())
                .indexType(PgVectorStore.PgIndexType.valueOf(props.getIndexType()))
                .distanceType(PgVectorStore.PgDistanceType.valueOf(props.getDistanceType()))
                .idType(PgVectorStore.PgIdType.valueOf(props.getIdType()))
                .initializeSchema(props.isInitializeSchema())
                .vectorTableValidationsEnabled(props.isSchemaValidation());
        return builder.build();
    }
}