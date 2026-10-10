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
            Object id = r.get("id");
            String content = (String) r.get("content");
            List<String> terms = CnTokenizer.indexTerms(content);
            Map<String, Integer> tf = new LinkedHashMap<>();
            for (String t : terms) tf.merge(t, 1, Integer::sum);
            docArgs.add(new Object[]{id, r.get("src"), r.get("fname"), r.get("ci"), terms.size()});
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postingArgs.add(new Object[]{e.getKey(), id, e.getValue()});
            }
            termPairs += tf.size();
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
