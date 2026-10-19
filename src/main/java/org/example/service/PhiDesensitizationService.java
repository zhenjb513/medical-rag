package org.example.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * PHI（Protected Health Information）患者隐私信息脱敏服务
 * 医疗合规必备：在用户提问进入RAG检索和大模型之前，对敏感信息进行脱敏
 *
 * 脱敏范围：
 * - 身份证号
 * - 手机号
 * - 病历号/住院号
 * - 出生日期
 * - 银行卡号（扩展）
 */
@Service
public class PhiDesensitizationService {

    // 身份证号：18位（最后一位可能是X）或15位
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
            "\\b\\d{17}[\\dXx]\\b|\\b\\d{15}\\b"
    );

    // 手机号：1开头，第二位3-9，共11位
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
    );

    // 病历号/住院号：字母+数字组合（至少6位数字），或纯数字住院号（8-12位）
    private static final Pattern MEDICAL_RECORD_NO_PATTERN = Pattern.compile(
            "(?i)(?:病历号|住院号|病案号|MRN|HIS)[：: ]*([A-Za-z]*\\d{6,12})"
    );

    // 出生日期：YYYY-MM-DD 或 YYYY/MM/DD 或 YYYY年MM月DD日
    private static final Pattern BIRTH_DATE_PATTERN = Pattern.compile(
            "\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?"
    );

    // 银行卡号：16-19位数字
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{16,19}(?!\\d)"
    );

    /**
     * 对输入文本进行完整PHI脱敏
     * @param input 原始输入
     * @return 脱敏后的文本
     */
    public String desensitize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        result = desensitizeIdCard(result);
        result = desensitizePhone(result);
        result = desensitizeMedicalRecordNo(result);
        result = desensitizeBirthDate(result);
        result = desensitizeBankCard(result);
        return result;
    }

    /**
     * 身份证号脱敏：保留前3位和后4位，中间用*代替
     * 例：320102199001011234 → 320***********1234
     */
    public String desensitizeIdCard(String text) {
        return ID_CARD_PATTERN.matcher(text).replaceAll(matchResult -> {
            String id = matchResult.group();
            if (id.length() == 18) {
                return id.substring(0, 3) + "***********" + id.substring(14);
            } else { // 15位
                return id.substring(0, 3) + "********" + id.substring(11);
            }
        });
    }

    /**
     * 手机号脱敏：保留前3位和后4位
     * 例：13812345678 → 138****5678
     */
    public String desensitizePhone(String text) {
        return PHONE_PATTERN.matcher(text).replaceAll(matchResult -> {
            String phone = matchResult.group();
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });
    }

    /**
     * 病历号/住院号脱敏：只保留前2位和后2位
     */
    public String desensitizeMedicalRecordNo(String text) {
        return MEDICAL_RECORD_NO_PATTERN.matcher(text).replaceAll(matchResult -> {
            String full = matchResult.group();
            String no = matchResult.group(1);
            String prefix = full.substring(0, full.length() - no.length());
            String maskedNo;
            if (no.length() <= 4) {
                maskedNo = "****";
            } else {
                maskedNo = no.substring(0, 2) + "*".repeat(no.length() - 4) + no.substring(no.length() - 2);
            }
            return prefix + maskedNo;
        });
    }

    /**
     * 出生日期脱敏：保留年份，月日用*代替
     * 例：1990-01-01 → 1990-**-**
     */
    public String desensitizeBirthDate(String text) {
        return BIRTH_DATE_PATTERN.matcher(text).replaceAll(matchResult -> {
            String date = matchResult.group();
            // 提取年份（前4位）
            String year = date.substring(0, 4);
            return year + "-**-**";
        });
    }

    /**
     * 银行卡号脱敏：保留前4位和后4位
     */
    public String desensitizeBankCard(String text) {
        return BANK_CARD_PATTERN.matcher(text).replaceAll(matchResult -> {
            String card = matchResult.group();
            if (card.length() <= 8) {
                return "****";
            }
            return card.substring(0, 4) + "*".repeat(card.length() - 8) + card.substring(card.length() - 4);
        });
    }

    /**
     * 检查输入是否包含PHI敏感信息（用于日志记录和审计）
     */
    public boolean containsPhi(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return ID_CARD_PATTERN.matcher(input).find()
                || PHONE_PATTERN.matcher(input).find()
                || MEDICAL_RECORD_NO_PATTERN.matcher(input).find()
                || BIRTH_DATE_PATTERN.matcher(input).find()
                || BANK_CARD_PATTERN.matcher(input).find();
    }

    /** 一类 PHI 的命中情况，供审计落库用（只带类型名和条数，不带命中文本）。 */
    public java.util.List<PhiHit> countHits(String input) {
        java.util.List<PhiHit> hits = new java.util.ArrayList<>();
        if (input == null || input.isEmpty()) {
            return hits;
        }
        addHits(hits, "身份证", ID_CARD_PATTERN, input);
        addHits(hits, "手机号", PHONE_PATTERN, input);
        addHits(hits, "病历号", MEDICAL_RECORD_NO_PATTERN, input);
        addHits(hits, "出生日期", BIRTH_DATE_PATTERN, input);
        addHits(hits, "银行卡", BANK_CARD_PATTERN, input);
        return hits;
    }

    private static void addHits(java.util.List<PhiHit> hits, String type, Pattern pattern, String input) {
        int n = 0;
        java.util.regex.Matcher m = pattern.matcher(input);
        while (m.find()) n++;
        if (n > 0) hits.add(new PhiHit(type, n));
    }

    /**
     * 审计用的分类命中计数：按原文逐类数正则命中了多少条，类别顺序与 {@link #desensitize} 的处理顺序一致。
     * <p>
     * ⚠️ 口径：数的是**原文上各正则各自的命中数**，类与类会重叠。18 位身份证同时满足银行卡的
     * 16~19 位数字，所以会报成「身份证 1 条 + 银行卡 1 条」。这是刻意的——审计回答的是
     * 「原文里有哪些形态的敏感数字被规则看见了」，不是「脱敏链实际改了几处」，别拿它去对星号数。
     */
    public record PhiHit(String type, int count) {}
}
