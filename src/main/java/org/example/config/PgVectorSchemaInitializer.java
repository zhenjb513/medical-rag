package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PgVector 初始化（仅 app.pgvector.enabled=true 时运行）。
 * 负责创建 vector 扩展与 vector_store 表，并处理维度不匹配时的自动重建。
 */
@Component
@ConditionalOnProperty(name = "app.pgvector.enabled", havingValue = "true")
public class PgVectorSchemaInitializer {

    private final JdbcTemplate pgVectorJdbcTemplate;
    private final PgVectorConfigProperties props;

    private volatile boolean ready = false;

    @Autowired
    public PgVectorSchemaInitializer(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            PgVectorConfigProperties props) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.props = props;
    }

    public boolean isReady() { return ready; }

    @PostConstruct
    public void init() {
        try {
            doInit();
            this.ready = true;
            System.out.println("[PgVectorSchemaInitializer] ✅ 初始化成功，PGVector RAG 持久化已启用。");
        } catch (Exception ex) {
            this.ready = false;
            System.err.println("[PgVectorSchemaInitializer] ⚠️ PGVector 初始化失败：" + ex.getMessage());
            System.err.println("    最常见原因：当前 PostgreSQL 没有对应 pgvector 预编译 dll。");
            System.err.println("    建议：安装 pgvector 扩展或将 app.pgvector.enabled=false 走内存 SimpleVectorStore。");
        }
    }

    private void doInit() {
        pgVectorJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");

        final String fullTableName = props.getSchemaName() + "." + props.getTableName();

        // 配置 app.pgvector.reset-table=true 时清空重建 vector_store 表
        if (props.isResetTable()) {
            pgVectorJdbcTemplate.execute("DROP TABLE IF EXISTS " + fullTableName);
            System.out.println("[PgVectorSchemaInitializer] 🗑️ 已按 app.pgvector.reset-table=true 清空并重建 vector_store 表");
        }

        final int expectedDim = props.getDimensions();

        // 检测已存在表的 embedding 向量列维度，与配置不一致时自动 DROP 重建
        int actualDim = -1;
        try {
            String dimensionSql =
                    "SELECT atttypmod " +
                    "FROM   pg_attribute a " +
                    "JOIN   pg_class     c ON c.oid = a.attrelid " +
                    "JOIN   pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE  n.nspname = ? " +
                    "AND    c.relname = ? " +
                    "AND    a.attname = 'embedding'";
            Integer mod = pgVectorJdbcTemplate.queryForObject(dimensionSql, Integer.class,
                    props.getSchemaName(), props.getTableName());
            if (mod != null && mod > 0) actualDim = mod;
        } catch (Exception ignore) { /* 表或列不存在，保持 -1 走正常 CREATE */ }

        if (actualDim > 0 && actualDim != expectedDim) {
            pgVectorJdbcTemplate.execute("DROP TABLE IF EXISTS " + fullTableName);
            actualDim = -1;
        }

        String createTable =
                "CREATE TABLE IF NOT EXISTS " + fullTableName + " (\n" +
                "    id        UUID PRIMARY KEY,\n" +
                "    content   TEXT NOT NULL,\n" +
                "    metadata  JSONB,\n" +
                "    embedding vector(" + expectedDim + ")\n" +
                ")";
        pgVectorJdbcTemplate.execute(createTable);

        String op = switch (props.getDistanceType()) {
            case "COSINE_DISTANCE" -> "vector_cosine_ops";
            case "EUCLIDEAN_DISTANCE" -> "vector_l2_ops";
            case "NEGATIVE_INNER_PRODUCT" -> "vector_ip_ops";
            default -> "vector_cosine_ops";
        };
        String idxName = props.getTableName() + "_embedding_idx";
        String createIndex;
        if ("HNSW".equalsIgnoreCase(props.getIndexType())) {
            createIndex = "CREATE INDEX IF NOT EXISTS " + idxName
                    + " ON " + fullTableName
                    + " USING hnsw (embedding " + op + ")";
        } else if ("IVFFLAT".equalsIgnoreCase(props.getIndexType())) {
            createIndex = "CREATE INDEX IF NOT EXISTS " + idxName
                    + " ON " + fullTableName
                    + " USING ivfflat (embedding " + op + ") WITH (lists = 100)";
        } else {
            createIndex = null;
        }
        if (createIndex != null) pgVectorJdbcTemplate.execute(createIndex);
    }
}
