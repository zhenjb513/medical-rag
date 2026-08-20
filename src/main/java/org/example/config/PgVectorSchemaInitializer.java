package org.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PgVector 初始化（只有 app.pgvector.enabled=true 时才运行）。
 * 默认 enabled=false 时，这个 Bean 根本不会被 Spring 创建，不影响启动。
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
        System.out.println();
        System.out.println("[PgVectorSchemaInitializer] 开始初始化 vector 扩展 + vector_store 表 ...");
        try {
            doInit();
            this.ready = true;
            System.out.println("[PgVectorSchemaInitializer] ✅ 初始化成功！PGVector RAG 持久化已启用。");
        } catch (Exception ex) {
            this.ready = false;
            System.err.println();
            System.err.println("⚠️  PGVector 初始化失败：" + ex.getMessage());
            System.err.println("   最常见原因：当前 PostgreSQL 大版本没有对应 pgvector 预编译 dll（当前 PG 18 就是此情况）。");
            System.err.println("   👉 推荐：降级安装 PostgreSQL 17.x（pgvector 生态最成熟），");
            System.err.println("            安装完成后重新跑 D:\\AI\\demoAI 项目里自动安装脚本即可。");
            System.err.println("   👉 临时方案：保持 app.pgvector.enabled=false，自动走内存 SimpleVectorStore，");
            System.err.println("            RAG 功能完整可用，仅重启后向量不持久化。");
        }
    }

    private void doInit() {
        pgVectorJdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        String createTable =
                "CREATE TABLE IF NOT EXISTS " + props.getSchemaName() + "." + props.getTableName() + " (\n" +
                "    id        UUID PRIMARY KEY,\n" +
                "    content   TEXT NOT NULL,\n" +
                "    metadata  JSONB,\n" +
                "    embedding vector(" + props.getDimensions() + ")\n" +
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
                    + " ON " + props.getSchemaName() + "." + props.getTableName()
                    + " USING hnsw (embedding " + op + ")";
        } else if ("IVFFLAT".equalsIgnoreCase(props.getIndexType())) {
            createIndex = "CREATE INDEX IF NOT EXISTS " + idxName
                    + " ON " + props.getSchemaName() + "." + props.getTableName()
                    + " USING ivfflat (embedding " + op + ") WITH (lists = 100)";
        } else {
            createIndex = null;
        }
        if (createIndex != null) pgVectorJdbcTemplate.execute(createIndex);
    }
}