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

        final String fullTableName = props.getSchemaName() + "." + props.getTableName();

        // --- 启动时诊断编码状态（中文乱码 99% 出在这里）---
        String serverEnc = "?", clientEnc = "?", dbEnc = "?";
        try {
            serverEnc = pgVectorJdbcTemplate.queryForObject("SHOW server_encoding", String.class);
            clientEnc = pgVectorJdbcTemplate.queryForObject("SHOW client_encoding", String.class);
            dbEnc     = pgVectorJdbcTemplate.queryForObject(
                    "SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database()",
                    String.class);
            System.out.println("  📝 Postgres 编码诊断：server=" + serverEnc
                    + "  client=" + clientEnc + "  database=" + dbEnc
                    + ("UTF8".equalsIgnoreCase(serverEnc) && "UTF8".equalsIgnoreCase(clientEnc) && "UTF8".equalsIgnoreCase(dbEnc)
                        ? " ✅ 全部 UTF8，中文不会乱码"
                        : " ⚠️ 存在非 UTF8！请继续看下面的数据库重建步骤提示"));
        } catch (Exception ignore) { /* 编码查询失败不影响建表主流程 */ }

        // =========================================================================
        // ★★★ 中文编码终极自检：真枪实弹 INSERT 中文 → 再 SELECT + hex 对比 ★★★
        //   这能彻底区分：
        //      ① PG 服务端编码错（存进去 byte 就错了 → 必须重建数据库）
        //      ② 存的是对的，但 PgAdmin/psql 客户端显示设置错（hex 对比一致就放心了）
        // =========================================================================
        final String TEST_STR = "【中文编码自检】中国心血管病一级预防指南-Ab123!@#-测试特殊字符";
        boolean allUtf8 = "UTF8".equalsIgnoreCase(serverEnc) && "UTF8".equalsIgnoreCase(clientEnc) && "UTF8".equalsIgnoreCase(dbEnc);
        try {
            String javaHex   = bytesToHex(TEST_STR.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String tmpTable  = "tmp_encoding_check_" + System.currentTimeMillis();
            pgVectorJdbcTemplate.execute("CREATE TEMP TABLE " + tmpTable + "(content TEXT)");
            pgVectorJdbcTemplate.update("INSERT INTO " + tmpTable + "(content) VALUES (?)", TEST_STR);
            String dbContent = pgVectorJdbcTemplate.queryForObject("SELECT content FROM " + tmpTable + " LIMIT 1", String.class);
            String dbHex     = pgVectorJdbcTemplate.queryForObject(
                    "SELECT encode(content::bytea, 'hex') FROM " + tmpTable + " LIMIT 1", String.class);
            pgVectorJdbcTemplate.execute("DROP TABLE " + tmpTable);

            boolean roundTripOk = TEST_STR.equals(dbContent) && javaHex.equalsIgnoreCase(dbHex);
            if (roundTripOk) {
                System.out.println("  ✅ 中文写入→读出 往返检查通过（hex 完全一致，JDBC↔PG 编码链路 100% OK）");
                System.out.println("     Java UTF-8 hex = " + javaHex);
                System.out.println("     PG    bytea hex = " + dbHex);
            } else {
                System.err.println("  ❌ 中文往返不一致！说明数据库编码链路上存在转码：");
                System.err.println("     期望内容: " + TEST_STR);
                System.err.println("     实际读出: " + dbContent);
                System.err.println("     Java UTF-8 hex = " + javaHex);
                System.err.println("     PG    bytea hex = " + dbHex);
                if (!allUtf8) {
                    System.err.println(" 👉👉👉 必须用下面 SQL **重新创建 postgres 数据库**（PG 的 database encoding 无法 ALTER 修改）：");
                    System.err.println("      -- 1) 关闭所有连接到 postgres 库的会话，连接到默认的 template1 数据库：");
                    System.err.println("           psql -U postgres -d template1");
                    System.err.println("      -- 2) 踢掉 postgres 库上所有连接：");
                    System.err.println("           SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='postgres' AND pid <> pg_backend_pid();");
                    System.err.println("      -- 3) 删库重建（必须指定 UTF8 + C 排序规则）：");
                    System.err.println("           DROP DATABASE IF EXISTS postgres;");
                    System.err.println("           CREATE DATABASE postgres OWNER postgres ENCODING 'UTF8' LC_COLLATE='C' LC_CTYPE='C' TEMPLATE=template0;");
                } else {
                    System.err.println(" 👉👉👉 检查是否有触发器/RULE 对 text 列做了隐式转换（或 PgJDBC 驱动过老）。");
                }
            }
        } catch (Exception ex) {
            System.err.println("  ⚠️  中文编码自检失败（不影响建表）：" + ex.getMessage());
        }

        // =========================================================================
        // ★★★ 本次修复乱码问题后强制清理旧 vector_store 表（里面存的已经是乱码行）★★★
        //   之后每次重新启动，RagInitializer 都会重新扫描 D:/ai-docs 重新入库，
        //   所以删掉旧表完全安全，还能顺带验证维度匹配。
        //   如需保留旧数据：启动时加 JVM 参数 -Dpgvector.keep-old-table=true
        // =========================================================================
        boolean keepOld = Boolean.getBoolean("pgvector.keep-old-table");
        if (!keepOld) {
            try {
                pgVectorJdbcTemplate.execute("DROP TABLE IF EXISTS " + fullTableName);
                System.out.println("  🗑️  已清理旧的 vector_store 表（-Dpgvector.keep-old-table=true 可跳过），将用全新编码重建");
            } catch (Exception ignore) { /* 删表失败也继续尝试 CREATE */ }
        }

        final int expectedDim = props.getDimensions();

        // ---------------------------------------------------------------------
        // ★ 关键：检测已存在表的向量列维度，若和配置不一致 → 自动 DROP 重建
        //   (CREATE TABLE IF NOT EXISTS 永远不会改已存在的列，1536 vs 1024 这种不匹配
        //    会导致后续写入时报 "expected 1536 dimensions, not 1024" 错误)
        // ---------------------------------------------------------------------
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
        } catch (Exception ignore) { /* 表不存在 / 列不存在：actualDim 保持 -1，走正常 CREATE */ }

        if (actualDim > 0 && actualDim != expectedDim) {
            System.out.println("  ⚠️  检测到旧表 vector_store.embedding 维度不匹配：期望 " + expectedDim +
                    " 维，实际 " + actualDim + " 维。已自动 DROP 重建（旧向量数据会清空，重启后会重新入库）。");
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

    // =================== 辅助：byte[] → 十六进制小写字符串（JDK 无依赖）===================
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i*2]     = HEX[v >>> 4];
            out[i*2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}