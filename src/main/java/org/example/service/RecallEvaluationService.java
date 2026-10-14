package org.example.service;

import org.example.model.MedicalTestSet;
import org.example.model.TestCase;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只测「检索」不测「生成」：拿测试集问题跑召回，看该被召回的正文/文献有没有进到 top-K。
 * <p>
 * 目的是给混合检索一条可重复的基线，mode 支持 vector / bm25 / hybrid / rerank，
 * 换检索器时同一份口径可以直接对比。调用量：mode=bm25 全程不碰模型接口；vector / hybrid 每题一次
 * text-embedding-v3；rerank 在此基础上每题再加一次 gte-rerank-v2（它还要给「重排前后」取同一批候选）。
 */
@Service
public class RecallEvaluationService {

    private static final int[] K_CUTS = {1, 2, 4, 8, 12, 20};

    /**
     * 块号容错半径。推荐意见这类表格会被切块劈成相邻两块，严格块号会把「隔壁块已经捞到」判成 0 分；
     * 生产链路的相邻块扩展本来就会补上邻居，所以两个口径都要报，只报一个都会失真。
     */
    private static final int CHUNK_TOLERANCE = 1;

    /** 分问法统计：书面题和口语题的答案块完全相同，只比问法，这一栏就是关键词检索的"水分"。 */
    private static final class CatStat {
        int n, hit1, hit4, hit8;
        double mrr;

        void accept(int rank) {
            n++;
            if (rank > 0 && rank <= 1) hit1++;
            if (rank > 0 && rank <= 4) hit4++;
            if (rank > 0 && rank <= 8) hit8++;
            if (rank > 0) mrr += 1.0 / rank;
        }
    }

    private final RagReferenceService ragReferenceService;
    private final MedicalTestSet medicalTestSet;
    private final Bm25IndexService bm25IndexService;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;

    public RecallEvaluationService(RagReferenceService ragReferenceService,
                                   MedicalTestSet medicalTestSet,
                                   Bm25IndexService bm25IndexService,
                                   HybridRetrievalService hybridRetrievalService,
                                   RerankService rerankService) {
        this.ragReferenceService = ragReferenceService;
        this.medicalTestSet = medicalTestSet;
        this.bm25IndexService = bm25IndexService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
    }

    /** 一次检索的扁平结果，向量分和 BM25 分都塞进同一个形状，指标口径才不会因检索器而变。 */
    private record Probe(List<String> text, List<String> file, List<Double> score, List<String> chunk) {}

    public String run(int probeK, String mode, boolean expand) {
        String m = mode == null ? "vector" : mode.toLowerCase();
        StringBuilder out = new StringBuilder();
        List<TestCase> all = medicalTestSet.getTestCases();
        int[] ckHit = new int[K_CUTS.length];
        int[] ckAll = new int[K_CUTS.length];
        int[] ckHitNear = new int[K_CUTS.length];
        double[] ckCover = new double[K_CUTS.length];
        int[] kwHit = new int[K_CUTS.length];
        int[] allKwHit = new int[K_CUTS.length];
        int[] docHit = new int[K_CUTS.length];
        int total = 0;
        double mrrSum = 0.0;
        double ckMrrSum = 0.0;
        // mode=rerank 专用：同一批候选在重排前后的名次对照
        int[] ckHitBefore = new int[K_CUTS.length];
        double ckMrrBefore = 0.0;
        int movedUp = 0, movedDown = 0, movedSame = 0;
        Map<String, CatStat> byCat = new LinkedHashMap<>();
        Map<String, CatStat> byCatBefore = new LinkedHashMap<>();

        out.append("== ").append(switch (m) {
            case "bm25" -> "BM25 关键词召回";
            case "hybrid" -> "混合召回（向量+BM25，RRF 融合" + (expand ? "+相邻块扩展" : "，未含相邻块扩展") + "）";
            case "rerank" -> "混合+重排召回（RRF 融合取候选，再送 " + rerankService.getModelName()
                    + " 逐对打分重新定序" + (expand ? "+相邻块扩展" : "") + "）";
            default -> "向量召回";
        }).append("基线（probeK=").append(probeK)
                .append("，只做检索，不调用生成模型）==\n");
        if (m.equals("rerank")) {
            out.append("重排模型可用性：").append(rerankService.isAvailable() ? "已就绪（每次评测每题一次重排调用）"
                    : "❌ 拿不到 RerankModel bean，下面的结果其实等于纯融合排序").append('\n');
        }
        out.append("主口径=答案块命中：top-K 里出现了人工标注的『答案所在块』才算召回，这是唯一能证明『答案真的被找回』的指标。\n");
        out.append("副口径=关键词：top-K 正文里出现期望关键词，只证明『话题相关』，答案数字未必在块里。\n");

        for (TestCase tc : all) {
            if (!tc.isShouldBeAnswerable()) continue;
            total++;
            Probe before = null;
            Probe p;
            if (m.equals("rerank")) {
                // 候选池只取一次：before=融合顺序，p=重排顺序，两者题目集合完全相同，只差排序
                List<Document> pool = hybridRetrievalService.fuseCandidates(tc.getQuestion(), probeK);
                before = documentProbe(pool);
                // 用 HybridRetrievalService.rerankQuery 而不是直接用原话：这张对照表要复现的就是线上那次重排，
                // 线上换成扩展串（rerank-use-expanded-query=true）时评测必须跟着换，否则测的是线上不走的分支。
                p = documentProbe(rerankService.rerank(hybridRetrievalService.rerankQuery(tc.getQuestion()), pool));
            } else {
                p = probe(tc.getQuestion(), probeK, m, expand);
            }
            int n = p.text().size();

            List<String> gold = tc.getAnswerChunks();
            int goldRank = firstChunkRank(p, gold);
            int goldRankNear = firstChunkRankWithin(p, gold, CHUNK_TOLERANCE);
            int goldWorst = worstChunkRank(p, gold);
            byCat.computeIfAbsent(tc.getCategory(), k -> new CatStat()).accept(goldRank);
            if (goldRank > 0) ckMrrSum += 1.0 / goldRank;
            int goldRankBefore = 0;
            if (before != null) {
                goldRankBefore = firstChunkRank(before, gold);
                byCatBefore.computeIfAbsent(tc.getCategory(), k -> new CatStat()).accept(goldRankBefore);
                if (goldRankBefore > 0) ckMrrBefore += 1.0 / goldRankBefore;
                for (int i = 0; i < K_CUTS.length; i++) {
                    if (goldRankBefore > 0 && goldRankBefore <= K_CUTS[i]) ckHitBefore[i]++;
                }
                if (goldRankBefore > 0 && goldRank > 0) {
                    if (goldRank < goldRankBefore) movedUp++;
                    else if (goldRank > goldRankBefore) movedDown++;
                    else movedSame++;
                }
            }
            for (int i = 0; i < K_CUTS.length; i++) {
                if (goldRank > 0 && goldRank <= K_CUTS[i]) ckHit[i]++;
                if (goldRankNear > 0 && goldRankNear <= K_CUTS[i]) ckHitNear[i]++;
                if (goldWorst > 0 && goldWorst <= K_CUTS[i]) ckAll[i]++;
                ckCover[i] += coveredFraction(p, gold, K_CUTS[i]);
            }

            int kwRank = firstRank(p, tc.getExpectedKeywords(), true);
            int docRank = firstRank(p, tc.getExpectedReferences(), false);
            int worstKwRank = worstRank(p, tc.getExpectedKeywords());
            if (kwRank > 0) mrrSum += 1.0 / kwRank;
            for (int i = 0; i < K_CUTS.length; i++) {
                if (kwRank > 0 && kwRank <= K_CUTS[i]) kwHit[i]++;
                if (worstKwRank > 0 && worstKwRank <= K_CUTS[i]) allKwHit[i]++;
                if (docRank > 0 && docRank <= K_CUTS[i]) docHit[i]++;
            }

            out.append("\n[").append(tc.getCategory()).append("] ").append(tc.getQuestion()).append('\n');
            out.append("  答案块=").append(shortGold(gold)).append('\n');
            out.append("  【答案块】首命中rank=").append(rankText(goldRank))
                    .append("  ±").append(CHUNK_TOLERANCE).append("块容错rank=").append(rankText(goldRankNear))
                    .append("  全部到位rank=").append(rankText(goldWorst))
                    .append("  覆盖@").append(n).append('=').append(coveredText(coveredFraction(p, gold, n))).append('\n');
            if (before != null) {
                out.append("  同一池子重排前第").append(rankText(goldRankBefore)).append("名 → 重排后第")
                        .append(rankText(goldRank)).append("名（").append(moveMark(goldRankBefore, goldRank))
                        .append("）\n");
            }
            out.append("  【关键词】首命中rank=").append(rankText(kwRank))
                    .append("  全部到位rank=").append(rankText(worstKwRank))
                    .append("  文献首命中rank=").append(rankText(docRank)).append('\n');
            out.append("  逐词位次: ").append(perKeywordRanks(p, tc.getExpectedKeywords())).append('\n');
            out.append("  召回=").append(n)
                    .append("  第一名块=").append(p.chunk().isEmpty() ? "?" : p.chunk().get(0))
                    .append("  分数: top1=").append(fmt(first(p.score())))
                    .append(" 第").append(n).append("位=").append(fmt(last(p.score()))).append('\n');
            if (m.equals("vector")) out.append(thresholdLine(p));
            if (goldRank == 0) {
                out.append(goldRankNear > 0
                        ? "  🔶 标注的答案块没进候选池，但它的第" + goldRankNear + "名邻居块被捞到了——"
                        + "答案表格被切块劈成了两半（生产链路靠相邻块扩展能补上，严格块级口径判 0 分）\n"
                        : "  ❌ 答案块连同左右邻居都没进候选池（probeK=" + n + "）：检索真漏了，"
                        + "常见于该块正文被字体乱码/水印串污染，向量被带偏\n");
            } else if (goldRank > 8) {
                out.append("  ⚠️ 答案块要到第").append(goldRank).append("位才出现，top8 之前进不了提示词\n");
            }
        }

        out.append("\n== 汇总（问题数=").append(total).append("）==\n");
        out.append("  K   答案块命中(严格)  ±").append(CHUNK_TOLERANCE)
                .append("块容错  答案块全覆盖  平均覆盖度 | 任一词  全部词  文献\n");
        for (int i = 0; i < K_CUTS.length; i++) {
            out.append("  ").append(String.format("%-3d", K_CUTS[i]))
                    .append(String.format("%6.1f%%（%d/%d）", 100.0 * ckHit[i] / Math.max(1, total), ckHit[i], total))
                    .append(String.format("  %6.1f%%", 100.0 * ckHitNear[i] / Math.max(1, total)))
                    .append(String.format("  %6.1f%%", 100.0 * ckAll[i] / Math.max(1, total)))
                    .append(String.format("  %6.1f%%", ckCover[i] / Math.max(1, total)))
                    .append("  |  ").append(pct(kwHit[i], total))
                    .append("  ").append(pct(allKwHit[i], total))
                    .append("  ").append(pct(docHit[i], total)).append('\n');
        }
        out.append("  MRR(答案块)=").append(String.format("%.3f", total == 0 ? 0 : ckMrrSum / total))
                .append("  MRR(关键词)=")
                .append(String.format("%.3f", total == 0 ? 0 : mrrSum / total)).append('\n');
        if (m.equals("rerank")) {
            out.append(rerankDelta(total, ckHitBefore, ckHit, ckMrrBefore, ckMrrSum,
                    movedUp, movedDown, movedSame, byCatBefore, byCat));
        }
        out.append("== 按问法分组（答案块完全相同，只差问法：书面=照抄指南术语，口语=患者说法）==\n");
        out.append("  类别  题数  答案块@1   @4    @8   MRR\n");
        for (Map.Entry<String, CatStat> e : byCat.entrySet()) {
            CatStat c = e.getValue();
            out.append("  ").append(e.getKey()).append("  ")
                    .append(String.format("%3d  %5.1f%% %5.1f%% %5.1f%%  %.3f\n", c.n,
                            100.0 * c.hit1 / Math.max(1, c.n),
                            100.0 * c.hit4 / Math.max(1, c.n),
                            100.0 * c.hit8 / Math.max(1, c.n),
                            c.mrr / Math.max(1, c.n)));
        }
        out.append(negativeControls(probeK, m, expand));
        return out.toString();
    }

    /** 融合顺序 → 重排顺序的名次差，只看顺序不看候选池。 */
    private static String moveMark(int before, int after) {
        if (before <= 0 || after <= 0) return "不可比";
        int d = before - after;
        return d > 0 ? "提前" + d + "位" : d < 0 ? "退后" + (-d) + "位" : "名次不变";
    }

    /** 重排不改候选池只改顺序，所以前后两列能直接相减——这是唯一能证明「重排本身值不值」的对比。 */
    private String rerankDelta(int total, int[] before, int[] after, double mrrBefore, double mrrAfter,
                               int up, int down, int same,
                               Map<String, CatStat> catBefore, Map<String, CatStat> catAfter) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n== 重排前后的名次变化（同一批候选，只差排序，所以两列可直接相减）==\n");
        sb.append("  K      重排前        重排后        差\n");
        for (int i = 0; i < K_CUTS.length; i++) {
            double b = 100.0 * before[i] / Math.max(1, total);
            double a = 100.0 * after[i] / Math.max(1, total);
            sb.append("  ").append(String.format("%-4d", K_CUTS[i]))
                    .append(String.format("%6.1f%%（%3d/%d）", b, before[i], total))
                    .append(String.format("%6.1f%%（%3d/%d）", a, after[i], total))
                    .append(String.format("  %+6.1f", a - b)).append('\n');
        }
        sb.append("  MRR(答案块) ")
                .append(String.format("%.3f → %.3f（%+.3f）",
                        mrrBefore / Math.max(1, total), mrrAfter / Math.max(1, total),
                        (mrrAfter - mrrBefore) / Math.max(1, total))).append('\n');
        sb.append("  逐题答案块名次：提前").append(up).append("题 / 不变").append(same)
                .append("题 / 退后").append(down).append("题\n");
        sb.append("  按问法看首位（重排前 → 重排后）：\n");
        for (Map.Entry<String, CatStat> e : catAfter.entrySet()) {
            CatStat a = e.getValue();
            CatStat b = catBefore.get(e.getKey());
            int nb = b == null ? 1 : Math.max(1, b.n);
            int na = Math.max(1, a.n);
            sb.append("    ").append(e.getKey()).append("  @1 ")
                    .append(String.format("%5.1f%% → %5.1f%%   @4 %5.1f%% → %5.1f%%   @8 %5.1f%% → %5.1f%%  （%d题）\n",
                            100.0 * (b == null ? 0 : b.hit1) / nb, 100.0 * a.hit1 / na,
                            100.0 * (b == null ? 0 : b.hit4) / nb, 100.0 * a.hit4 / na,
                            100.0 * (b == null ? 0 : b.hit8) / nb, 100.0 * a.hit8 / na, a.n));
        }
        return sb.toString();
    }

    private Probe probe(String query, int probeK, String mode, boolean expand) {
        // bm25 / vector 这两条单边通道也要过 QueryExpander：线上 HybridRetrievalService 是在进通道前就扩展好的，
        // 评测若拿原话去打分边通道，测到的是线上根本不会用的查询串。hybrid / rerank 走 searchFused/searchReranked，
        // 扩展已经在 hybrid() 里做了，这里不能再补一遍。
        String recallQuery = (mode.equals("bm25") || mode.equals("vector"))
                ? QueryExpander.expand(query) : query;
        return switch (mode) {
            case "bm25" -> bm25Probe(recallQuery, probeK);
            case "hybrid" -> documentProbe(hybridRetrievalService.searchFused(query, probeK, expand));
            case "rerank" -> documentProbe(hybridRetrievalService.searchReranked(query, probeK, expand));
            default -> documentProbe(ragReferenceService.search(recallQuery, probeK, null));
        };
    }

    /** 向量与融合通道都返回 Document，走同一个取 key 的逻辑，指标口径才不会随检索器变化。 */
    private Probe documentProbe(List<Document> docs) {
        List<String> texts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        if (docs != null) {
            for (Document d : docs) {
                String file = fileNameOf(d);
                texts.add(d.getText() == null ? "" : d.getText());
                files.add(file);
                scores.add(d.getScore() == null ? -1 : d.getScore());
                chunks.add(chunkKey(file, metaValue(d, "chunk_index")));
            }
        }
        return new Probe(texts, files, scores, chunks);
    }

    private Probe bm25Probe(String query, int probeK) {
        List<Bm25IndexService.Hit> hits = bm25IndexService.search(query, probeK);
        List<String> texts = new ArrayList<>();
        List<String> files = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<String> chunks = new ArrayList<>();
        for (Bm25IndexService.Hit h : hits) {
            String file = h.fileName() == null ? "" : h.fileName();
            texts.add(h.content() == null ? "" : h.content());
            files.add(file);
            scores.add(h.score());
            chunks.add(chunkKey(file, h.chunkIndex()));
        }
        return new Probe(texts, files, scores, chunks);
    }

    /** 向量分数区间的可解释性：老那道逐条 0.65 阈值到底会砍掉多少条。 */
    private String thresholdLine(Probe p) {
        int n = p.text().size(), ge065 = 0, ge045 = 0;
        for (Double s : p.score()) {
            if (s >= 0.65) ge065++;
            if (s >= 0.45) ge045++;
        }
        return "  ≥0.65的条数=" + ge065 + "/" + n + "（≥0.45=" + ge045
                + "）→ 老的逐条阈值0.65会砍掉" + (n - ge065)
                + "条（现已改成只看第一名是否 ≥ 可答性闸门）\n";
    }

    /**
     * 域外对照：这些知识库里没有答案，看向量还能给多高的分。
     * top1 就有 0.6+ 说明「相似度绝对阈值」挡不住噪声，只能靠 top-K + 下限。
     */
    private String negativeControls(int probeK, String mode, boolean expand) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n== 域外对照（知识库里没有答案，看排名是否仍被抬高）==\n");
        List<String> probes = new ArrayList<>();
        for (TestCase tc : medicalTestSet.getTestCases()) {
            if (!tc.isShouldBeAnswerable()) probes.add(tc.getQuestion());
        }
        probes.add("Java 的 synchronized 和 ReentrantLock 有什么区别？");
        probes.add("2026 年国庆档电影票房排行");
        for (String q : probes) {
            Probe p = probe(q, probeK, mode, expand);
            String hitFile = p.file().isEmpty() ? "<无命中>" : baseName(p.file().get(0));
            sb.append("  ").append(q).append('\n');
            sb.append("      命中条数=").append(p.text().size())
                    .append("  top1分数=").append(fmt(first(p.score())))
                    .append("  第一名来自=").append(hitFile).append('\n');
        }
        return sb.toString();
    }

    private static String baseName(String s) {
        if (s == null || s.isEmpty()) return "<unknown>";
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return i >= 0 && i < s.length() - 1 ? s.substring(i + 1) : s;
    }

    /** 检索结果的身份证：文件名#块号，和标注的 answerChunks 用同一格式才能直接比对。 */
    private static String chunkKey(String fileName, Object chunkIndex) {
        String idx = chunkIndex == null ? "" : String.valueOf(chunkIndex).trim();
        if (idx.isEmpty() || idx.equals("null")) return "<无块号>";
        return baseName(fileName) + "#" + idx;
    }

    private static Object metaValue(Document d, String key) {
        Map<String, Object> meta = d.getMetadata();
        return meta == null ? null : meta.get(key);
    }

    /** 第一个落在标注答案块里的名次（1 起），0 = probeK 内一个答案块都没召回。 */
    private int firstChunkRank(Probe p, List<String> gold) {
        if (gold == null || gold.isEmpty()) return 0;
        for (int i = 0; i < p.chunk().size(); i++) {
            if (gold.contains(p.chunk().get(i))) return i + 1;
        }
        return 0;
    }

    /** 同上，但允许同一份文档内块号相差 tolerance：捞到答案块的邻居也算命中。 */
    private int firstChunkRankWithin(Probe p, List<String> gold, int tolerance) {
        if (gold == null || gold.isEmpty()) return 0;
        for (int i = 0; i < p.chunk().size(); i++) {
            for (String g : gold) {
                if (nearChunk(p.chunk().get(i), g, tolerance)) return i + 1;
            }
        }
        return 0;
    }

    /** 邻居判定只在同一份文档内成立，跨文档的 #44 和 #44 不是邻居。 */
    private static boolean nearChunk(String got, String want, int tolerance) {
        if (got.equals(want)) return true;
        int gi = got.indexOf('#'), wi = want.indexOf('#');
        if (gi <= 0 || wi <= 0 || gi != wi || !got.regionMatches(0, want, 0, gi)) return false;
        try {
            return Math.abs(Long.parseLong(got.substring(gi + 1)) - Long.parseLong(want.substring(wi + 1))) <= tolerance;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 综合分析题的答案散在多块，最后一个答案块到位的名次才有意义。 */
    private int worstChunkRank(Probe p, List<String> gold) {
        if (gold == null || gold.isEmpty()) return 0;
        int worst = 0;
        for (String g : gold) {
            int r = p.chunk().indexOf(g);
            if (r < 0) return 0;
            worst = Math.max(worst, r + 1);
        }
        return worst;
    }

    private double coveredFraction(Probe p, List<String> gold, int k) {
        if (gold == null || gold.isEmpty()) return 0;
        int hit = 0;
        for (int i = 0; i < Math.min(k, p.chunk().size()); i++) {
            if (gold.contains(p.chunk().get(i))) hit++;
        }
        return Math.round(hit * 100.0 / gold.size());
    }

    private static String coveredText(double fraction) {
        return String.format("%.0f%%", fraction);
    }

    /** 标注太长，汇总时只显示块号，文件名取公共前缀即可辨认。 */
    private String shortGold(List<String> gold) {
        if (gold == null || gold.isEmpty()) return "(未标注)";
        StringBuilder sb = new StringBuilder();
        String prev = null;
        for (String g : gold) {
            int i = g.indexOf('#');
            String file = i < 0 ? g : g.substring(0, i);
            String idx = i < 0 ? "" : g.substring(i);
            sb.append(prev == null || !prev.equals(file) ? shortFile(file) : "").append(idx).append("  ");
            prev = file;
        }
        return sb.toString().trim();
    }

    private static String shortFile(String fileName) {
        String f = baseName(fileName);
        int dot = f.indexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    /** 返回第一个命中任一关键词的块序号（1 起），0 = 前 probeK 里一个都没有。 */
    private int firstRank(Probe p, List<String> keys, boolean matchText) {
        if (keys == null) return 0;
        for (int i = 0; i < p.text().size(); i++) {
            String hay = matchText ? flat(p.text().get(i)) : flat(p.file().get(i)).toLowerCase();
            for (String k : keys) {
                String needle = flat(k == null ? "" : k).toLowerCase();
                if (!needle.isEmpty() && hay.contains(needle)) return i + 1;
            }
        }
        return 0;
    }

    private int worstRank(Probe p, List<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        int worst = 0;
        for (String k : keys) {
            int r = firstRank(p, List.of(k), true);
            if (r == 0) return 0;
            worst = Math.max(worst, r);
        }
        return worst;
    }

    private String perKeywordRanks(Probe p, List<String> keys) {
        if (keys == null) return "(无关键词)";
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(k).append('→').append(rankText(firstRank(p, List.of(k), true))).append("  ");
        }
        return sb.toString().trim();
    }

    private static String rankText(int r) { return r == 0 ? "无" : String.valueOf(r); }

    private static String fileNameOf(Document d) {
        Map<String, Object> meta = d.getMetadata();
        Object v = meta == null ? null : meta.get("source");
        if (v == null && meta != null) v = meta.get("file_name");
        return v == null ? "" : String.valueOf(v);
    }

    /** PDF 抽取常在中文字间插入空格，去掉所有空白再比对关键词，避免"看起来没命中"的假阴性。 */
    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static double first(List<Double> s) { return s.isEmpty() ? -1 : s.get(0); }

    private static double last(List<Double> s) { return s.isEmpty() ? -1 : s.get(s.size() - 1); }

    private static String fmt(double v) { return v < 0 ? "n/a" : String.format("%.3f", v); }

    private static String pct(int hit, int total) {
        return total == 0 ? "0.0%" : String.format("%.1f%%", hit * 100.0 / total);
    }
}
