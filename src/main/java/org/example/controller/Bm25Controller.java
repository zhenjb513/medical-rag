package org.example.controller;

import org.example.service.Bm25IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BM25 倒排的运维口子：重建 + 试查。
 * 重建放在显式接口里而不是启动自动跑，避免每次启动都 TRUNCATE。
 */
@RestController
@RequestMapping("/bm25")
public class Bm25Controller {

    private final Bm25IndexService bm25;

    public Bm25Controller(Bm25IndexService bm25) {
        this.bm25 = bm25;
    }

    @GetMapping(value = "/rebuild", produces = "text/plain; charset=utf-8")
    public String rebuild() {
        return bm25.rebuildAll();
    }

    @GetMapping(value = "/search", produces = "text/plain; charset=utf-8")
    public String search(@RequestParam String q, @RequestParam(defaultValue = "8") int topN) {
        StringBuilder sb = new StringBuilder("查询: " + q + "\n");
        var hits = bm25.search(q, topN);
        sb.append("命中: ").append(hits.size()).append("\n");
        int i = 0;
        for (Bm25IndexService.Hit h : hits) {
            sb.append("  #").append(++i).append("  bm25=").append(String.format("%.3f", h.score()))
                    .append("  chunk_index=").append(h.chunkIndex())
                    .append("  file=").append(h.fileName()).append('\n')
                    .append("      ").append(h.snippet()).append('\n');
        }
        return sb.toString();
    }

    /** 库里到底有没有这个词：df=0 说明不是检索器的问题，是语料里就没这个写法。 */
    @GetMapping(value = "/df", produces = "text/plain; charset=utf-8")
    public String df(@RequestParam String term) {
        return "term=" + term + "  df=" + bm25.documentFrequency(term);
    }

    /** 逐字定位答案块：给测试集标注 ground truth 用，纯查询不改库。 */
    @GetMapping(value = "/locate", produces = "text/plain; charset=utf-8")
    public String locate(@RequestParam String phrase, @RequestParam(defaultValue = "10") int limit) {
        var hits = bm25.locate(phrase, limit);
        StringBuilder sb = new StringBuilder("原文逐字定位: ").append(phrase)
                .append("  命中块数=").append(hits.size()).append('\n');
        for (Bm25IndexService.Located l : hits) {
            sb.append("  ").append(l.fileName()).append("#").append(l.chunkIndex())
                    .append("  @").append(l.matchAt()).append('\n')
                    .append("      ").append(l.excerpt()).append('\n');
        }
        return sb.toString();
    }
}
