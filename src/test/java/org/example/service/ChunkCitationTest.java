package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块级引用溯源（RagReferenceService.extractReferences）的口径守门测试。
 * <p>
 * 钉住两件事：① 每行最前面永远是文件名，因为评测的引用判据是「预期文档名是否为答案文本的子串」
 * （EvaluationService 第 375-387 行），块号只能追加在它后面，否则 98.4% 基线里的引用维度会整片误判；
 * ② 块号就是 metadata 里的 chunk_index，同一文件的多块聚成一行、升序去重，查不到块号时如实说明而不是编一个。
 * <p>
 * 只用 Document 的字段，不连库、不打模型；RagReferenceService 构造时的 VectorStore 传 null 就够
 * （引用这条路径压根不碰它）。
 */
class ChunkCitationTest {

    private static final String XZ = "中国血脂管理指南2023年.pdf";
    private static final String YJ = "中国心血管病一级预防指南2020.pdf";

    private static final RagReferenceService SVC = new RagReferenceService(null);

    private static Document doc(String file, Object chunkIndex, String text) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "/data/pdf/" + file);
        meta.put("file_name", file);
        if (chunkIndex != null) {
            meta.put("chunk_index", chunkIndex);
        }
        return new Document("id-" + file + "-" + chunkIndex, text, meta);
    }

    /** 只有 file_name（没有 source）：入库走 SimpleVectorStore 时就是这个形态。 */
    private static Document docFileNameOnly(String file, int chunkIndex) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("file_name", file);
        meta.put("chunk_index", chunkIndex);
        return new Document("id-" + file + "-" + chunkIndex, "正文", meta);
    }

    @Test
    @DisplayName("同一份文件的多个块聚成一行，块号升序去重")
    void groupsChunksOfOneFileAscending() {
        List<String> refs = SVC.extractReferences(List.of(
                doc(XZ, 12, "A"), doc(YJ, 5, "B"), doc(XZ, 3, "C"), doc(XZ, 4, "D"), doc(XZ, 3, "E")));
        assertEquals(List.of(XZ + "（块号 3、4、12）", YJ + "（块号 5）"), refs);
    }

    @Test
    @DisplayName("文件名排在每行最前：追加块号不能把评测的引用判据挤掉")
    void fileNameStaysFirstSoReferenceCaliberStillHits() {
        String answer = "模型的答复正文。\n\n" + SVC.formatReferences(SVC.extractReferences(List.of(doc(XZ, 97, "A"))));
        String norm = EvaluationService.normalizeForMatch(answer);
        // 考卷里 expectedReferences 就是这个纯文件名，子串判据必须仍然成立
        assertTrue(norm.contains(EvaluationService.normalizeForMatch(XZ)), answer);
        assertTrue(norm.contains("97"), "块号没跟上：" + answer);
    }

    @Test
    @DisplayName("路径前缀剥掉；只有 filename 这个键也认")
    void stripsPathAndAcceptsAlternateMetadataKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("filename", "C:\\kb\\" + YJ);
        meta.put("chunk_index", 0);
        assertEquals(List.of(YJ + "（块号 0）"),
                SVC.extractReferences(List.of(new Document("id-1", "正文", meta))));
    }

    @Test
    @DisplayName("块号支持字符串和 Long；认不出来的写法一律算「未记录块号」，不编造")
    void parsesChunkIndexLeniently() {
        assertEquals(List.of(XZ + "（块号 7）"), SVC.extractReferences(List.of(doc(XZ, "7", "A"))));
        assertEquals(List.of(XZ + "（块号 7）"), SVC.extractReferences(List.of(doc(XZ, 7L, "A"))));
        assertEquals(List.of(XZ + "（块号 7）"), SVC.extractReferences(List.of(doc(XZ, " 7 ", "A"))));
        assertEquals(List.of(XZ + "（本批块未记录块号）"), SVC.extractReferences(List.of(doc(XZ, null, "A"))));
        assertEquals(List.of(XZ + "（本批块未记录块号）"), SVC.extractReferences(List.of(doc(XZ, "第3块", "A"))));
        assertEquals(Integer.valueOf(7), RagReferenceService.parseChunkIndex("7"));
        assertNull(RagReferenceService.parseChunkIndex("abc"));
        assertNull(RagReferenceService.parseChunkIndex(null));
    }

    @Test
    @DisplayName("同一文件既有带块号的又有不带块号的：两个都报出来，不含糊")
    void reportsMissingIndexesAlongsideKnownOnes() {
        List<String> refs = SVC.extractReferences(List.of(doc(XZ, 3, "A"), doc(XZ, null, "B"), doc(XZ, null, "C")));
        assertEquals(List.of(XZ + "（块号 3；另有 2 块未记录块号）"), refs);
    }

    @Test
    @DisplayName("没有 source 时按 file_name 归属，同一文件不会因为键名不同被拆成两行")
    void sameFileUnderDifferentKeysGroupsOnce() {
        Map<String, Object> onlyName = new LinkedHashMap<>();
        onlyName.put("file_name", XZ);
        onlyName.put("chunk_index", 1);
        List<String> refs = SVC.extractReferences(List.of(
                new Document("id-a", "正文", onlyName), doc(XZ, 2, "正文"), docFileNameOnly(XZ, 3)));
        assertEquals(List.of(XZ + "（块号 1、2、3）"), refs);
    }

    @Test
    @DisplayName("空输入 = 空引用；formatReferences 空列表仍是 (无引用)，拒答题的输出形态没变")
    void emptyInputs() {
        assertEquals(List.of(), SVC.extractReferences(List.of()));
        assertEquals(List.of(), SVC.extractReferences(null));
        assertEquals("(无引用)", SVC.formatReferences(List.of()));
        assertEquals("(无引用)", SVC.formatReferences(null));
    }

    @Test
    @DisplayName("引用段整体长相：标题带核对方式，行首带序号，多文件按首次出现顺序")
    void formatsNumberedBlock() {
        String text = SVC.formatReferences(SVC.extractReferences(List.of(doc(YJ, 5, "A"), doc(XZ, 3, "B"))));
        String expected = "\n\n---\n【引用来源】（括号内是本次实际送入模型的文档块编号，即库里的 chunk_index；"
                + "回读该块正文、它在 PDF 里的第几页、页上印的页码和它归在哪一节：GET /rag/chunk?file=文件名&chunk=块号）\n"
                + "1." + YJ + "（块号 5）\n"
                + "2." + XZ + "（块号 3）\n";
        assertEquals(expected, text);
    }

    @Test
    @DisplayName("metadata 整个为空的块不进引用列表（以前会把 null 直接写进文件名里）")
    void skipsDocsWithoutAnyFileName() {
        List<String> refs = SVC.extractReferences(List.of(
                new Document("id-x", "正文", new LinkedHashMap<>()), doc(XZ, 3, "正文")));
        assertEquals(List.of(XZ + "（块号 3）"), refs);
    }

    @Test
    @DisplayName("source 不是字符串时取 String.valueOf，和日志行、判分块主键同一套取法")
    void nonStringSourceKeepsSameRuleAsLogAndBlockKey() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", 12345);
        meta.put("chunk_index", 2);
        List<String> refs = SVC.extractReferences(List.of(new Document("id-n", "正文", meta)));
        // ChatController.extractFileName 打的是 String.valueOf(source)，EvaluationService.chunkKeyOf 也一样，
        // 引用行要是偷偷把这行丢掉，日志和引用就对不上了
        assertEquals(List.of("12345（块号 2）"), refs);
    }
}
