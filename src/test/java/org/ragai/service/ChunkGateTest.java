package org.ragai.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库前的块级闸门与块级增量（A2 + B1）。
 * <p>
 * 钉三件容易悄悄坏掉、且坏了不报错的事：
 * ① 跨文件查重<b>必须排除本文件自己的 source</b>。重灌时本文件的旧行是在过滤之后才被删的，
 *    把自己算进「库内已存在」会让未变的块先被判重复丢掉、紧接着旧行删除，整块内容静默消失。
 * ② 块级增量省的是 embedding 调用，<b>不省落库行数</b>：未变块带着旧向量照样重新 INSERT。
 *    把「只重灌变化的块」当成现状，就会误读 /rag/diag 里的计数。
 * ③ 去重后的重复块根本不进库，所以同一段共享模板文字只归属第一个灌进去的那份文件。
 */
class ChunkGateTest {

    private static final String SELF = "D:/ai-docs/基层指南.pdf";

    /** pgJdbc=null 的实例：跨文件查重整段跳过，只验低信息量过滤与文件内去重。 */
    private static RagInitializer initializerWithoutDb() {
        return new RagInitializer(null, null, null, null);
    }

    private static TextSegment chunk(String text) {
        return TextSegment.from(text, new Metadata());
    }

    private static String body(String prefix) {
        return prefix + "中国血脂管理指南推荐成人 LDL-C 目标值按危险分层设定";
    }

    /**
     * 只记录 SQL 与绑定参数、并按 alreadyIndexed 回答跨文件查重的假 JDBC。
     * 生产代码走的是 {@code query(sql, ResultSetExtractor, args...)}，所以这里覆写的是那一个重载，
     * 不是 RowMapper 那个——打错重载的话记录不到任何东西，查询结果也会静默变成 null。
     */
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<String> sqls = new CopyOnWriteArrayList<>();
        final List<Object> args = new CopyOnWriteArrayList<>();
        volatile Set<String> alreadyIndexed = Set.of();

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> rse, Object... inArgs) {
            sqls.add(sql);
            List<String> hits = new ArrayList<>();
            for (Object a : inArgs) {
                args.add(a);
                if (alreadyIndexed.contains(String.valueOf(a))) hits.add(String.valueOf(a));
            }
            ResultSet rs = Mockito.mock(ResultSet.class);
            AtomicInteger cursor = new AtomicInteger();
            try {
                Mockito.when(rs.next()).thenAnswer(inv -> cursor.get() < hits.size());
                Mockito.when(rs.getString(1)).thenAnswer(inv -> hits.get(cursor.getAndIncrement()));
                return rse.extractData(rs);
            } catch (java.sql.SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    // ===== ① 低信息量判据 =====

    @Test
    @DisplayName("空块、纯空白、非空白字符不足 10 的都算低信息量")
    void tooShortOrBlankIsLowInformation() {
        assertTrue(RagInitializer.isLowInformationChunk(null));
        assertTrue(RagInitializer.isLowInformationChunk(""));
        assertTrue(RagInitializer.isLowInformationChunk("   \n\t "));
        assertTrue(RagInitializer.isLowInformationChunk("高血压指南"), "5 个非空白字符，够不上 10");
    }

    @Test
    @DisplayName("纯数字、纯标点块没有字母/汉字，靠 meaningful<2 拦掉")
    void digitsAndPunctuationOnlyAreLowInformation() {
        assertTrue(RagInitializer.isLowInformationChunk("1234567890"));
        assertTrue(RagInitializer.isLowInformationChunk("0.0 1.2 3.4 5.6"), "数字再长也不算正文");
        assertTrue(RagInitializer.isLowInformationChunk("——— ... !!!"));
    }

    @Test
    @DisplayName("页码形状有专门一条正则：汉字/字母数量够，只有正则拦得住")
    void pageNumberShapesNeedTheRegex() {
        assertTrue(RagInitializer.isLowInformationChunk("第 12 页"), "第、页 两个汉字已过 meaningful 关");
        assertTrue(RagInitializer.isLowInformationChunk("Page 12"), "Page 四个字母已过 meaningful 关");
        assertFalse(RagInitializer.isLowInformationChunk("第 12 页 记录了血压测量的注意事项"),
                "页码只是开头、后面有正文的块不能误杀");
    }

    @Test
    @DisplayName("正常正文与含字母的数值表格行必须放行")
    void realBodyAndTablesStay() {
        assertFalse(RagInitializer.isLowInformationChunk(body("4.1.2 ")));
        assertFalse(RagInitializer.isLowInformationChunk("LDL-C 2.6 mmol/L 以下 适用于高危患者"));
        assertFalse(RagInitializer.isLowInformationChunk("表3 不同人群血压目标值 130/80 mmHg"));
    }

    // ===== ② 文件内去重 + chunk_hash 落 metadata =====

    @Test
    @DisplayName("同文件同 hash 只留第一次出现，chunk_hash 写进 metadata")
    void withinFileDuplicateKeepsFirstOccurrenceOnly() {
        String dup = body("重复段落 ");
        List<TextSegment> kept = initializerWithoutDb().filterAndDeduplicateChunks(
                List.of(chunk(dup), chunk("另起一段正常正文，讲的是随访频率与复查项目。"), chunk(dup)),
                new String[]{SELF}, "  ");

        assertEquals(2, kept.size(), "重复的那一份不该进库");
        assertEquals(dup, kept.get(0).text(), "留的必须是首次出现那一份");
        for (TextSegment c : kept) {
            String hash = c.metadata().getString("chunk_hash");
            assertTrue(hash != null && hash.length() == 64, "块级增量和跨文件查重都读这个键：" + hash);
        }
    }

    @Test
    @DisplayName("低信息量块被过滤后不去重、不写库")
    void lowInfoChunksAreDroppedBeforeDedup() {
        List<TextSegment> kept = initializerWithoutDb().filterAndDeduplicateChunks(
                List.of(chunk("第 3 页"), chunk("12 / 88"), chunk(body("前言 "))),
                new String[]{SELF}, "  ");
        assertEquals(1, kept.size());
        assertEquals(body("前言 "), kept.get(0).text());
    }

    // ===== ③ 跨文件查重：排除自身是唯一不能错的约束 =====

    @Test
    @DisplayName("查重 SQL 把本文件的 source 作为参数绑进 NOT IN")
    void crossFileLookupExcludesOwnSource() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new RagInitializer(null, jdbc, null, null)
                .filterAndDeduplicateChunks(List.of(chunk(body("免责声明 "))), new String[]{SELF}, "  ");

        assertEquals(1, jdbc.sqls.size(), "一份文件一次跨文件查重");
        String sql = jdbc.sqls.get(0);
        assertTrue(sql.contains("chunk_hash"), "查重要按块级 hash：" + sql);
        assertTrue(sql.contains("NOT IN"),
                "不排除自身 source，重灌时未变的块会被判成重复、随后随旧行一起消失：" + sql);
        assertTrue(jdbc.args.contains(SELF), "自身 source 必须走绑定参数：" + jdbc.args);
    }

    @Test
    @DisplayName("库内其它文件已有同 hash 的块，本文件这块不再写入")
    void duplicateOfAnotherFileIsDropped() {
        String shared = body("免责声明 ");
        String own = body("本文件独有的段落 ");
        // 先走一次无库路径，拿生产代码真算出来的 chunk_hash，避免测试自己实现一遍哈希
        String sharedHash = initializerWithoutDb()
                .filterAndDeduplicateChunks(List.of(chunk(shared)), new String[]{SELF}, "  ")
                .get(0).metadata().getString("chunk_hash");

        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.alreadyIndexed = Set.of(sharedHash);

        List<TextSegment> kept = new RagInitializer(null, jdbc, null, null)
                .filterAndDeduplicateChunks(List.of(chunk(shared), chunk(own)), new String[]{SELF}, "  ");

        assertEquals(1, kept.size(), "跨文件重复块要砍掉，连 embedding 调用一起省掉");
        assertEquals(own, kept.get(0).text());
    }

    @Test
    @DisplayName("没给出自身 source 时用占位键兜住 NOT IN，不把语法写坏")
    void missingSelfKeysFallsBackToNeverMatchingSentinel() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new RagInitializer(null, jdbc, null, null)
                .filterAndDeduplicateChunks(List.of(chunk(body("段落 "))), new String[0], "  ");

        assertTrue(jdbc.args.contains("\u0000__never_matches__"),
                "空 source 键时 NOT IN 里必须有占位：" + jdbc.args);
    }

    // ===== ④ 块级增量：省模型调用，不省写入行数 =====

    @Test
    @DisplayName("未变块复用旧向量，只有变化块送模型；但全部块照样写库")
    void unchangedChunksReuseVectorsButStillGetWritten() {
        TextSegment old = chunk(body("第一块 "));
        TextSegment changed = chunk(body("第二块换了内容 "));
        String oldHash = "a".repeat(64);
        old.metadata().put("chunk_hash", oldHash);
        changed.metadata().put("chunk_hash", "b".repeat(64));

        PgVectorEmbeddingStore store = Mockito.mock(PgVectorEmbeddingStore.class);
        EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
        ArgumentCaptor<List<TextSegment>> embedCap = ArgumentCaptor.forClass(List.class);
        Mockito.when(model.embedAll(embedCap.capture()))
                .thenReturn(Response.from(List.of(new Embedding(new float[]{1f, 2f}))));

        Map<String, RagInitializer.StoredChunk> oldByHash = new HashMap<>();
        oldByHash.put(oldHash, new RagInitializer.StoredChunk(
                "11111111-1111-1111-1111-111111111111", new float[]{7f, 8f}));

        int batches = new RagInitializer(store, null, null, model)
                .writeChunksInBatchesWithReuse(List.of(old, changed), 2, oldByHash, "  ");

        assertEquals(1, embedCap.getValue().size(), "只有变化块该送模型");
        assertEquals(changed.text(), embedCap.getValue().get(0).text());
        assertEquals(1, batches, "每批 ≤10 块，2 块就是一批");

        ArgumentCaptor<List<String>> idsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> vecCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TextSegment>> segCap = ArgumentCaptor.forClass(List.class);
        Mockito.verify(store).addAll(idsCap.capture(), vecCap.capture(), segCap.capture());
        assertEquals(2, segCap.getValue().size(), "未变块也重新写库——省的是调用量，不是行数");
        assertEquals("11111111-1111-1111-1111-111111111111", idsCap.getValue().get(0), "复用块沿用原来的主键");
        assertEquals(7f, vecCap.getValue().get(0).vector()[0]);
    }

    @Test
    @DisplayName("库里没有旧块（首次入库）时全块送模型，与增量改造前一致")
    void firstIngestEmbedsEverything() {
        TextSegment a = chunk(body("甲 "));
        TextSegment b = chunk(body("乙 "));
        a.metadata().put("chunk_hash", "c".repeat(64));
        b.metadata().put("chunk_hash", "d".repeat(64));

        PgVectorEmbeddingStore store = Mockito.mock(PgVectorEmbeddingStore.class);
        EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
        ArgumentCaptor<List<TextSegment>> embedCap = ArgumentCaptor.forClass(List.class);
        Mockito.when(model.embedAll(embedCap.capture()))
                .thenReturn(Response.from(List.of(new Embedding(new float[]{1f}), new Embedding(new float[]{2f}))));

        new RagInitializer(store, null, null, model)
                .writeChunksInBatchesWithReuse(List.of(a, b), 2, Map.of(), "  ");

        assertEquals(2, embedCap.getValue().size(), "没有可复用的旧块就不该省调用");
    }
}
