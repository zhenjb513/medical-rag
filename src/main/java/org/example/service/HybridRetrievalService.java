package org.example.service;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量 + BM25 混合检索。
 * <p>
 * 为什么不能用分数直接相加：向量分是 0~1 的余弦，BM25 分是 5~60 的无上限量，
 * 两边阈值也完全不同（实测域外问题在向量侧 0.39~0.66、在 BM25 侧照样能拿 20 分）。
 * 所以只比较「名次」——RRF：score = Σ 1/(rrfK + rank)，谁排得前谁进，量纲不参与。
 * <p>
 * mode=vector 时整条链路直接委托给原来的向量检索，行为与改造前一致。
 * <p>
 * mode=bm25 只是评测通道，生产入口（/chat、/evaluation/retrieve）拿不到它：可答性闸门要的是
 * 向量侧的余弦，纯关键词命中没有 score 可判（实测域外问题在 BM25 侧照样能拿 20 分，
 * 用关键词覆盖率替代也分不开——火星题 60% vs 口语题 25~50%，见 scratch/termcoverage.out）。
 */
@Service
public class HybridRetrievalService {

    private final RagReferenceService ragReferenceService;
    private final Bm25IndexService bm25IndexService;
    private final RerankService rerankService;

    /** app.retrieval.mode 的原始值；实际执行哪个模式看 effectiveMode()（配成 bm25 会降级成 hybrid） */
    @Value("${app.retrieval.mode:vector}")
    private String mode = "vector";

    /**
     * RRF 平滑系数：分数 = 1/(rrfK + 名次)。k 越大越"民主"（只看是否两边都在），
     * k 越小越"独裁"（任一路的第一名就能定序）。默认 60 是论文常规值，
     * 但实测 k=60 会把 BM25 的第一名挤下去，而本库里 BM25 的 top1 恰恰最准。
     */
    @Value("${app.retrieval.rrf-k:60}")
    private int rrfK = 60;

    /**
     * 两路在融合里的话语权。实测口语化提问下 BM25 答案块命中@1 只有 10.3%（向量 31.0%），
     * 等权融合会把向量排好的中段挤下去（前 4 名 65.5%→48.3%），所以关键词这路要降权。
     */
    @Value("${app.retrieval.vector-weight:1.0}")
    private double vectorWeight = 1.0;

    @Value("${app.retrieval.bm25-weight:1.0}")
    private double bm25Weight = 1.0;

    /** 每一路先取多少条参与融合 */
    @Value("${app.retrieval.probe-size:20}")
    private int probeSize = 20;

    /** 向量侧下限：只挡真正无关的（实测 Java/电影类问题 top1 只有 0.39~0.44） */
    @Value("${app.retrieval.vector-floor:0.45}")
    private double vectorFloor = 0.45;

    @Value("${app.retrieval.expand-neighbors:true}")
    private boolean expandNeighbors = true;

    /** 扩展后最多给模型多少块，防止上下文被撑爆 */
    @Value("${app.retrieval.max-context-chunks:16}")
    private int maxContextChunks = 16;

    /** 生产链路是否启用重排；评测用 mode=rerank 单独打开，两者互不干扰 */
    @Value("${app.retrieval.rerank-enabled:false}")
    private boolean rerankEnabled = false;

    /**
     * 重排那一步用不用扩展后的查询串。
     * <p>
     * false（默认）= 只把患者原话送给重排模型，扩展术语只影响召回，行为与加这行配置之前完全一致。
     * true = 重排也看见补进去的指南术语，目的是让「词面上对不上的正确答案」能被交叉编码器认出来
     * （实测形态：口语题「我血糖偏高」的答案块《中国血脂管理指南2023年》#92，标题是「糖尿病患者血脂目标值推荐」，
     * 融合池第 14 名，用原话重排后仍进不了前 8，见 scratch/g12_run.json）。
     */
    @Value("${app.retrieval.rerank-use-expanded-query:false}")
    private boolean rerankUseExpandedQuery = false;

    /** 重排前先从融合池里取多少块。重排只能在这个池子里换座位，池子太小它再准也救不回来。 */
    @Value("${app.retrieval.rerank-window:20}")
    private int rerankWindow = 20;

    /**
     * 可答性闸门：只看「最像的那一段」够不够像，不够就整批当没资料，让模型走「无法回答」那条路。
     * <p>
     * 为什么不沿用老的「每条低于 0.65 就丢掉」：实测 58 题里，那道逐条阈值会把「每日食盐摄入量」
     * 这种正确题的 20 条候选砍到只剩 2 条，却照样放行库里确实没答案的题（「火星上有没有心血管指南」
     * top1=0.660）。改成对第一名做整体判断后：正常题 top1 余弦 0.614~0.881，
     * 库里没答案的题只有 0.388~0.568，一条阈值就能分开，而且不再破坏候选池。
     * 唯一漏网的是火星题（0.660），那是任何检索分数都分不开的，只能靠生成期校验。
     */
    @Value("${app.retrieval.answer-floor:0.60}")
    private double answerFloor = 0.60;

    public HybridRetrievalService(RagReferenceService ragReferenceService, Bm25IndexService bm25IndexService,
                                  RerankService rerankService) {
        this.ragReferenceService = ragReferenceService;
        this.bm25IndexService = bm25IndexService;
        this.rerankService = rerankService;
    }

    /** 配置里的原始模式（app.retrieval.mode）。生产实际执行哪个模式看 {@link #effectiveMode()}。 */
    public String getMode() { return mode == null ? "vector" : mode; }

    /** 本次真正会执行的模式：配置成 bm25 时这里是 hybrid。 */
    public String effectiveMode() {
        return enforceGateCapable(getMode(), bm25IndexService.isAvailable());
    }

    /** 打印用：配置值与实际执行值不一致时两个都写出来，别让日志看着像生产在跑纯关键词。 */
    public String describeMode() {
        String raw = getMode();
        String eff = effectiveMode();
        return raw.equalsIgnoreCase(eff) ? eff : eff + "（配置=" + raw + "，已降级）";
    }

    @PostConstruct
    void warnUngatedModeConfig() {
        if (getMode().equalsIgnoreCase("bm25")) {
            System.out.println("[检索模式] ⚠️ app.retrieval.mode=bm25 已降级为 " + effectiveMode()
                    + "：纯关键词通道没有余弦，可答性闸门判不了，生产问答不允许只走关键词。"
                    + "需要 bm25 单边对照请用零调用口子 /evaluation/recall?mode=bm25。");
        }
    }

    /**
     * 把「会被绕过闸门」的模式配置折算成实际可执行的模式（纯函数，单测直接查这张表）。
     * <p>
     * bm25 → hybrid：BM25 命中不带 score，hasEvidence() 在它上面恒等于「全放行」；
     * BM25 索引不可用（没 PG）且配了非 vector → vector，跟改造前一样的退路。
     */
    static String enforceGateCapable(String configured, boolean bm25IndexAvailable) {
        String m = configured == null ? "vector" : configured.toLowerCase();
        if (!bm25IndexAvailable && !m.equals("vector")) return "vector";
        if (m.equals("bm25")) return "hybrid";
        return m;
    }

    /**
     * @param threshold 向量通道的相似度下限，传 null 时用 app.retrieval.vector-floor
     */
    public List<Document> search(String query, int topK, Double threshold) {
        String m = enforceGateCapable(getMode(), bm25IndexService.isAvailable());
        switch (m) {
            case "hybrid" -> {
                return hybrid(query, topK, true, rerankEnabled);
            }
            default -> {
                List<Document> v = ragReferenceService.search(retrievalQuery(query), topK,
                        threshold == null ? vectorFloor : threshold);
                return hasEvidence(v) ? v : List.of();
            }
        }
    }

    /** @param expand 是否把命中块的前后邻居块补进上下文 */
    public List<Document> searchFused(String query, int topK, boolean expand) {
        return hybrid(query, topK, expand, false);
    }

    /** 融合 + 重排，强制开启重排（评测对比用，不看 rerank-enabled） */
    public List<Document> searchReranked(String query, int topK, boolean expand) {
        return hybrid(query, topK, expand, true);
    }

    public double getAnswerFloor() { return answerFloor; }

    /** 重排实际用哪个查询串（受 rerank-use-expanded-query 支配）。评测要对同一批候选单独调重排，得跟线上用同一个口径。 */
    public String rerankQuery(String query) {
        return rerankUseExpandedQuery ? QueryExpander.expand(query) : query;
    }

    public boolean isRerankUseExpandedQuery() { return rerankUseExpandedQuery; }

    /** 第一名都不够像（或压根没召回到）就判定为「这次没有资料」，只判一次，不逐条砍。 */
    private boolean hasEvidence(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) return false;
        Double top = vectorDocs.get(0).getScore();
        return top != null && top >= answerFloor;
    }

    private List<Document> hybrid(String query, int topK, boolean expand, boolean rerank) {
        int poolSize = rerank ? Math.max(topK, rerankWindow) : topK;
        int perChannel = rerank ? Math.max(probeSize, poolSize) : probeSize;
        String recallQuery = retrievalQuery(query);
        List<Document> vectorDocs = ragReferenceService.search(recallQuery, perChannel, vectorFloor);
        if (!hasEvidence(vectorDocs)) {
            System.out.println("[检索闸门] 最像的一条也不够像（阈值 " + String.format("%.2f", answerFloor)
                    + "），本次不提供知识库上下文：" + query);
            return List.of();
        }
        List<Document> fused = fuse(vectorDocs,
                fromHits(bm25IndexService.search(recallQuery, perChannel)), poolSize);
        String rerankQuery = rerankQuery(query);
        if (rerank && !rerankQuery.equals(query)) {
            System.out.println("[重排查询] 本次送重排的是扩展串（rerank-use-expanded-query=true）：" + rerankQuery);
        }
        if (rerank) fused = take(rerankService.rerank(rerankQuery, fused), topK);
        return expand ? expand(fused) : fused;
    }

    /**
     * 融合出 poolSize 条候选但不重排、也**不走可答性闸门**。
     * 评测要靠它把「同一批候选，重排前后」对比出来，闸门一挡就没得比了。
     */
    public List<Document> fuseCandidates(String query, int poolSize) {
        int perChannel = Math.max(probeSize, poolSize);
        String recallQuery = retrievalQuery(query);
        return fuse(ragReferenceService.search(recallQuery, perChannel, vectorFloor),
                fromHits(bm25IndexService.search(recallQuery, perChannel)), poolSize);
    }

    /** 命中口语措辞时给召回通道补指南术语（详见 QueryExpander），没命中就原样返回、不打日志。 */
    private static String retrievalQuery(String query) {
        List<String> extra = QueryExpander.addedTerms(query);
        if (extra.isEmpty()) return query;
        System.out.println("[查询扩展] 补充术语: " + String.join(" ", extra) + "  原问句: " + query);
        return QueryExpander.expand(query);
    }

    private static List<Document> take(List<Document> docs, int limit) {
        return docs.size() <= limit ? docs : new ArrayList<>(docs.subList(0, limit));
    }

    private List<Document> fuse(List<Document> vectorDocs, List<Document> bm25Docs, int topK) {
        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();
        rankInto(rrf, byId, vectorDocs, true, vectorWeight);
        rankInto(rrf, byId, bm25Docs, false, bm25Weight);

        return rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void rankInto(Map<String, Double> rrf, Map<String, Document> byId,
                          List<Document> docs, boolean prefer, double weight) {
        int rank = 0;
        for (Document d : docs) {
            rank++;
            String id = d.getId();
            if (id == null) continue;
            rrf.merge(id, weight / (rrfK + rank), Double::sum);
            // 向量侧的 Document 带 score 和完整 metadata，能覆盖就优先留它的
            if (prefer || !byId.containsKey(id)) byId.put(id, d);
        }
    }

    private List<Document> fromHits(List<Bm25IndexService.Hit> hits) {
        List<Document> out = new ArrayList<>();
        for (Bm25IndexService.Hit h : hits) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", h.source() == null ? "" : h.source());
            meta.put("file_name", h.fileName() == null ? "" : h.fileName());
            if (h.chunkIndex() != null) meta.put("chunk_index", h.chunkIndex());
            out.add(new Document(h.id(), h.content(), meta));
        }
        return out;
    }

    /**
     * 相邻块扩展：指南里「140/90」这类数值常在表格里被切开，命中块本身句子不完整。
     * 命中块的前后各一块补进上下文，总块数不超过 maxContextChunks，名额不够时按命中块名次优先。
     */
    private List<Document> expand(List<Document> selected) {
        if (!expandNeighbors || selected.isEmpty()) return selected;
        int budget = Math.max(0, maxContextChunks - selected.size());
        if (budget == 0) return selected;

        Map<String, Document> have = new LinkedHashMap<>();
        List<String[]> pairs = new ArrayList<>();
        for (Document d : selected) {
            have.put(keyOf(d), d);
            Object src = d.getMetadata().get("source");
            Object ci = d.getMetadata().get("chunk_index");
            if (src == null || !(ci instanceof Number n)) continue;
            for (int delta : new int[]{-1, 1}) {
                pairs.add(new String[]{String.valueOf(src), String.valueOf(n.intValue() + delta)});
            }
        }
        pairs.removeIf(p -> have.containsKey(p[0] + "#" + p[1]));
        if (pairs.isEmpty()) return selected;

        // neighborChunks 的 SQL 没有 ORDER BY，回来的顺序是数据库自己的行顺序。名额只有
        // maxContextChunks-selected.size() 个时，按返回顺序截断会出现「排名靠后的父块把邻居占满、
        // 第 1 名的邻居反而落选」——实测见 scratch/e2e_ctx.txt：TIA 题第 1 名 XZ#97 的邻居 #96/#99
        // 都没进，而第 8 名 XZ#55 的邻居 #54/#56 两块都进了。
        // pairs 是严格按 selected 的名次生成的，所以改成按 pairs 的顺序取，让高名次优先占名额。
        Map<String, Document> pending = new LinkedHashMap<>();
        for (Document nb : bm25IndexService.neighborChunks(pairs)) {
            pending.putIfAbsent(keyOf(nb), nb);
        }
        List<Document> out = new ArrayList<>(selected);
        for (String[] p : pairs) {
            if (out.size() >= maxContextChunks) break;
            Document nb = pending.get(p[0] + "#" + p[1]);
            if (nb == null || have.containsKey(keyOf(nb))) continue;
            have.put(keyOf(nb), nb);
            out.add(nb);
        }
        return out;
    }

    private static String keyOf(Document d) {
        Object src = d.getMetadata() == null ? null : d.getMetadata().get("source");
        Object ci = d.getMetadata() == null ? null : d.getMetadata().get("chunk_index");
        if (src == null) src = d.getMetadata() == null ? null : d.getMetadata().get("file_name");
        return src + "#" + ci;
    }
}
