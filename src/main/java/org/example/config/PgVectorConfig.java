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
        // -----------------------------------------------------------------
        // ★ 防中文乱码 3 件套：
        //   1) characterEncoding=utf-8：JDBC 驱动按 UTF-8 发字符串
        //   2) connectionInitSql：每次从连接池拿到连接后 **立刻 SET client_encoding = UTF8**
        //      （覆盖 postgresql.conf 里的 GBK/其它默认值，中文 Windows 装 PG 很容易踩这个坑）
        //   3) 强制 UTF-8 写入：PgVector 底层通过 JDBC setString 传 content JSON，驱动层编码确定了就不会乱
        // -----------------------------------------------------------------
        cfg.addDataSourceProperty("characterEncoding", "utf-8");
        cfg.addDataSourceProperty("useUnicode", "true");
        cfg.setConnectionInitSql("SET client_encoding = 'UTF8'");
        System.out.println("[PgVectorConfig] ✅ 已启用 PgVector 持久化向量存储");
        System.out.println("                JDBC URL = " + props.getJdbcUrl()
                + "  user=" + props.getUsername() + "  characterEncoding=utf-8  initSql=SET client_encoding=UTF8");
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