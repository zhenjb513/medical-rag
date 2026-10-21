package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BM25 行构造的单测：全量重建和按份增量刷新共用 {@link Bm25RowBuilder}，
 * 这两条路算出的行必须逐字节相同、列序不能漂。
 * 分数在 PG 里现算（ndocs/avgdl/df 都是查询时的 CTE），所以 dl 与 tf 一旦和分词结果不一致，
 * 表现是「某次重建之后分数变了」，那种问题只能靠这里钉住。
 */
class Bm25RowBuilderTest {

    private static Bm25RowBuilder.ChunkRow sample(String content) {
        return Bm25RowBuilder.of("11111111-1111-1111-1111-111111111111",
                "D:\\ai-docs\\国家基层高血压防治管理指南2025版.pdf", "国家基层高血压防治管理指南2025版.pdf", 7, content);
    }

    @Test
    void dlEqualsTermCountAndTfSumsToIt() {
        Bm25RowBuilder.ChunkRow r = sample("血压 140/90 mmHg 以上建议启动药物治疗。");
        int tfSum = r.termFrequencies().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(r.dl(), tfSum, "dl 是分词后的 term 总数，必须等于各 term 词频之和");
        assertEquals(r.dl(), ((Integer) r.docRow()[4]).intValue(), "doc 行第 5 列就是 dl");
    }

    @Test
    void sameContentBuildsByteIdenticalRows() {
        // 全量重建与增量刷新之间不许出现「同一块正文两套行」——共用的构造函数只能给一个答案
        Bm25RowBuilder.ChunkRow a = sample("血脂：LDL-C 目标值 < 2.6 mmol/L。");
        Bm25RowBuilder.ChunkRow b = sample("血脂：LDL-C 目标值 < 2.6 mmol/L。");
        assertEquals(a.dl(), b.dl());
        assertEquals(new ArrayList<>(a.termFrequencies().keySet()), new ArrayList<>(b.termFrequencies().keySet()));
        assertEquals(a.postingRows().size(), b.postingRows().size());
        for (int i = 0; i < a.postingRows().size(); i++) {
            assertEquals(a.postingRows().get(i)[0], b.postingRows().get(i)[0]);
            assertEquals(a.postingRows().get(i)[2], b.postingRows().get(i)[2]);
        }
    }

    @Test
    void docRowKeepsTheFiveColumnOrderTheInsertExpects() {
        Bm25RowBuilder.ChunkRow r = sample("高血压患者随访。");
        Object[] row = r.docRow();
        assertEquals(5, row.length, "INSERT INTO bm25_docs (id, source, file_name, chunk_index, dl)");
        assertEquals("11111111-1111-1111-1111-111111111111", row[0]);
        assertEquals("国家基层高血压防治管理指南2025版.pdf", row[2]);
        assertEquals(7, ((Integer) row[3]).intValue());
    }

    @Test
    void sourceIsCopiedVerbatimBecauseTheIncrementalDeleteMatchesOnIt() {
        // 增量刷新靠 DELETE ... WHERE source IN (sourceKeys) 找回这一份的旧行；
        // 构造函数若在这里加工路径（换分隔符、去盘符、截断），删回来就对不上，倒排会出现同一块两行。
        Bm25RowBuilder.ChunkRow r = sample("戒烟建议。");
        assertEquals("D:\\ai-docs\\国家基层高血压防治管理指南2025版.pdf", r.source());
        assertEquals(r.source(), r.docRow()[1]);
    }

    @Test
    void repeatedTermCollapsesIntoOnePosting() {
        // bm25_postings 主键是 (term, doc_id)，同一个 term 出两行会撞主键，被 ON CONFLICT DO NOTHING 静默吃掉
        Bm25RowBuilder.ChunkRow r = sample("血压血压血压血压");
        Set<String> terms = new HashSet<>();
        for (Object[] p : r.postingRows()) {
            String term = (String) p[0];
            assertTrue(terms.add(term), "同一个 term 只能有一行 posting，重复的是 tf 不是行：" + term);
            assertEquals(r.docId(), p[1], "posting 第 2 列必须指向本块 id");
        }
        assertEquals(r.termFrequencies().size(), r.postingRows().size());
    }

    @Test
    void blankChunkStillGetsADocRowWithZeroDl() {
        // 零 term 的块也要落 doc 行：搜索 SQL 里 ndocs 与 avgdl 是从 bm25_docs 现算的，
        // 少一行就让分母和向量库块数不一致，idf 整体偏移。
        Bm25RowBuilder.ChunkRow r = sample("   ");
        assertEquals(0, r.dl());
        assertTrue(r.postingRows().isEmpty());
        assertEquals(0, ((Integer) r.docRow()[4]).intValue());
    }

    @Test
    void nullContentDoesNotBlowUpTheRebuild() {
        Bm25RowBuilder.ChunkRow r = Bm25RowBuilder.of("id-1", "D:\\ai-docs\\x.pdf", "x.pdf", 0, null);
        assertEquals(0, r.dl());
        assertTrue(r.postingRows().isEmpty());
    }
}
