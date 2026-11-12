package org.ragai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PgVector 数据源配置，仅当 app.pgvector.enabled=true 时激活。
 *
 * <p><b>框架迁移说明</b>：本分支（langchain4j-exp）已剥离 Spring AI。
 * 原 {@code vectorStore}（Spring AI 的 {@code PgVectorStore}）Bean 已删除，
 * 向量库改由 LangChain4j 的
 * {@link org.ragai.service.PgVectorStoreService} 接管。
 *
 * <p>但 {@code pgVectorDataSource} 与 {@code pgVectorJdbcTemplate} 必须保留：
 * BM25 索引、PHI 审计、库内分布统计等框架无关的功能仍在直连这张表，
 * 它们不依赖向量库框架，只依赖这个数据源。
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
        // 防中文乱码：JDBC 驱动按 UTF-8 收发字符串，且每次取连接后强制 SET client_encoding = UTF8
        cfg.addDataSourceProperty("characterEncoding", "utf-8");
        cfg.addDataSourceProperty("useUnicode", "true");
        cfg.setConnectionInitSql("SET client_encoding = 'UTF8'");
        return new HikariDataSource(cfg);
    }

    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}
