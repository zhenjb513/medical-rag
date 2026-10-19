package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHI 审计行的口径：只准记「原句指纹 + 入口 + 命中类型:条数」，任何一列都不许带上原文。
 * <p>
 * 这条 guard 存在的理由：改之前 {@code ChatController} 把含身份证和手机号的整条原始问句打进控制台，
 * 审计落库如果顺手把原文或脱敏后文本也存进去，等于把同一个泄露换了个更持久、更难删除的地方。
 * 纯静态方法，不连库——建表和写入路径要靠重启时日志里的 {@code [PHI审计]} 行确认。
 */
class PhiAuditRowTest {

    private static final String RAW = "我叫张三，身份证 320102199001011234，手机 13812345678，血压 140/90";

    @Test
    @DisplayName("指纹：12 位十六进制，同一句稳定、不同句不同")
    void hashIsStableAndShort() {
        String h = PhiAuditService.sha256Prefix12(RAW);
        assertEquals(12, h.length(), h);
        assertTrue(h.matches("[0-9a-f]{12}"), h);
        assertEquals(h, PhiAuditService.sha256Prefix12(RAW));
        assertNotEquals(h, PhiAuditService.sha256Prefix12(RAW + "。"));
        // null 不进异常分支，也绝不退化成「把原文当指纹」：按空串哈希，仍是 12 位十六进制
        String nullHash = PhiAuditService.sha256Prefix12(null);
        assertTrue(nullHash.matches("[0-9a-f]{12}"), nullHash);
        assertEquals(PhiAuditService.sha256Prefix12(""), nullHash);
    }

    @Test
    @DisplayName("四列里没有一列装得下原文：身份证、手机号、姓名都不许出现")
    void rowNeverCarriesRawText() {
        PhiAuditService.Row row = PhiAuditService.build(RAW, "/chat",
                new PhiDesensitizationService().countHits(RAW));
        for (String cell : List.of(row.messageHash(), row.endpoint(), row.hitTypes())) {
            assertFalse(cell.contains("320102199001011234"), "命中列漏了整张身份证：" + cell);
            assertFalse(cell.contains("13812345678"), "命中列漏了手机号：" + cell);
            assertFalse(cell.contains("张三"), "命中列漏了姓名：" + cell);
            assertFalse(cell.contains("19900101"), "命中列漏了身份证里的出生日期段：" + cell);
        }
        assertTrue(row.hitCount() > 0, row.toString());
    }

    @Test
    @DisplayName("hit_types 口径：类型:条数 逗号拼，hit_count 是各类型相加")
    void hitTypesFormat() {
        List<PhiDesensitizationService.PhiHit> hits = List.of(
                new PhiDesensitizationService.PhiHit("身份证", 1),
                new PhiDesensitizationService.PhiHit("银行卡", 1));
        PhiAuditService.Row row = PhiAuditService.build(RAW, "/chat", hits);
        assertEquals("身份证:1,银行卡:1", row.hitTypes());
        assertEquals(2, row.hitCount());
    }

    @Test
    @DisplayName("18 位身份证会被银行卡正则重叠命中——这是已定口径，别当 bug 改")
    void overlappingCountsAreTheDocumentedCaliber() {
        List<String> types = new PhiDesensitizationService().countHits(RAW)
                .stream().map(PhiDesensitizationService.PhiHit::type).toList();
        assertTrue(types.contains("身份证") && types.contains("银行卡"),
                "审计数的是原文上各正则各自的命中数，身份证同时满足 16~19 位数字，所以两类都报。"
                        + " 实得=" + types);
    }

    @Test
    @DisplayName("没命中也建得出行（ChatController 只在 hasPhi 时调用，这里只保证不抛）")
    void emptyHitsBuild() {
        PhiAuditService.Row row = PhiAuditService.build("高血压降压目标是多少", "/chat", List.of());
        assertEquals("", row.hitTypes());
        assertEquals(0, row.hitCount());
    }

    @Test
    @DisplayName("超出列宽要截断，不能把插入撑失败")
    void longValuesAreTruncatedToColumnWidth() {
        String longEndpoint = "/".repeat(40);
        PhiAuditService.Row viaEndpoint = PhiAuditService.build(RAW, longEndpoint, List.of());
        assertEquals(32, viaEndpoint.endpoint().length(), viaEndpoint.endpoint());

        List<PhiDesensitizationService.PhiHit> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(new PhiDesensitizationService.PhiHit("类型" + i, i));
        PhiAuditService.Row viaTypes = PhiAuditService.build(RAW, "/chat", many);
        assertTrue(viaTypes.hitTypes().length() <= 128,
                "hit_types 超出 VARCHAR(128)：" + viaTypes.hitTypes().length());
    }

    @Test
    @DisplayName("插入只有四列，且与 Row 的字段一一同名")
    void insertColumnsArePinned() {
        assertEquals("INSERT INTO phi_audit_log (message_hash, endpoint, hit_types, hit_count) VALUES (?, ?, ?, ?)",
                PhiAuditService.INSERT_SQL);
        assertEquals(4, PhiAuditService.INSERT_SQL.chars().filter(c -> c == '?').count());
    }
}
