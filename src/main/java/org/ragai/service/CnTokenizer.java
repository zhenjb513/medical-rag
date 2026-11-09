package org.ragai.service;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BM25 用的中文分词。
 * <p>
 * 只分词不够用：实测「体位性低血压」被 HanLP 切成 体/位/性/低血压，专业词组整串召不回来。
 * 所以索引里同时写入「词」和「CJK 连续段的相邻二字组」，二字组等价于子串匹配；
 * 查询侧同理，但词给更高权重，避免二字组数量多把分数带偏。
 */
public final class CnTokenizer {

    private CnTokenizer() {}

    /** 词权重（BM25 查询侧的 term 权重） */
    public static final double WORD_WEIGHT = 2.0;
    /** 二字组权重 */
    public static final double BIGRAM_WEIGHT = 1.0;

    /** 归一化：全角转半角、小写、连续空白并成一个空格。 */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);      // 全角 ASCII → 半角
            else if (c == '　') c = ' ';                                    // 全角空格
            if (c < 'a' || c > 'z') c = Character.toLowerCase(c);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) { sb.append(' '); pendingSpace = false; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 建库用：返回一个块的全部 term（含重复，重复次数即 tf）。 */
    public static List<String> indexTerms(String rawText) {
        String text = normalize(rawText);
        if (text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Term t : HanLP.segment(text)) {
            String w = t.word;
            if (isJunk(w)) continue;
            if (w.length() >= 2 || isNumeric(w)) {
                out.add(w);
                addSubTokens(w, out);
            }
        }
        addCjkBigrams(text, out);
        return out;
    }

    /** 查询用：term → 权重（词 2.0，二字组 1.0，重复取大的那个）。 */
    public static Map<String, Double> queryWeights(String rawQuery) {
        String text = normalize(rawQuery);
        Map<String, Double> w = new LinkedHashMap<>();
        if (text.isEmpty()) return w;
        for (Term t : HanLP.segment(text)) {
            String s = t.word;
            if (!isJunk(s) && (s.length() >= 2 || isNumeric(s))) {
                w.merge(s, WORD_WEIGHT, Math::max);
                for (String sub : subTokens(s)) w.merge(sub, WORD_WEIGHT, Math::max);
            }
        }
        List<String> bigrams = new ArrayList<>();
        addCjkBigrams(text, bigrams);
        for (String b : bigrams) w.merge(b, BIGRAM_WEIGHT, Math::max);
        return w;
    }

    /**
     * 复合 token 拆子 token：库里「LDL-C」「140/90」会被切成一个整词，
     * 不拆的话查 LDL、查 140 都匹配不上整串，等于把最关键的数字藏起来了。
     */
    private static List<String> subTokens(String w) {
        if (w.indexOf('-') < 0 && w.indexOf('/') < 0) return List.of();
        List<String> parts = new ArrayList<>();
        for (String p : w.split("[-/]+")) {
            if (p.length() >= 2 && !p.equals(w)) parts.add(p);
        }
        return parts;
    }

    private static void addSubTokens(String w, List<String> out) {
        out.addAll(subTokens(w));
    }

    /** CJK 连续段内所有相邻二字组（长度 1 的段没有二字组）。 */
    private static void addCjkBigrams(String text, List<String> out) {
        int run = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean cjk = i < text.length() && isCjk(text.charAt(i));
            if (cjk && run < 0) run = i;
            if (!cjk && run >= 0) {
                for (int j = run; j + 1 < i; j++) out.add(text.substring(j, j + 2));
                run = -1;
            }
        }
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isNumeric(String w) {
        if (w.isEmpty()) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '%')) return false;
        }
        return true;
    }

    /** 标点、符号不进索引；单字虚词由调用方的「长度≥2」规则自然滤掉。 */
    private static boolean isJunk(String w) {
        if (w == null || w.isBlank()) return true;
        boolean anyLetterOrDigit = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetterOrDigit(c) || isCjk(c)) { anyLetterOrDigit = true; break; }
        }
        return !anyLetterOrDigit;   // 纯标点/纯符号
    }
}
