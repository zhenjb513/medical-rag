package org.ragai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 倒排行的构造：一块正文进、(doc 行 + posting 行) 出，不碰数据库。
 * <p>
 * 单独抽出来只为一件事——全量重建 {@code rebuildAll()} 和按份增量刷新必须算出**逐字节相同**的行。
 * 这两条路各写一遍分词逻辑，早晚会漂移到「同一块正文在全量重建后分数变了」，那种 bug 只能靠重灌库复现，最难查。
 */
final class Bm25RowBuilder {

    /**
     * 一块的倒排原料。docId 保持读库读回来的原类型（vector_store.id 是 uuid，
     * JDBC 给的是 UUID 对象；换成字符串会让 uuid 列的绑定报「类型不匹配」），
     * posting 里每个 (term, tf) 都指向同一个 docId。
     */
    record ChunkRow(Object docId, String source, String fileName, Integer chunkIndex, int dl,
                    Map<String, Integer> termFrequencies) {

        /** 写 bm25_docs 的一行，列序与 {@code INSERT INTO bm25_docs (id, source, file_name, chunk_index, dl)} 对齐。 */
        Object[] docRow() {
            return new Object[]{docId, source, fileName, chunkIndex, dl};
        }

        /** 写 bm25_postings 的行，列序与 {@code INSERT INTO bm25_postings (term, doc_id, tf)} 对齐。 */
        List<Object[]> postingRows() {
            List<Object[]> out = new ArrayList<>(termFrequencies.size());
            for (Map.Entry<String, Integer> e : termFrequencies.entrySet()) {
                out.add(new Object[]{e.getKey(), docId, e.getValue()});
            }
            return out;
        }
    }

    private Bm25RowBuilder() {
    }

    /**
     * {@code content} 为 null 或全是不进索引的符号时得到 dl=0、零 posting——
     * 这种块仍然要落 doc 行，否则 BM25 的 ndocs/avgdl 会和向量库块数不一致，idf 整体偏移。
     */
    static ChunkRow of(Object docId, String source, String fileName, Integer chunkIndex, String content) {
        List<String> terms = CnTokenizer.indexTerms(content == null ? "" : content);
        Map<String, Integer> tf = new LinkedHashMap<>();
        for (String t : terms) {
            tf.merge(t, 1, Integer::sum);
        }
        return new ChunkRow(docId, source, fileName, chunkIndex, terms.size(), tf);
    }
}
