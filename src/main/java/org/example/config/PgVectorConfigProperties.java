package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pgvector")
public class PgVectorConfigProperties {

    /** 是否启用 PgVector（持久化向量）。默认 false 使用 SimpleVectorStore（内存版，开箱即用）。 */
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

    /** PgVectorStore 内部初始化开关（仅 enabled=true 时生效） */
    private boolean initializeSchema = false;
    private boolean schemaValidation = false;

    /** 启动时若为 true，先清空并重建 vector_store 表（重新入库所有 PDF） */
    private boolean resetTable = false;

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

    public boolean isResetTable() { return resetTable; }
    public void setResetTable(boolean resetTable) { this.resetTable = resetTable; }
}
