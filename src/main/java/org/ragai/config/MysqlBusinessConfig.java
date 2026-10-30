package org.ragai.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 业务库（MySQL {@code mkbAI}）的 DataSource / JdbcTemplate。
 * <p>
 * <b>为什么必须显式声明：</b>本项目里 {@link PgVectorConfig} 自己注册了
 * {@code pgVectorDataSource}（DataSource）和 {@code pgVectorJdbcTemplate}（JdbcTemplate）。
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 和 {@code JdbcTemplateAutoConfiguration}
 * 都是 {@code @ConditionalOnMissingBean} —— 只要容器里已经有同类型 Bean，它们就整体退避。
 * 结果：<b>{@code spring.datasource} 配的 MySQL 从来没有生成过连接池</b>。
 * 证据（scratch/app_usage.log 启动段）：整个过程只起了一个池 {@code PgVectorHikariPool}，
 * 连接对象是 {@code org.postgresql.jdbc.PgConnection}，没有任何 MySQL/HikariPool-1 的启动日志；
 * 而 {@code PhiAuditService} 建 MySQL 表时收到的是 PostgreSQL 的报错
 * {@code syntax error at or near "AUTO_INCREMENT"}。
 * <p>
 * 因为这个隐藏事实，容器里现在明确分成两个 JdbcTemplate：{@code pgVectorJdbcTemplate}（向量库）
 * 和 {@code businessJdbcTemplate}（MySQL 业务库）。两边的注入点都写死了 {@code @Qualifier}
 * ——没有 @Primary，按类型注入会 {@code NoUniqueBeanDefinitionException}，应用起不来。
 * 不加 {@code @Primary} 是故意的：标了以后任何按类型注入 DataSource 的地方会静默换库，那不是本意。
 */
@Configuration
public class MysqlBusinessConfig {

    /** 绑定 {@code spring.datasource.*}（url / username / password / driver-class-name，口令在 application-local.yml）。 */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public org.springframework.boot.autoconfigure.jdbc.DataSourceProperties businessDataSourceProperties() {
        return new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
    }

    @Bean(name = "businessDataSource", destroyMethod = "close")
    public HikariDataSource businessDataSource(
            @Qualifier("businessDataSourceProperties")
            org.springframework.boot.autoconfigure.jdbc.DataSourceProperties props) {
        HikariDataSource ds = props.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // 日志里靠这个池名确认业务池真的起了（PG 那边叫 PgVectorHikariPool，一眼能分开）
        ds.setPoolName("MysqlBusinessPool");
        return ds;
    }

    @Bean("businessJdbcTemplate")
    public JdbcTemplate businessJdbcTemplate(@Qualifier("businessDataSource") DataSource businessDataSource) {
        return new JdbcTemplate(businessDataSource);
    }
}