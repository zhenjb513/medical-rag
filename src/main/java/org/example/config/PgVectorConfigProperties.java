package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pgvector")
public class PgVectorConfigProperties {

    /**
     * 是否启用 PgVector（持久化向量到 PostgreSQL vector 列）。
     *
     * 默认 false：使用 SimpleVectorStore（纯内存，无外部依赖，开箱即用）。
     * 什么时候设为 true：你的 PostgreSQL 安装了 pgvector 扩展（对应 PG 大版本要有预编译 dll），
     *                 且需要向量跨重启持久化。
     */
    private boolean enabled = false;

    private String jdbcUrl = "jdbc:postgresql://localhost:5432/postgres";
    private String username = "postgres";
    private String password = "postgres";
    private String driverClassName = "org.postgresql.Driver";

    private String schemaName = "public";
    private String tableName = "vector_store";
    private int dimensions = 1536;
    private String indexType = "HNSW";
    private String distanceType = "COSINE_DISTANCE";
    private String idType = "UUID";

    /** PgVectorStore 内部初始化开关（只有 enable=true 时才生效） */
    private boolean initializeSchema = false;
    private boolean schemaValidation = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public int getDimensions() { return dimensions; }
    public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    public String getIndexType() { return indexType; }
    public void setIndexType(String indexType) { this.indexType = indexType; }
    public String getDistanceType() { return distanceType; }
    public void setDistanceType(String distanceType) { this.distanceType = distanceType; }
    public String getIdType() { return idType; }
    public void setIdType(String idType) { this.idType = idType; }

    public boolean isInitializeSchema() { return initializeSchema; }
    public void setInitializeSchema(boolean initializeSchema) { this.initializeSchema = initializeSchema; }
    public boolean isSchemaValidation() { return schemaValidation; }
    public void setSchemaValidation(boolean schemaValidation) { this.schemaValidation = schemaValidation; }
}