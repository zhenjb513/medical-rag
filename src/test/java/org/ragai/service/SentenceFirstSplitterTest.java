package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V4 句子优先分块器的守门：不丢字、不超帽、块是原文逐字子串、刀口优先句末、确定性、metadata 随块走。
 * 纯合成文本，不连库、不打模型、不依赖本机 PDF（CI 三 OS 可跑）。
 * 离线收益数字（块尾中断 80.6%→6.9%）锚在 scratch/splitter_fix_compare.out，改切法先重跑那份对照。
 */
class SentenceFirstSplitterTest {

    private static final String MIXED = """
            高血压患者应限制钠盐摄入。每日食盐不超过5 g！WHO 推荐 <140/90 mmHg。
            他说：「血压要达标。」然后离开了。
            LDL-C 水平显著升高，≥4.14 mmol/L 者达 8.1%。研究见 NEJM 2021; 385: 1834-1844.
            English sentence one. Sentence two follows here! Is this three? Yes it is.
            表格行没有句末标点
            140/90 mmHg 130/80 mmHg
            """;

    private static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '\u00AD') continue;
            b.append(c);
        }
        return b.toString();
    }

    private static List<TextSegment> split(String text, int cap) {
        return new SentenceFirstSplitter(cap).split(Document.from(text));
    }

    @Test
    @DisplayName("不丢字：所有块抹空白后首尾相接 = 原文抹空白")
    void noCharacterLoss() {
        for (int cap : new int[]{16, 64, 512}) {
            List<TextSegment> segs = split(MIXED, cap);
            StringBuilder joined = new StringBuilder();
            for (TextSegment s : segs) joined.append(norm(s.text()));
            assertEquals(norm(MIXED), joined.toString(), "cap=" + cap + " 时丢字或改字");
        }
    }

    @Test
    @DisplayName("不超帽：任何块的原始长度 ≤ cap（含单句超长的硬切段）")
    void neverExceedsCap() {
        for (int cap : new int[]{16, 64, 512}) {
            for (TextSegment s : split(MIXED, cap)) {
                assertTrue(s.text().length() <= cap,
                        "cap=" + cap + " 出现超长块(" + s.text().length() + "): " + s.text());
            }
        }
    }

    @Test
    @DisplayName("块是原文逐字子串：只挪刀口，不造连接符、不改块内空白")
    void blocksAreVerbatimSubstrings() {
        for (TextSegment s : split(MIXED, 64)) {
            assertTrue(MIXED.contains(s.text()), "块不是原文子串: " + s.text());
        }
    }

    @Test
    @DisplayName("确定性：同一输入切两遍逐块相同（块号是考卷标注的主键，不能漂）")
    void deterministic() {
        assertEquals(split(MIXED, 64).stream().map(TextSegment::text).toList(),
                split(MIXED, 64).stream().map(TextSegment::text).toList());
    }

    @Test
    @DisplayName("刀口优先句末：非硬切块一律收在句末标点（表格行无标点可收的除外）")
    void cutsPreferSentenceEnds() {
        String prose = "第一句话在这里。第二句话也不短啊。第三句话继续说。第四句话收尾了。";
        List<TextSegment> segs = split(prose, 16);
        assertTrue(segs.size() >= 3, "16 字帽应切出多块");
        String tails = "。！？；.!?;”』)」】]》";
        for (TextSegment s : segs) {
            String t = s.text().strip();
            assertTrue(tails.indexOf(t.charAt(t.length() - 1)) >= 0,
                    "纯正文块的刀口没落在句末: " + t);
        }
    }

    @Test
    @DisplayName("单句超帽只能硬切：段段 ≤ cap 且合起来不丢字（这是切法的天花板，不是 bug）")
    void overlongSentenceHardCut() {
        String longSentence = "这是一个非常长的句子".repeat(60) + "。";
        assertTrue(longSentence.length() > 512);
        List<TextSegment> segs = split(longSentence, 512);
        assertTrue(segs.size() >= 2);
        StringBuilder joined = new StringBuilder();
        for (TextSegment s : segs) {
            assertTrue(s.text().length() <= 512);
            joined.append(s.text());
        }
        assertEquals(longSentence.strip(), joined.toString());
    }

    @Test
    @DisplayName("小数与期刊页码不是句末：3.01 / 385: 1834-1844. 不会被当成句子边界")
    void decimalsAreNotSentenceEnds() {
        String s = "LDL-C 降至 3.01 mmol/L 达标。";
        List<int[]> spans = SentenceFirstSplitter.sentenceSpans(s);
        assertEquals(1, spans.size(), "小数点被误判成句末: " + spans.size() + " 段");
        String en = "Targets are listed below. Next sentence here.";
        List<int[]> enSpans = SentenceFirstSplitter.sentenceSpans(en);
        assertEquals(2, enSpans.size(), "英文句末识别不对: " + enSpans.size() + " 段");
        // 已知取舍：数字后的 . 一律不认（挡 3.01），所以页码 1834-1844. 后的句末也被放过，
        // 两句会并成一段——启发式的代价，离线对照 [1d] 已按同一口径量过。
        String pageRef = "See NEJM 2021; 385: 1834-1844. Next sentence here.";
        assertEquals(1, SentenceFirstSplitter.sentenceSpans(pageRef).size());
    }

    @Test
    @DisplayName("收尾引号跟着句末走：。」不会被劈成两块")
    void closersStayWithSentenceEnd() {
        String s = "他说：「血压要达标。」然后离开了。";
        List<TextSegment> segs = split(s, 14);
        boolean found = segs.stream().anyMatch(t -> t.text().contains("。」"));
        assertTrue(found, "。」被拆开了: " + segs.stream().map(TextSegment::text).toList());
    }

    @Test
    @DisplayName("空文档/纯空白 → 空列表；cap 非正 → 构造即拒")
    void emptyAndInvalidInput() {
        // Document.from 拒绝 null/空白文本（框架闸门），空输入走内部方法与 null Document 验
        assertTrue(SentenceFirstSplitter.sentenceFirst("", 512).isEmpty());
        assertTrue(SentenceFirstSplitter.sentenceFirst("  \n ", 512).isEmpty());
        assertTrue(new SentenceFirstSplitter(512).split(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SentenceFirstSplitter(0));
        assertThrows(IllegalArgumentException.class, () -> new SentenceFirstSplitter(-5));
    }

    @Test
    @DisplayName("metadata 随块走：增量判断读的 content_hash 在每个块上都在")
    void metadataPropagatesToEverySegment() {
        Metadata meta = new Metadata();
        meta.put("content_hash", "abc123");
        meta.put("file_name", "测试.pdf");
        meta.put("extract_version", "2026-10-12a");
        List<TextSegment> segs = new SentenceFirstSplitter(16).split(Document.from(MIXED, meta));
        assertTrue(segs.size() > 1);
        for (TextSegment s : segs) {
            assertEquals("abc123", s.metadata().getString("content_hash"));
            assertEquals("测试.pdf", s.metadata().getString("file_name"));
        }
    }

    @Test
    @DisplayName("每块 Metadata 必须独立：TextSegment.from 共享传入实例（beta3/1.23 均如此），不拷贝会让 chunk_hash 互相覆盖")
    void metadataIsolatedPerSegment() {
        Metadata meta = new Metadata();
        meta.put("content_hash", "abc123");
        List<TextSegment> segs = new SentenceFirstSplitter(16).split(Document.from(MIXED, meta));
        assertTrue(segs.size() > 1);
        assertTrue(segs.get(0).metadata() != segs.get(1).metadata(),
                "两块共享同一个 Metadata 实例：闸门逐块写 chunk_hash/block_type 会互相覆盖");
        segs.get(0).metadata().put("chunk_hash", "h0");
        assertNull(segs.get(1).metadata().getString("chunk_hash"),
                "给第 0 块写 chunk_hash 不该出现在第 1 块上");
        assertNull(meta.getString("chunk_hash"), "也不该写回文档级 metadata");
    }

    @Test
    @DisplayName("生产接线守门：RagInitializer 用的就是 SentenceFirstSplitter(512)，别被悄悄换回去")
    void productionWiring() throws Exception {
        RagInitializer init = new RagInitializer(null, null, null, null);
        Field f = RagInitializer.class.getDeclaredField("splitter");
        f.setAccessible(true);
        Object splitter = f.get(init);
        assertInstanceOf(SentenceFirstSplitter.class, splitter,
                "生产分块器被换掉了：改切法必须重跑离线对照并 bump EXTRACT_PIPELINE_VERSION");
        Field cap = SentenceFirstSplitter.class.getDeclaredField("cap");
        cap.setAccessible(true);
        assertEquals(512, cap.getInt(splitter));
    }
}