package org.ragai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ragai.config.PgVectorConfigProperties;
import dev.langchain4j.data.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 关键词召回：把向量库表里每个 chunk 分词后写进自建倒排表，检索时在 PG 里直接算 Okapi BM25。
 * <p>
 * 只存 (id, dl) 不存正文——正文仍然只在向量表一份，避免两处文本不同步；
 * bm25_postings 靠外键级联删除，向量块被删掉时倒排也一起没了。
 * <p>
 * ⚠️ 本分支（langchain4j-exp）的向量库是 {@code PgVectorEmbeddingStore}，落到
 * {@code lc_document_embeddings} 表，schema 与 Spring AI 的 {@code PgVectorStore}（{@code vector_store}）
 * 完全不同：主键叫 {@code embedding_id}、正文列叫 {@code text}、块号写在 metadata 的 {@code index} 键上。
 * 下面所有读向量表的 SQL 都必须按这套列名写，照抄 spring-ai 分支的 {@code id/content/chunk_index}
 * 会直接报「列不存在」。
 * <p>
 * ⚠️ {@code bm25_docs} / {@code bm25_postings} 的表名是**硬编码**的，两个分支建的是同一张物理表
 * （{@code CREATE TABLE IF NOT EXISTS} 会命中已存在的那张）。两分支只有「读哪张向量表」不同，
 * 写进去的行结构完全一致（都 alias 成 {@code id/content/ci}），所以同一张表两边都能写。
 * 但 {@code rebuildAll()} 开头是无条件 {@code TRUNCATE}，因此**表内容由最后启动的那个分支独占**：
 * 谁最后重建，表里就是谁的块。两分支若同时连同一个 PG 库，后启动者会把先启动者的索引清掉，
 * 先启动那台的 BM25 检索会因 uuid 对不上而查不到结果。正常部署是两分支各自独立库，不会触发。
 */
@Service
public class Bm25IndexService {

    private static final Logger log = LoggerFactory.getLogger(Bm25IndexService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final int INSERT_CHUNK = 400;

    private final JdbcTemplate pg;
    private final PgVectorConfigProperties props;

    /**
     * 本分支向量表名与列名。不能直接用 {@code props.getTableName()}——那是 spring-ai 分支的
     * {@code vector_store}；LangChain4j 侧建的是 {@code lc_document_embeddings}，且列名/块号键都不同。
     */
    @Value("${app.pgvector.lc-table-name:lc_document_embeddings}")
    private String lcTableName = "lc_document_embeddings";

    /** 主键列：LangChain4j PgVectorEmbeddingStore 叫 embedding_id。 */
    private static final String ID_COL = "embedding_id";
    /** 正文列：LangChain4j 叫 text（Spring AI 叫 content）。 */
    private static final String TEXT_COL = "text";
    /** 块号所在的 metadata 键：LangChain4j 写 index（Spring AI 写 chunk_index）。 */
    private static final String CHUNK_KEY = "index";

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
        String schema = props.getSchemaName() == null || props.getSchemaName().isBlank()
                ? "public" : props.getSchemaName();
        String table = lcTableName == null || lcTableName.isBlank() ? "lc_document_embeddings" : lcTableName;
        String full = schema + "." + table;
        return full.matches("[A-Za-z0-9_.]+") ? full : "public.lc_document_embeddings";
    }

    /** 向量表里取块号的表达式（统一走 metadata 的 index 键）。 */
    private static String chunkIndexExpr() {
        return "(metadata->>'" + CHUNK_KEY + "')::int";
    }

    /**
     * 从向量表取一份 chunk 所需的四个字段：主键、正文、source、file_name、块号。
     * 列名按 LangChain4j 侧命名，但统一 alias 成 {@code id/content/ci}，
     * 让下游的行构造（{@code Bm25RowBuilder}）与 spring-ai 分支保持同一套键，不必分叉。
     */
    private String vectorSelect() {
        return "SELECT " + ID_COL + " AS id, " + TEXT_COL + " AS content,"
                + " metadata->>'source' AS src, metadata->>'file_name' AS fname,"
                + " " + chunkIndexExpr() + " AS ci FROM " + vectorTable();
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

    /**
     * 全量重建 BM25 倒排索引：清表 → 读向量表 → 分词 → 写倒排。
     *
     * <p>调用时机：应用启动（{@code RagInitializer.init()} 末尾）、RabbitMQ 入库任务、
     * 以及手工触发的管理接口。单份文件变更走 {@link #refreshSource(String[])}，不要走这里。
     *
     * <p>复杂度是 O(全库块数)：TRUNCATE 后把每一块重新分词、重新写两张表。
     * 这是入库链路里唯一随库增长的操作，所以按份刷新的热路径刻意绕开它。
     *
     * <p>返回一段人类可读的统计（块数/倒排条目/词表/平均块长/耗时），直接用于日志和诊断接口回显。
     */
    @CacheEvict(cacheNames = "bm25Search", allEntries = true)
    public String rebuildAll() {
        requirePg();
        ensureSchema();
        long t0 = System.currentTimeMillis();
        // CASCADE 是必须的：bm25_postings.doc_id 有外键指向 bm25_docs，
        // 级联删除让这一句同时清空倒排表，不必再单独 DELETE postings
        pg.execute("TRUNCATE bm25_docs CASCADE");

        // 向量库是唯一真相源：索引内容永远由它派生，不单独维护一份原文
        List<Map<String, Object>> rows = pg.queryForList(vectorSelect());

        long termPairs = 0;
        List<Object[]> docArgs = new ArrayList<>();
        List<Object[]> postingArgs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            // 行构造只此一份实现：增量刷新走的是同一个方法，两条路算出的倒排行必须逐字节相同
            Bm25RowBuilder.ChunkRow row = Bm25RowBuilder.of(
                    r.get("id"), (String) r.get("src"), (String) r.get("fname"),
                    (Integer) r.get("ci"), (String) r.get("content"));
            // 一块正文派生两类行：doc 行（含 dl，供 avgdl 归一化）+ 若干 posting 行（term/tf）
            docArgs.add(row.docRow());
            postingArgs.addAll(row.postingRows());
            // termFrequencies 的条目数即「(词, 块) 对」，与 posting 行数一致，用内存值免掉一次全表 count
            termPairs += row.termFrequencies().size();
        }

        // 两张表分两批写：每批最多 INSERT_CHUNK 行拼成一条多值 INSERT，把 961 块从 961 次往返压到几次
        batch("INSERT INTO bm25_docs (id, source, file_name, chunk_index, dl) VALUES ", docArgs, 5);
        batch("INSERT INTO bm25_postings (term, doc_id, tf) VALUES ", postingArgs, 3);

        long ms = System.currentTimeMillis() - t0;
        // 三条全表统计只在重建路径上跑（rebuildAll 本身已是 O(全库)），不影响热路径；
        // 全库对账也就靠这里，refreshSource 刻意不做同类统计
        Integer docs = pg.queryForObject("SELECT count(*) FROM bm25_docs", Integer.class);
        Integer vocab = pg.queryForObject("SELECT count(DISTINCT term) FROM bm25_postings", Integer.class);
        // avgdl 供 BM25 的长度归一化分母用，空表时 coalesce 兜底成 0，避免除零
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
    @CacheEvict(cacheNames = "bm25Search", allEntries = true)
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
                vectorSelect() + " WHERE metadata->>'source' IN " + in, (Object[]) sourceKeys);

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

    public int deleteSource(String[] sourceKeys) {
        if (pg == null || sourceKeys == null || sourceKeys.length == 0) return 0;
        try {
            return pg.update("DELETE FROM bm25_docs WHERE source IN " + placeholders(sourceKeys.length),
                    (Object[]) sourceKeys);
        } catch (Exception ex) {
            log.warn("BM25 删除 source 异常：{}", ex.getMessage());
            return -1;
        }
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
                            + "(SELECT 1 FROM bm25_docs d WHERE d.id = v." + ID_COL + ")", Integer.class));
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
    @Cacheable(cacheNames = "bm25Search", key = "#query + '_' + #topN")
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
                + "SELECT sc.doc_id::text, sc.score, d.file_name, d.source, d.chunk_index, v." + TEXT_COL + " AS content, "
                + "       left(regexp_replace(v." + TEXT_COL + ", '\\s+', ' ', 'g'), 160) AS snippet "
                + "FROM sc JOIN bm25_docs d ON d.id = sc.doc_id "
                + "JOIN " + vectorTable() + " v ON v." + ID_COL + " = sc.doc_id "
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
        return pg.query("SELECT " + ID_COL + "::text AS id, " + TEXT_COL + " AS content, metadata::text AS meta "
                        + "FROM " + vectorTable() + " WHERE " + ID_COL + "::text IN (" + in + ")",
                (rs, i) -> Document.from(rs.getString("content"), readMeta(rs.getString("meta"))),
                args.toArray());
    }

    /**
     * 把 PG 里的 metadata JSON 读成 LangChain4j 的 Metadata（值统一按字符串存，与 Spring AI 侧行为一致）。
     * <p>
     * 这里顺手把 LangChain4j 的 {@code index} 键补成 {@code chunk_index}：下游相邻块扩展
     * （{@code HybridRetrievalService.expand()} 的 {@code keyOf()}）按 {@code chunk_index} 拼
     * 「source#块号」去配对，直接拿原始 metadata 会因为键名不同配不上，邻居块全被静默丢掉。
     */
    private dev.langchain4j.data.document.Metadata readMeta(String json) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        if (json == null || json.isBlank()) return metadata;
        try {
            Map<String, Object> raw = new ObjectMapper().readValue(json, Map.class);
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() != null) {
                    metadata.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            Object index = raw.get(CHUNK_KEY);
            if (index != null && !raw.containsKey("chunk_index")) {
                metadata.put("chunk_index", String.valueOf(index));
            }
        } catch (Exception ex) {
            // 解析失败返回空 Metadata，与迁移前返回空 Map 的语义一致
        }
        return metadata;
    }

    /** 相邻块扩展：按 (source, 块号) 成对取回同一份文档里紧挨着的块。 */
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
        return pg.query("SELECT " + ID_COL + "::text AS id, " + TEXT_COL + " AS content, metadata::text AS meta "
                        + "FROM " + vectorTable()
                        + " WHERE (metadata->>'source', " + chunkIndexExpr() + ") IN (VALUES " + values + ")",
                (rs, i) -> Document.from(rs.getString("content"), readMeta(rs.getString("meta"))),
                args.toArray());
    }

    /** 库里的一块正文：引用溯源时按块号回读原文用。 */
    public record StoredChunk(String source, String fileName, Integer chunkIndex, String content) {}

    /**
     * 按「文件名 + 块号」从向量表取回原文块。只读，不碰倒排表。
     * <p>
     * 引用列表里给了块号却读不到那一块，溯源只做了一半，所以这个口子跟着 {@code /rag/chunk} 一起给。
     * 放在本类是因为它已经是「用 pgVectorJdbcTemplate 直查向量表」的地方（neighborChunks、locate 同理），
     * 换到别处就得再抄一遍可选数据源的接法。
     */
    public List<StoredChunk> readChunk(String fileName, int chunkIndex, int limit) {
        requirePg();
        // source 在库里是带盘符的路径（实测 D:\ai-docs\02-WHO成人高血压药物治疗指南2021.pdf），
        // 先把反斜杠统一成正斜杠再按 basename 兜底匹配，chr(92) 是为避免 SQL 字面量里的转义歧义。
        String likeName = fileName.replace("%", "\\%").replace("_", "\\_");
        String sql = "SELECT metadata->>'source' AS src,"
                + " coalesce(metadata->>'file_name', metadata->>'source') AS fname,"
                + " " + chunkIndexExpr() + " AS ci, " + TEXT_COL + " AS content FROM " + vectorTable()
                + " WHERE " + chunkIndexExpr() + " = ?"
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
                + "         " + chunkIndexExpr() + " AS ci,"
                + "         regexp_replace(" + TEXT_COL + ", '\\s+', '', 'g') AS flat,"
                + "         position(? in regexp_replace(" + TEXT_COL + ", '\\s+', '', 'g')) AS pos"
                + "  FROM " + vectorTable()
                + "  WHERE regexp_replace(" + TEXT_COL + ", '\\s+', '', 'g') LIKE '%' || ? || '%'"
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
