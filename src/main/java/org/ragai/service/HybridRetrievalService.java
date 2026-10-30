package org.ragai.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final RagReferenceService ragReferenceService;
    private final Bm25IndexService bm25IndexService;
    private final RerankService rerankService;
    private final Executor ragExecutor;

    /**
     * app.retrieval.mode 的原始值；实际执行哪个通道看 {@link #executedMode(String, boolean)}（配成 bm25 会降级成 hybrid）
     */
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

    /**
     * 每一路先取多少条参与融合
     */
    @Value("${app.retrieval.probe-size:20}")
    private int probeSize = 20;

    /**
     * 向量侧下限：只挡真正无关的（实测 Java/电影类问题 top1 只有 0.39~0.44）
     */
    @Value("${app.retrieval.vector-floor:0.45}")
    private double vectorFloor = 0.45;

    @Value("${app.retrieval.expand-neighbors:true}")
    private boolean expandNeighbors = true;

    /**
     * 扩展后最多给模型多少块，防止上下文被撑爆
     */
    @Value("${app.retrieval.max-context-chunks:16}")
    private int maxContextChunks = 16;

    /**
     * 生产链路是否启用重排；评测用 mode=rerank 单独打开，两者互不干扰
     */
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

    /**
     * 重排前先从融合池里取多少块。重排只能在这个池子里换座位，池子太小它再准也救不回来。
     */
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
                                  RerankService rerankService,
                                  @Qualifier("ragExecutor") Executor ragExecutor) {
        this.ragReferenceService = ragReferenceService;
        this.bm25IndexService = bm25IndexService;
        this.rerankService = rerankService;
        this.ragExecutor = ragExecutor;
    }

    /**
     * 配置里的原始模式（app.retrieval.mode）。生产实际执行哪个通道看 {@link #executedMode(String, boolean)}。
     */
    public String getMode() {
        return mode == null ? "vector" : mode;
    }

    /**
     * 本次真正会执行的模式：配置成 bm25 时这里是 hybrid。
     */
    public String effectiveMode() {
        return enforceGateCapable(getMode(), bm25IndexService.isAvailable());
    }

    /**
     * search() 里这次究竟走哪条通道（纯函数）。
     * <p>
     * 和 {@link #effectiveMode()} 的差别只在不认识的写法上：enforceGateCapable 对它原样透传，
     * 但 {@link #search} 的 switch 只有 hybrid 一个 case，其余全落 default 分支＝向量通道。
     * 所以配成 rerank、或把 hybrid 拼错时，真正执行的是 vector。
     */
    static String executedMode(String configured, boolean bm25IndexAvailable) {
        return enforceGateCapable(configured, bm25IndexAvailable).equals("hybrid") ? "hybrid" : "vector";
    }

    /**
     * 打印用：配置值与实际执行值不一致时两个都写出来，并说明原因，别让日志替错误配置背书。
     */
    public String describeMode() {
        String raw = getMode();
        String run = executedMode(raw, bm25IndexService.isAvailable());
        if (raw.equalsIgnoreCase(run)) return run;
        String why = unrecognizedModeWarning(raw) != null ? "，模式名不认识" : "，已降级";
        return run + "（配置=" + raw + why + "）";
    }

    @PostConstruct
    void warnUngatedModeConfig() {
        String unrecognized = unrecognizedModeWarning(getMode());
        if (unrecognized != null) {
            log.warn(unrecognized);
        }
        if (getMode().equalsIgnoreCase("bm25")) {
            log.warn("[检索模式] app.retrieval.mode=bm25 已降级为 {}：纯关键词通道没有余弦，可答性闸门判不了，生产问答不允许只走关键词。需要 bm25 单边对照请用零调用口子 /evaluation/recall?mode=bm25。", effectiveMode());
        }
    }

    /**
     * 配了认不出来的模式名时给一句话，返回 {@code null} 表示不用警告。
     * <p>
     * 为什么要有这条：{@link #enforceGateCapable} 把不认识的写法原样透传，{@link #search} 的 switch
     * 只剩 default 分支接住它，于是**静默按 vector 跑**——把 hybrid 拼错、或把评测专用的 rerank
     * 当生产模式写进配置，检索照样出结果，谁都不报错，只有分数和降级行为悄悄变了。
     * 本次改造只加提示和  这个如实标签，执行路径一个字节没动（仍然是 vector，和改造前一致）。
     */
    static String unrecognizedModeWarning(String configured) {
        String m = configured == null ? "vector" : configured.toLowerCase();
        if (m.equals("vector") || m.equals("hybrid") || m.equals("bm25")) return null;
        return "[检索模式] ⚠️ app.retrieval.mode=" + configured + " 不是可识别的模式（生产只有 vector / hybrid；"
                + "bm25、rerank 是评测口子专用），本次实际按 vector 执行，这份配置等于没生效。";
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
        return search(query, topK, threshold, null);
    }

    /**
     * @param threshold     向量通道的相似度下限，传 null 时用 app.retrieval.vector-floor
     * @param versionFilter 限定检索的版本号白名单（null/空集=不过滤，"2023"/"2021" 这种）
     */
    public List<Document> search(String query, int topK, Double threshold, java.util.Set<String> versionFilter) {
        String m = enforceGateCapable(getMode(), bm25IndexService.isAvailable());
        List<Document> raw;
        switch (m) {
            case "hybrid" -> raw = hybrid(query, topK, true, rerankEnabled);
            default -> {
                List<Document> v = ragReferenceService.search(retrievalQuery(query), topK,
                        threshold == null ? vectorFloor : threshold);
                raw = hasEvidence(v) ? v : List.of();
            }
        }
        return applyVersionFilter(raw, versionFilter);
    }

    static List<Document> applyVersionFilter(List<Document> docs, java.util.Set<String> versionFilter) {
        if (docs == null || docs.isEmpty() || versionFilter == null || versionFilter.isEmpty()) {
            return docs;
        }
        java.util.List<Document> filtered = new java.util.ArrayList<>();
        for (Document d : docs) {
            Object v = d.getMetadata() == null ? null : d.getMetadata().get("version");
            if (v != null && versionFilter.contains(String.valueOf(v))) {
                filtered.add(d);
            }
        }
        return filtered;
    }

    /**
     * @param expand 是否把命中块的前后邻居块补进上下文
     */
    public List<Document> searchFused(String query, int topK, boolean expand) {
        return hybrid(query, topK, expand, false);
    }

    /**
     * 融合 + 重排，强制开启重排（评测对比用，不看 rerank-enabled）
     */
    public List<Document> searchReranked(String query, int topK, boolean expand) {
        return hybrid(query, topK, expand, true);
    }

    public double getAnswerFloor() {
        return answerFloor;
    }

    /**
     * 重排实际用哪个查询串（受 rerank-use-expanded-query 支配）。评测要对同一批候选单独调重排，得跟线上用同一个口径。
     */
    public String rerankQuery(String query) {
        return rerankUseExpandedQuery ? QueryExpander.expand(query) : query;
    }

    public boolean isRerankUseExpandedQuery() {
        return rerankUseExpandedQuery;
    }

    /**
     * 第一名都不够像（或压根没召回到）就判定为「这次没有资料」，只判一次，不逐条砍。
     */
    private boolean hasEvidence(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) return false;
        Double top = vectorDocs.get(0).getScore();
        return top != null && top >= answerFloor;
    }

    private List<Document> hybrid(String query, int topK, boolean expand, boolean rerank) {
        int poolSize = rerank ? Math.max(topK, rerankWindow) : topK;
        int perChannel = rerank ? Math.max(probeSize, poolSize) : probeSize;
        String recallQuery = retrievalQuery(query);

        log.info("[检索并行] 启动: query={}  poolSize={}  expand={}  rerank={}  线程={}", query, poolSize, expand, rerank, Thread.currentThread().getName());

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(
                () -> {
                    log.debug("[检索并行] 向量通道开始  线程={}", Thread.currentThread().getName());
                    return ragReferenceService.search(recallQuery, perChannel, vectorFloor);
                }, ragExecutor);
        CompletableFuture<List<Bm25IndexService.Hit>> bm25Future = CompletableFuture.supplyAsync(
                () -> {
                    log.debug("[检索并行] BM25 通道开始  线程={}", Thread.currentThread().getName());
                    return bm25IndexService.search(recallQuery, perChannel);
                }, ragExecutor);

        List<Document> vectorDocs;
        try {
            vectorDocs = vectorFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            bm25Future.cancel(true);
            log.warn("[检索并行] 向量检索超时/异常，取消 BM25：{}", e.getMessage());
            return List.of();
        }

        if (!hasEvidence(vectorDocs)) {
            bm25Future.cancel(true);
            log.info("[检索闸门] 最像的一条也不够像（阈值 {}），本次不提供知识库上下文：{}", String.format("%.2f", answerFloor), query);
            return List.of();
        }

        List<Bm25IndexService.Hit> bm25Hits;
        try {
            bm25Hits = bm25Future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[检索并行] BM25 超时/异常，只用向量通道：{}", e.getMessage());
            bm25Hits = List.of();
        }

        List<Document> fused = fuse(vectorDocs, fromHits(bm25Hits), poolSize);
        String rerankQuery = rerankQuery(query);
        if (rerank && !rerankQuery.equals(query)) {
            log.debug("[重排查询] 本次送重排的是扩展串（rerank-use-expanded-query=true）：{}", rerankQuery);
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

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(
                () -> ragReferenceService.search(recallQuery, perChannel, vectorFloor), ragExecutor);
        CompletableFuture<List<Bm25IndexService.Hit>> bm25Future = CompletableFuture.supplyAsync(
                () -> bm25IndexService.search(recallQuery, perChannel), ragExecutor);

        try {
            return fuse(vectorFuture.get(30, TimeUnit.SECONDS),
                    fromHits(bm25Future.get(30, TimeUnit.SECONDS)), poolSize);
        } catch (Exception e) {
            log.warn("[检索并行] fuseCandidates 超时/异常：{}", e.getMessage());
            bm25Future.cancel(true);
            try {
                return fuse(vectorFuture.get(5, TimeUnit.SECONDS), List.of(), poolSize);
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    /**
     * 命中口语措辞时给召回通道补指南术语（详见 QueryExpander），没命中就原样返回、不打日志。
     */
    private static String retrievalQuery(String query) {
        List<String> extra = QueryExpander.addedTerms(query);
        if (extra.isEmpty()) return query;
        log.debug("[查询扩展] 补充术语: {}  原问句: {}", String.join(" ", extra), query);
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