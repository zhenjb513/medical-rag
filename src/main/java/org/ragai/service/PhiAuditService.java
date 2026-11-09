package org.ragai.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * PHI 命中审计落库（MySQL 业务库 {@code mkbAI}，表 {@code phi_audit_log}）。
 * <p>
 * 为什么原来那行日志不算审计：{@code ChatController} 以前把**含身份证/手机号的原始问句整条打进控制台**，
 * 既没留痕可查、又自己构成一次泄露。现在改成只落一行「命中了什么类型、各几条、原句指纹」，
 * 原文和脱敏后文本都不进库——脱敏后仍然可能留「我父亲有高血压」这类健康信息，那张表本身就该按敏感数据处理。
 * <p>
 * 落库失败绝不影响问答：建表和写入都吞 {@link DataAccessException} 并打一行日志，
 * 与 {@code InventoryService} 的区别是这里吞的是**自己的审计副作用**，不是把上游数据状态误报成 0。
 */
@Service
public class PhiAuditService {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS phi_audit_log (
              id           BIGINT AUTO_INCREMENT PRIMARY KEY,
              created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              message_hash CHAR(12)  NOT NULL COMMENT '原始问句 SHA-256 前 12 位，只用于确认是否同一句',
              endpoint     VARCHAR(32) NOT NULL COMMENT '入口，如 /chat',
              hit_types    VARCHAR(128) NOT NULL COMMENT '命中的 PHI 类型及条数，如 身份证:1,银行卡:1',
              hit_count    INT NOT NULL COMMENT '各类型条数相加（类型之间会重叠，见 PhiDesensitizationService.countHits 口径）',
              KEY idx_phi_audit_created (created_at)
            ) DEFAULT CHARSET=utf8mb4
            """;

    /** 列就这四列，测试会钉住它：多出任何一个装得下原文的列都等于把审计表变成聊天日志。 */
    static final String INSERT_SQL =
            "INSERT INTO phi_audit_log (message_hash, endpoint, hit_types, hit_count) VALUES (?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final PhiDesensitizationService phiDesensitizationService;

    private volatile boolean tableReady = false;

    /**
     * 必须点名 {@code businessJdbcTemplate}。容器里现在有两个 JdbcTemplate
     * （{@code pgVectorJdbcTemplate} 和 {@code businessJdbcTemplate}），都没有 @Primary，
     * 按类型注入会直接 NoUniqueBeanDefinitionException；不加限定符还会「碰巧」连到 PostgreSQL，
     * 那就是本表第一次上线时建表失败的原因（{@code MysqlBusinessConfig} 类注释有日志证据）。
     */
    public PhiAuditService(@Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate,
                           PhiDesensitizationService phiDesensitizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.phiDesensitizationService = phiDesensitizationService;
    }

    @PostConstruct
    void ensureSchema() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            tableReady = true;
            System.out.println("[PHI审计] ✅ phi_audit_log 就绪（MySQL 业务库，只存类型/条数/原句指纹，不存原文）");
        } catch (DataAccessException e) {
            System.out.println("[PHI审计] ⚠️ phi_audit_log 建表失败，本次运行不落审计（不影响问答）："
                    + e.getClass().getSimpleName() + ": " + rootMessage(e));
        }
    }

    /**
     * 记一行审计。**只在 {@code containsPhi=true} 时调用**——不然这张表就成了全量聊天流水，
     * 每问一句写一行，跟「PHI 命中审计」这个名字完全不是一回事。
     */
    public void recordPhiHit(String rawMessage, String endpoint) {
        Row row = build(rawMessage, endpoint, phiDesensitizationService.countHits(rawMessage));
        try {
            jdbcTemplate.update(INSERT_SQL, row.messageHash(), row.endpoint(), row.hitTypes(), row.hitCount());
            System.out.println("[PHI审计] 已落库 hash=" + row.messageHash() + "  " + row.hitTypes());
        } catch (DataAccessException e) {
            System.out.println("[PHI审计] ⚠️ 落库失败（不影响本次问答）："
                    + e.getClass().getSimpleName() + ": " + rootMessage(e));
        }
    }

    public boolean isTableReady() { return tableReady; }

    /**
     * 表里已有多少条审计。给 {@code /rag/diag} 用：这样「业务库连得上、表建出来了」能在零模型调用下被证实，
     * 不用为了验证写一行而打一次付费问答。数不出来时返回错误文本而不是抛异常——诊断接口不能因为审计挂了而 500。
     */
    public String countRows() {
        try {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phi_audit_log", Long.class);
            return String.valueOf(n);
        } catch (DataAccessException e) {
            return "查询失败: " + e.getClass().getSimpleName() + ": " + rootMessage(e);
        }
    }

    /** 审计行 = 原句指纹 + 入口 + 类型:条数 + 总条数。四列，且都不含原文或脱敏后文本。 */
    public record Row(String messageHash, String endpoint, String hitTypes, int hitCount) {}

    static Row build(String rawMessage, String endpoint, List<PhiDesensitizationService.PhiHit> hits) {
        StringBuilder types = new StringBuilder();
        int total = 0;
        for (PhiDesensitizationService.PhiHit h : hits) {
            if (types.length() > 0) types.append(',');
            types.append(h.type()).append(':').append(h.count());
            total += h.count();
        }
        String ep = endpoint == null ? "" : endpoint;
        if (ep.length() > 32) ep = ep.substring(0, 32);
        String t = types.toString();
        if (t.length() > 128) t = t.substring(0, 128);
        return new Row(sha256Prefix12(rawMessage), ep, t, total);
    }

    /** 原文的稳定指纹：SHA-256 十六进制前 12 位。够确认「是不是同一句话」，不够还原内容。 */
    static String sha256Prefix12(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                if (hex.length() >= 12) break;
            }
            return hex.substring(0, Math.min(12, hex.length()));
        } catch (java.security.NoSuchAlgorithmException e) {
            // JDK 必然有 SHA-256；真缺了也不能把原文当指纹写进库
            return "000000000000";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage();
    }
}