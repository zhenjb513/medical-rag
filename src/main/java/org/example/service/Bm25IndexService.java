package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.PgVectorConfigProperties;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 关键词召回：把 vector_store 里每个 chunk 分词后写进自建倒排表，检索时在 PG 里直接算 Okapi BM25。
 * <p>
 * 只存 (id, dl) 不存正文——正文仍然只在 vector_store 一份，避免两处文本不同步；
 * bm25_postings 靠外键级联删除，向量块被删掉时倒排也一起没了。
 */
@Service
public class Bm25IndexService {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int INSERT_CHUNK = 400;

    private final JdbcTemplate pg;
    private final PgVectorConfigProperties props;

    @Autowired
    public Bm25IndexService(
            @Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("pgVectorJdbcTemplate") JdbcTemplate pg,
            PgVectorConfigProperties props) {
        this.pg = pg;
        this.props = props;
    }

    /** content 必须是全文：混合融合和召回评测都靠它比对关键词，截断版只能给 snippet。 */
    public record Hit(String id, double score, String fileName, String source, Integer chunkIndex,
                      String content, String snippet) {}

    public boolean isAvailable() { return pg != null; }

    private String vectorTable() {
        return props.getSchemaName() + "." + props.getTableName();
    }

    public void ensureSchema() {
        requirePg();
        pg.execute("CREATE TABLE IF NOT EXISTS bm25_docs ("
                + "  id          uuid PRIMARY KEY,"
                + "  source      text,"
                + "  file_name   text,"
                + "  chunk_index int,"
                + "  dl          int NOT NULL)");
        pg.execute("ALTER TABLE bm25_docs ADD COLUMN IF NOT EXISTS source text");
        pg.execute("CREATE TABLE IF NOT EXISTS bm25_postings ("
                + "  term    text NOT NULL,"
                + "  doc_id  uuid NOT NULL REFERENCES bm25_docs(id) ON DELETE CASCADE,"
                + "  tf      int NOT NULL,"
                + "  PRIMARY KEY (term, doc_id))");
    }

    /** 全量重建：清表 → 读 vector_store → 分词 → 写倒排。返回一段可直接看的人类可读统计。 */
    public String rebuildAll() {
        requirePg();
        ensureSchema();
        long t0 = System.currentTimeMillis();
        pg.execute("TRUNCATE bm25_docs CASCADE");

        List<Map<String, Object>> rows = pg.queryForList(
                "SELECT id, content, metadata->>'source' AS src, metadata->>'file_name' AS fname, "
                        + "(metadata->>'chunk_index')::int AS ci FROM " + vectorTable());

        long termPairs = 0;
        List<Object[]> docArgs = new ArrayList<>();
        List<Object[]> postingArgs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            // 行构造只此一份实现：增量刷新走的是同一个方法，两条路算出的倒排行必须逐字节相同
            Bm25RowBuilder.ChunkRow row = Bm25RowBuilder.of(
                    r.get("id"), (String) r.get("src"), (String) r.get("fname"),
                    (Integer) r.get("ci"), (String) r.get("content"));
            docArgs.add(row.docRow());
            postingArgs.addAll(row.postingRows());
            termPairs += row.termFrequencies().size();
        }

        batch("INSERT INTO bm25_docs (id, source, file_name, chunk_index, dl) VALUES ", docArgs, 5);
        batch("INSERT INTO bm25_postings (term, doc_id, tf) VALUES ", postingArgs, 3);

        long ms = System.currentTimeMillis() - t0;
        Integer docs = pg.queryForObject("SELECT count(*) FROM bm25_docs", Integer.class);
        Integer vocab = pg.queryForObject("SELECT count(DISTINCT term) FROM bm25_postings", Integer.class);
        Double avgdl = pg.queryForObject("SELECT coalesce(avg(dl),0)::float8 FROM bm25_docs", Double.class);
        return "BM25 重建完成：文档块=" + docs + "  倒排条目=" + termPairs
                + "  词表=" + vocab + "  平均块长度(词)=" + String.format("%.1f", avgdl)
                + "  耗时=" + ms + "ms";
    }

    /**
     * 按份增量刷新：只清这一份文件的倒排行，再按它现在的块重算写回。
     * 代价跟**这份文件**的块数成正比，而不是跟全库成正比——这是 {@code rebuildAll} 每次入库
     * 都要 TRUNCATE 重插一遍整张表的那个规模化瓶颈。
     * <p>
     * posting 不用单独删：{@code bm25_postings.doc_id} 外键带 {@code ON DELETE CASCADE}。
     * <p>
     * ⚠️ 这里**刻意不统计全库**（{@code count(*) FROM bm25_docs} /
     * {@code count(DISTINCT term) FROM bm25_postings}）。第一版为了能「在日志里看出增量后总数对不对得上」
     * 把这两条放进了返回值，实测 501 块时它们只占 16~42ms（{@code scratch/g2_bm25_refresh2.out}），
     * 看着无害——但两条都是扫全表，是 O(全库)，留在每份文件都走一次的按份路径里，等于自己给自己
     * 埋回 rebuildAll 那根随库增长的曲线。全库对账交回 {@code rebuildAll}（启动那次）和 {@code /rag/diag}。
     */
    public String refreshSource(String[] sourceKeys) {
        requirePg();
        ensureSchema();
        if (sourceKeys == null || sourceKeys.length == 0) {
            return "BM25 增量刷新跳过：没有给出 source 键";
        }
        long t0 = System.currentTimeMillis();
        String in = placeholders(sourceKeys.length);
        int removedDocs = pg.update("DELETE FROM bm25_docs WHERE source IN " + in, (Object[]) sourceKeys);

        List<Map<String, Object>> rows = pg.queryForList(
                "SELECT id, content, metadata->>'source' AS src, metadata->>'file_name' AS fname, "
                        + "(metadata->>'chunk_index')::int AS ci FROM " + vectorTable()
                        + " WHERE metadata->>'source' IN " + in, (Object[]) sourceKeys);

        long termPairs = 0;
        List<Object[]> docArgs = new ArrayList<>();
        List<Object[]> postingArgs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Bm25RowBuilder.ChunkRow row = Bm25RowBuilder.of(
                    r.get("id"), (String) r.get("src"), (String) r.get("fname"),
                    (Integer) r.get("ci"), (String) r.get("content"));
            docArgs.add(row.docRow());
            postingArgs.addAll(row.postingRows());
            termPairs += row.termFrequencies().size();
        }
        batch("INSERT INTO bm25_docs (id, source, file_name, chunk_index, dl) VALUES ", docArgs, 5);
        batch("INSERT INTO bm25_postings (term, doc_id, tf) VALUES ", postingArgs, 3);

        long ms = System.currentTimeMillis() - t0;
        return "BM25 增量刷新：本次块=" + rows.size() + "（清掉旧行 " + removedDocs
                + "）  倒排条目=" + termPairs + "  写入 posting 行=" + postingArgs.size()
                + "  耗时=" + ms + "ms";
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ", ?");
        return sb.append(')').toString();
    }

    /**
     * 全库对账计数，给 {@code /rag/diag} 这个手动口子用。
     * ⚠️ 下面每一条都是扫全表的 O(全库) 查询，**绝不能挪进 {@code refreshSource}**——
     * 那条是每份文件入库都走一次的按份路径，放进去等于把 #48 省下来的随库增长曲线又埋回去。
     */
    public Map<String, Object> diagnose() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pg == null) {
            m.put("available", false);
            m.put("why", "pgVectorJdbcTemplate 未注入（PG 数据源不可用），BM25 关键词通道整条关闭");
            return m;
        }
        m.put("available", true);
        try {
            ensureSchema();   // 幂等：表没建时顺手建上，diag 才不会把「还没建」报成错误
            m.put("docs", pg.queryForObject("SELECT count(*) FROM bm25_docs", Integer.class));
            m.put("postings", pg.queryForObject("SELECT count(*) FROM bm25_postings", Integer.class));
            m.put("vocab", pg.queryForObject("SELECT count(DISTINCT term) FROM bm25_postings", Integer.class));
            m.put("avgdl", pg.queryForObject("SELECT coalesce(round(avg(dl)::numeric,1),0)::float8 FROM bm25_docs",
                    Double.class));
            m.put("sources", pg.queryForObject("SELECT count(DISTINCT source) FROM bm25_docs", Integer.class));
            m.put("orphan_postings", pg.queryForObject(
                    "SELECT count(*) FROM bm25_postings p WHERE NOT EXISTS "
                            + "(SELECT 1 FROM bm25_docs d WHERE d.id = p.doc_id)", Integer.class));
            m.put("residue_docs_of_missing_files", pg.queryForObject(
                    "SELECT count(*) FROM bm25_docs d WHERE d.source IS NOT NULL AND NOT EXISTS "
                            + "(SELECT 1 FROM " + vectorTable() + " v WHERE v.metadata->>'source' = d.source)",
                    Integer.class));
            m.put("vector_chunks_without_bm25_row", pg.queryForObject(
                    "SELECT count(*) FROM " + vectorTable() + " v WHERE NOT EXISTS "
                            + "(SELECT 1 FROM bm25_docs d WHERE d.id = v.id)", Integer.class));
        } catch (Exception e) {
            m.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return m;
    }

    private void batch(String prefix, List<Object[]> args, int arity) {
        for (int from = 0; from < args.size(); from += INSERT_CHUNK) {
            int to = Math.min(from + INSERT_CHUNK, args.size());
            StringBuilder sql = new StringBuilder(prefix);
            Object[] flat = new Object[(to - from) * arity];
            for (int i = from; i < to; i++) {
                int base = (i - from) * arity;
                System.arraycopy(args.get(i), 0, flat, base, arity);
                sql.append(i == from ? "" : ", ").append('(');
                for (int j = 0; j < arity; j++) sql.append(j == 0 ? "?" : ",?");
                sql.append(')');
            }
            sql.append(" ON CONFLICT DO NOTHING");
            pg.update(sql.toString(), flat);
        }
    }

    /** 关键词检索：查询分词 → 一条 SQL 算完 idf/tf/长度归一 → 按 BM25 分数取前 topN。 */
    public List<Hit> search(String query, int topN) {
        requirePg();
        ensureSchema();
        Map<String, Double> weights = CnTokenizer.queryWeights(query);
        if (weights.isEmpty()) return List.of();

        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            values.append(values.length() == 0 ? "" : ", ").append("(?::text, ?::double precision)");
            args.add(e.getKey());
            args.add(e.getValue());
        }

        String sql = "WITH q(term, qw) AS (VALUES " + values + "), "
                + "n AS (SELECT count(*)::float8 AS ndocs, coalesce(avg(dl),1)::float8 AS avgdl FROM bm25_docs), "
                + "df AS (SELECT p.term, count(*)::float8 AS df FROM bm25_postings p "
                + "        JOIN q ON q.term = p.term GROUP BY p.term), "
                + "sc AS (SELECT p.doc_id, "
                + "  sum( q.qw * (p.tf * (" + K1 + " + 1)) "
                + "        / (p.tf + " + K1 + " * (1 - " + B + " + " + B + " * d.dl / n.avgdl)) "
                + "        * ln(1 + (n.ndocs - df.df + 0.5) / (df.df + 0.5)) ) AS score "
                + "  FROM bm25_postings p "
                + "  JOIN q ON q.term = p.term "
                + "  JOIN df ON df.term = p.term "
                + "  JOIN bm25_docs d ON d.id = p.doc_id "
                + "  CROSS JOIN n "
                + "  GROUP BY p.doc_id) "
                + "SELECT sc.doc_id::text, sc.score, d.file_name, d.source, d.chunk_index, v.content, "
                + "       left(regexp_replace(v.content, '\\s+', ' ', 'g'), 160) AS snippet "
                + "FROM sc JOIN bm25_docs d ON d.id = sc.doc_id "
                + "JOIN " + vectorTable() + " v ON v.id = sc.doc_id "
                + "ORDER BY sc.score DESC LIMIT ?";
        args.add(topN);

        return pg.query(sql, (rs, i) -> new Hit(rs.getString(1), rs.getDouble(2), rs.getString(3),
                rs.getString(4), rs.getObject(5) == null ? null : rs.getInt(5), rs.getString(6), rs.getString(7)),
                args.toArray());
    }

    /** 融合后做相邻块扩展用：按 id 从向量库取回正文和元数据。 */
    public List<Document> chunksByIds(List<String> ids) {
        requirePg();
        if (ids == null || ids.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (String id : ids) {
            in.append(in.length() == 0 ? "" : ", ").append('?');
            args.add(id);
        }
        return pg.query("SELECT id::text AS id, content, metadata::text AS meta FROM " + vectorTable()
                        + " WHERE id::text IN (" + in + ")",
                (rs, i) -> new Document(rs.getString("id"), rs.getString("content"), readMeta(rs.getString("meta"))),
                args.toArray());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /** 相邻块扩展：按 (source, chunk_index) 成对取回同一份文档里紧挨着的块。 */
    public List<Document> neighborChunks(List<String[]> sourceAndChunkIndex) {
        requirePg();
        if (sourceAndChunkIndex == null || sourceAndChunkIndex.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        StringBuilder values = new StringBuilder();
        for (String[] pair : sourceAndChunkIndex) {
            values.append(values.length() == 0 ? "" : ", ").append("(?::text, ?::int)");
            args.add(pair[0]);
            args.add(pair[1]);
        }
        return pg.query("SELECT id::text AS id, content, metadata::text AS meta FROM " + vectorTable()
                        + " WHERE (metadata->>'source', (metadata->>'chunk_index')::int) IN (VALUES " + values + ")",
                (rs, i) -> new Document(rs.getString("id"), rs.getString("content"), readMeta(rs.getString("meta"))),
                args.toArray());
    }

    /** 库里的一块正文：引用溯源时按块号回读原文用。 */
    public record StoredChunk(String source, String fileName, Integer chunkIndex, String content) {}

    /**
     * 按「文件名 + chunk_index」从 vector_store 取回原文块。只读，不碰倒排表。
     * <p>
     * 引用列表里给了块号却读不到那一块，溯源只做了一半，所以这个口子跟着 {@code /rag/chunk} 一起给。
     * 放在本类是因为它已经是「用 pgVectorJdbcTemplate 直查 vector_store」的地方（neighborChunks、locate 同理），
     * 换到别处就得再抄一遍可选数据源的接法。
     */
    public List<StoredChunk> readChunk(String fileName, int chunkIndex, int limit) {
        requirePg();
        // source 在库里是带盘符的路径（实测 D:\ai-docs\02-WHO成人高血压药物治疗指南2021.pdf），
        // 先把反斜杠统一成正斜杠再按 basename 兜底匹配，chr(92) 是为避免 SQL 字面量里的转义歧义。
        String likeName = fileName.replace("%", "\\%").replace("_", "\\_");
        String sql = "SELECT metadata->>'source' AS src,"
                + " coalesce(metadata->>'file_name', metadata->>'source') AS fname,"
                + " (metadata->>'chunk_index')::int AS ci, content FROM " + vectorTable()
                + " WHERE (metadata->>'chunk_index')::int = ?"
                + "   AND ( coalesce(metadata->>'file_name', metadata->>'source') = ?"
                + "      OR metadata->>'source' = ?"
                + "      OR replace(metadata->>'source', chr(92), '/') LIKE ? )"
                + " ORDER BY fname, ci LIMIT ?";
        return pg.query(sql, (rs, i) -> new StoredChunk(rs.getString(1), rs.getString(2),
                        rs.getObject(3) == null ? null : rs.getInt(3), rs.getString(4)),
                chunkIndex, fileName, fileName, "%/" + likeName, Math.max(1, limit));
    }

    /** 某个词在库里出现了多少次（判断"库里到底有没有这个词"，用来解释召回失败）。 */
    public int documentFrequency(String term) {
        requirePg();
        ensureSchema();
        Integer df = pg.queryForObject("SELECT count(*) FROM bm25_postings WHERE term = ?",
                Integer.class, CnTokenizer.normalize(term));
        return df == null ? 0 : df;
    }

    /** 一段命中正文的定位结果：文件名 + 块号 + 命中点附近原文，用来人工确认答案块。 */
    public record Located(String fileName, Integer chunkIndex, Integer matchAt, String excerpt) {}

    /**
     * 逐字定位：某句话原文落在哪份文件的第几块。这是给测试集做「答案块」标注的尺子——
     * 只有先知道答案在第几块，才能算出检索到底有没有把那块捞进 top-K。
     * PDF 抽取会在中文字之间塞空格，所以先把正文压成无空白再比，否则逐字查会假阴性。
     */
    public List<Located> locate(String phrase, int limit) {
        requirePg();
        String needle = flat(phrase);
        if (needle.isEmpty()) return List.of();
        String sql = "SELECT fname, ci, pos, substr(flat, greatest(1, pos - 140), 360) AS excerpt FROM ("
                + "  SELECT coalesce(metadata->>'file_name', metadata->>'source') AS fname,"
                + "         (metadata->>'chunk_index')::int AS ci,"
                + "         regexp_replace(content, '\\s+', '', 'g') AS flat,"
                + "         position(? in regexp_replace(content, '\\s+', '', 'g')) AS pos"
                + "  FROM " + vectorTable()
                + "  WHERE regexp_replace(content, '\\s+', '', 'g') LIKE '%' || ? || '%'"
                + ") t ORDER BY fname, ci LIMIT ?";
        return pg.query(sql, (rs, i) -> new Located(rs.getString(1),
                        rs.getObject(2) == null ? null : rs.getInt(2),
                        rs.getObject(3) == null ? null : rs.getInt(3), rs.getString(4)),
                needle, needle, limit);
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private void requirePg() {
        if (pg == null) {
            throw new IllegalStateException("BM25 需要 app.pgvector.enabled=true（SimpleVectorStore 模式没有可建的倒排表）");
        }
    }
}
