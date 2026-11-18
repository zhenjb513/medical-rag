package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 句子优先装桶分块器（V4）：先认句子，再把整句贪心塞进 ≤cap 字符的块，塞不下就 flush。
 * 块文本一律取原文子串（只 strip 首尾空白），不自己造连接符——块内换行/空格与原文完全一致。
 * <p>
 * 替代 {@code DocumentSplitters.recursive(512, 0)} 的原因：递归切分器的句子层写死英文模型，
 * 中文句号不认，刀口大量落在句中（离线实测块尾中断 80.6%）；V4 把同一指标降到 6.9%
 * （scratch/splitter_fix_compare.out，2026-11-17，四份真实 PDF）。代价是块数约 +15%。
 * <p>
 * 分句是启发式：中文 。！？； 一律算句末；英文 .!? 要求后面是空白或文末，且前面不是数字、
 * 不是孤立大写字母（挡掉 3.01 与 initials）；Vol. / e.g. 这类缩写仍会被误判成句末。
 * 单句本身超 cap 时只能按 cap 硬切——这是这种切法的天花板。
 */
public class SentenceFirstSplitter implements DocumentSplitter {

    private final int cap;

    public SentenceFirstSplitter(int maxSegmentSizeInChars) {
        if (maxSegmentSizeInChars <= 0) {
            throw new IllegalArgumentException("maxSegmentSizeInChars 必须为正: " + maxSegmentSizeInChars);
        }
        this.cap = maxSegmentSizeInChars;
    }

    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> out = new ArrayList<>();
        if (document == null) return out;
        String s = document.text();
        if (s == null || s.isEmpty()) return out;
        for (String piece : sentenceFirst(s, cap)) {
            out.add(TextSegment.from(piece, document.metadata()));
        }
        return out;
    }

    /**
     * 句子区间 [start,end)，首尾相接覆盖全文，不丢字。
     */
    static List<int[]> sentenceSpans(String s) {
        List<int[]> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        String closers = "」』\"'）)】》〉";
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean zh = "。！？；".indexOf(c) >= 0;
            boolean en = (c == '.' || c == '!' || c == '?')
                    && (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)))
                    && i > 0 && !Character.isDigit(s.charAt(i - 1))
                    && !(Character.isUpperCase(s.charAt(i - 1))
                         && (i - 2 < 0 || Character.isWhitespace(s.charAt(i - 2))));
            if (!zh && !en) continue;
            int j = i + 1;
            while (j < s.length() && closers.indexOf(s.charAt(j)) >= 0) j++;
            out.add(new int[]{start, j});
            start = j;
            i = j - 1;
        }
        if (start < s.length()) out.add(new int[]{start, s.length()});
        return out;
    }

    static List<String> sentenceFirst(String s, int cap) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int bs = -1, be = -1;
        for (int[] sp : sentenceSpans(s)) {
            if (sp[1] - sp[0] > cap) {
                if (bs >= 0) {
                    addTrimmed(out, s.substring(bs, be));
                    bs = -1;
                }
                for (int k = sp[0]; k < sp[1]; k += cap) {
                    addTrimmed(out, s.substring(k, Math.min(sp[1], k + cap)));
                }
                continue;
            }
            if (bs < 0) {
                bs = sp[0];
                be = sp[1];
            } else if (sp[1] - bs <= cap) {
                be = sp[1];
            } else {
                addTrimmed(out, s.substring(bs, be));
                bs = sp[0];
                be = sp[1];
            }
        }
        if (bs >= 0) addTrimmed(out, s.substring(bs, be));
        return out;
    }

    private static void addTrimmed(List<String> out, String piece) {
        String t = piece.strip();
        if (!t.isEmpty()) out.add(t);
    }
}