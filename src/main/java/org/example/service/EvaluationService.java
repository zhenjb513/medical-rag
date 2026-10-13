package org.example.service;

import org.example.controller.ChatController;
import org.example.model.MedicalTestSet;
import org.example.model.TestCase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvaluationService {

    private final ChatController chatController;
    private final MedicalTestSet medicalTestSet;

    @Autowired
    public EvaluationService(ChatController chatController, MedicalTestSet medicalTestSet) {
        this.chatController = chatController;
        this.medicalTestSet = medicalTestSet;
    }

    /**
     * 单个测试结果
     */
    public static class TestResult {
        public String question;
        public String answer;
        public boolean shouldBeAnswerable;
        public String category;
        public boolean accuracyPass;      // 准确性是否通过
        public boolean referencePass;     // 引用是否通过
        public boolean hallucinationPass; // 幻觉抑制是否通过
        public boolean compliancePass;    // 合规性是否通过
        public String accuracyEvidence;   // 准确性的出处：命中的关键词落在哪一块
        public boolean backfillSuspect;   // 关键词只在答案里出现、上下文里没有 → 模型自带知识
        public boolean wrongBlockSuspect; // 关键词有出处、但出处不是考卷标注的答案块 → 没答到这一段
        public String failReason;         // 失败原因

        public boolean isAllPass() {
            return accuracyPass && referencePass && hallucinationPass && compliancePass;
        }
    }

    /**
     * 评估报告
     */
    public static class EvaluationReport {
        public int totalCount;
        public int passCount;
        public double passRate;
        public int accuracyPassCount;
        public int referencePassCount;
        public int hallucinationPassCount;
        public int compliancePassCount;
        public int backfillSuspectCount;
        public int wrongBlockCount;
        public List<TestResult> results;
        public Map<String, Integer> categoryStats;
        public String summary;
    }

    /**
     * 运行完整评估
     */
    public EvaluationReport runEvaluation() {
        return runEvaluation(List.of());
    }

    /**
     * 只跑题目包含任一片段的用例；片段列表为空就是全量。
     * 为什么要这个口子：这条链路一跑起来每道题是三次付费调用（向量化的查询、重排、生成），
     * 全卷 61 题实测 392 秒。所以先用 matchedQuestions() 零成本确认选中的到底是哪些题，再决定跑。
     *
     * @param only 题目里要包含的片段，比如「头晕」「火星」
     */
    public EvaluationReport runEvaluation(List<String> only) {
        List<TestCase> testCases = medicalTestSet.getTestCases();
        List<TestResult> results = new ArrayList<>();

        int accuracyPass = 0, referencePass = 0, hallucinationPass = 0, compliancePass = 0;
        // 答案写了预期关键词、但本次送进模型的上下文里查不到出处：模型在用自己的知识替库背书
        int backfillSuspect = 0;
        // 有出处，但出处落在别的块上：检索找回了同一份指南里无关的段落，答案看着像对、其实没答到考点
        int wrongBlock = 0;

        // 逐题打印到标准输出：报告 body 只在 HTTP 响应里，客户端断线就拿不到了（2026-10-12 实测发生过一次），
        // 所以每题的判定和答案原文必须先落在服务端日志里，事后可以用脚本重建成报告。
        int ran = 0;
        for (int i = 0; i < testCases.size(); i++) {
            TestCase testCase = testCases.get(i);
            if (!matched(only, testCase.getQuestion())) {
                continue;
            }
            TestResult result = evaluateSingle(testCase);
            results.add(result);
            ran++;
            System.out.println("[逐题] 本次第" + ran + "道  全卷#" + (i + 1)
                    + (result.isAllPass() ? " ✅通过" : " ❌未通过")
                    + "  准确=" + yn(result.accuracyPass) + "  引用=" + yn(result.referencePass)
                    + "  幻觉=" + yn(result.hallucinationPass) + "  合规=" + yn(result.compliancePass)
                    + "  问题=" + result.question + "  类别=" + result.category);
            if (!result.isAllPass()) {
                System.out.println("[逐题] #" + (i + 1) + "  失败原因=" + result.failReason);
            }
            System.out.println("[逐题] #" + (i + 1) + "  出处=" + result.accuracyEvidence
                    + (result.backfillSuspect ? "  ⚠️模型自带知识" : "")
                    + (result.wrongBlockSuspect ? "  ⚠️出处不在答案块" : ""));
            System.out.println("[逐题] #" + (i + 1) + "  答案=" + flatten(result.answer));
            if (result.accuracyPass) accuracyPass++;
            if (result.referencePass) referencePass++;
            if (result.hallucinationPass) hallucinationPass++;
            if (result.compliancePass) compliancePass++;
            if (result.backfillSuspect) backfillSuspect++;
            if (result.wrongBlockSuspect) wrongBlock++;
        }

        // 统计分类
        Map<String, Integer> categoryStats = new LinkedHashMap<>();
        for (TestResult r : results) {
            categoryStats.merge(r.category, 1, Integer::sum);
        }

        // 计算通过率
        long passCount = results.stream().filter(TestResult::isAllPass).count();
        double passRate = results.isEmpty() ? 0 : (passCount * 100.0 / results.size());

        EvaluationReport report = new EvaluationReport();
        report.totalCount = results.size();
        report.passCount = (int) passCount;
        report.passRate = Math.round(passRate * 10) / 10.0;
        report.accuracyPassCount = accuracyPass;
        report.referencePassCount = referencePass;
        report.hallucinationPassCount = hallucinationPass;
        report.compliancePassCount = compliancePass;
        report.backfillSuspectCount = backfillSuspect;
        report.wrongBlockCount = wrongBlock;
        report.results = results;
        report.categoryStats = categoryStats;
        report.summary = generateSummary(report);

        return report;
    }

    /**
     * 判分前的文本归一：破折号/波浪号家族并成一个 '~'，并去掉所有空白（含全角空格 U+3000、软连字符 U+00AD）。
     * 依据是单题端到端实测（scratch/chat1.txt，2026-10-12）：库里原文「4~6周」用的是 U+007E，模型答成「4–6周」用的是 U+2013
     * （答案全文里逐字符验过码点）；库里「TG>4.5mmol/L」没有空格，模型写成「TG > 4.5 mmol/L」。
     * 原来四道判定都是 answer.contains(...) 逐字比，考卷里 6 道带 ~ 的关键词
     * （MedicalTestSet.java:126/138/144/218/230/236）会因为标点被判成准确性未通过。
     */
    static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '\u00AD') {
                continue;
            }
            if ("~～〜-‐‑‒–—―−－".indexOf(c) >= 0) {
                sb.append('~');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 准确性判定的全部逻辑，抽成纯函数好单测（scratch/AccuracyCheck.java 直接调它）。
     * <p>
     * 口径：关键词「在答案里」＋「在本次送进模型的上下文里」＋「出处那块正是考卷标注的答案块」三者齐全才算过。
     * 只满足前两条会判挂并标 wrongBlock：实测 2026-10-13 的 #43 就是这样——答案写的 2.6 确实来自库里的
     * 表3（XZ#32），但真正回答家族性高胆固醇血症目标值的是 XZ#107/#108，那两块这次根本没进上下文。
     * 「没编造」不等于「答在对的段落上」，所以出处必须对得上答案块。
     */
    static AccuracyVerdict judgeAccuracy(String normAnswer, List<String> normDocs,
                                         List<org.springframework.ai.document.Document> docs,
                                         List<String> keywords, List<String> answerChunks) {
        String gold = null;       // 答案有 + 出处是标注答案块
        String other = null;      // 答案有 + 出处在别的块
        String unsourced = null;  // 答案有 + 上下文一块都没有
        for (String keyword : keywords) {
            String nk = normalizeForMatch(keyword);
            if (nk.isEmpty() || !normAnswer.contains(nk)) {
                continue;
            }
            List<Integer> hits = sourceIndexes(normDocs, nk);
            if (hits.isEmpty()) {
                if (unsourced == null) {
                    unsourced = keyword;
                }
                continue;
            }
            for (int at : hits) {
                String key = chunkKeyOf(docs.get(at));
                if (answerChunks != null && answerChunks.contains(key)) {
                    if (gold == null) {
                        gold = keyword + "→" + key + "（答案块，上下文第" + (at + 1) + "块）";
                    }
                    break;
                }
                if (other == null) {
                    other = keyword + "→" + key + "（上下文第" + (at + 1) + "块）";
                }
            }
        }
        if (gold != null) {
            return new AccuracyVerdict(true, false, false, gold, null);
        }
        if (other != null) {
            return new AccuracyVerdict(false, false, true,
                    "出处不在答案块：" + other + "；考卷标注的答案块=" + answerChunks + neighborHint(other, answerChunks),
                    "准确性未通过：答案里的说法在 " + docs.size() + " 块上下文里能逐字找到（" + other
                            + "），但那不是考卷标注的答案块 " + answerChunks
                            + "，等于没答到这道题对应的原文；");
        }
        if (unsourced != null) {
            return new AccuracyVerdict(false, true, false,
                    "无出处：" + unsourced + " 只在答案里，" + docs.size() + " 块上下文中没有",
                    "准确性未通过：答案里的「" + unsourced + "」在本次送进模型的 " + docs.size()
                            + " 块上下文里找不到出处，属模型自带知识（库外背书），不算答对；");
        }
        return new AccuracyVerdict(false, false, false, "预期关键词未出现在答案里",
                "准确性未通过：答案未包含预期关键词 " + keywords + "；");
    }

    /**
     * 出处块和某个答案块同名不同号且只差 1 时提示一下。
     * 表格类原文常被切成相邻两块（FH 推荐表就是 #107/#108），这种很可能是考卷漏标而不是检索错，
     * 判分仍按挂处理，只把线索写出来供人工核对。
     */
    private static String neighborHint(String other, List<String> answerChunks) {
        if (answerChunks == null || other == null) {
            return "";
        }
        int sharp = other.lastIndexOf('#');
        int paren = other.indexOf('（', sharp);
        if (sharp < 0) {
            return "";
        }
        String file = other.substring(other.indexOf('→') + 1, sharp);
        String num = other.substring(sharp + 1, paren > sharp ? paren : other.length());
        try {
            int idx = Integer.parseInt(num.trim());
            for (String gold : answerChunks) {
                int g = gold.lastIndexOf('#');
                if (g < 0 || !gold.substring(0, g).equals(file)) {
                    continue;
                }
                if (Math.abs(Integer.parseInt(gold.substring(g + 1).trim()) - idx) == 1) {
                    return "；注意：出处 " + file + "#" + idx + " 与答案块 " + gold + " 相邻，可能考卷漏标";
                }
            }
        } catch (RuntimeException e) {
            return "";
        }
        return "";
    }

    /** pass=判过；backfill=答案有但库里没有出处；wrongBlock=有出处但不在标注答案块；evidence=出处或未通过线索 */
    record AccuracyVerdict(boolean pass, boolean backfill, boolean wrongBlock,
                           String evidence, String failReason) {
    }

    /** 关键词命中的所有上下文块序号（0 起）；空列表表示库里一块都没有。 */
    private static List<Integer> sourceIndexes(List<String> normDocs, String normKeyword) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < normDocs.size(); i++) {
            if (normDocs.get(i).contains(normKeyword)) {
                hits.add(i);
            }
        }
        return hits;
    }

    /** 块主键：文件名#chunk_index，和 MedicalTestSet 的 answerChunks、/bm25/locate 同一套写法。 */
    private static String chunkKeyOf(org.springframework.ai.document.Document doc) {
        Map<String, Object> meta = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
        Object src = meta.get("source") != null ? meta.get("source") : meta.get("file_name");
        String name = src == null ? "<unknown>" : String.valueOf(src);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash > 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name + "#" + meta.get("chunk_index");
    }

    /**
     * 评估单个测试用例
     */
    private TestResult evaluateSingle(TestCase testCase) {
        TestResult result = new TestResult();
        result.question = testCase.getQuestion();
        result.category = testCase.getCategory();
        result.shouldBeAnswerable = testCase.isShouldBeAnswerable();

        // 调用生产链路取答案，同时拿到本次真正送进模型的上下文块
        ChatController.ChatTrace trace;
        try {
            trace = chatController.chatWithTrace(testCase.getQuestion());
        } catch (Exception e) {
            trace = new ChatController.ChatTrace("【调用异常】" + e.getMessage(), List.of(), "");
        }
        String answer = trace.answer();
        result.answer = answer;
        // 四道判定统一用归一化后的文本，报告里展示的仍是原始答案
        String normAnswer = normalizeForMatch(answer);
        // 逐块归一：模型只看到这些文本，答案里的说法能在这里找到才算有出处
        List<String> normDocs = new ArrayList<>();
        for (org.springframework.ai.document.Document d : trace.docs()) {
            normDocs.add(normalizeForMatch(d.getText() == null ? "" : d.getText()));
        }

        StringBuilder failReasons = new StringBuilder();

        // 1. 准确性评估
        if (testCase.isShouldBeAnswerable()) {
            AccuracyVerdict v = judgeAccuracy(normAnswer, normDocs, trace.docs(),
                    testCase.getExpectedKeywords(), testCase.getAnswerChunks());
            result.accuracyPass = v.pass();
            result.backfillSuspect = v.backfill();
            result.wrongBlockSuspect = v.wrongBlock();
            result.accuracyEvidence = v.evidence();
            if (!v.pass()) {
                failReasons.append(v.failReason());
            }
        } else {
            // 不应该回答：准确性自动通过（由幻觉抑制评估）
            result.accuracyPass = true;
            result.accuracyEvidence = "该题考察拒答";
        }

        // 2. 引用正确性评估
        if (testCase.isShouldBeAnswerable() && testCase.getExpectedReferences() != null
                && !testCase.getExpectedReferences().isEmpty()) {
            boolean refHit = false;
            for (String ref : testCase.getExpectedReferences()) {
                if (normAnswer.contains(normalizeForMatch(ref))) {
                    refHit = true;
                    break;
                }
            }
            result.referencePass = refHit;
            if (!refHit) {
                failReasons.append("引用未通过：参考来源未包含预期文档 ").append(testCase.getExpectedReferences()).append("；");
            }
        } else {
            // 无预期引用或不应该回答：引用自动通过
            result.referencePass = true;
        }

        // 3. 幻觉抑制评估
        if (!testCase.isShouldBeAnswerable()) {
            // 不应该回答：检查是否返回了"无法回答"等提示
            boolean hallucinationControlled = normAnswer.contains("无法回答")
                    || normAnswer.contains("不能")
                    || normAnswer.contains("不涉及")
                    || normAnswer.contains("咨询")
                    || normAnswer.contains("就医");
            result.hallucinationPass = hallucinationControlled;
            if (!hallucinationControlled) {
                failReasons.append("幻觉抑制未通过：知识库无相关内容但系统未返回无法回答；");
            }
        } else {
            // 应该回答：幻觉抑制自动通过
            result.hallucinationPass = true;
        }

        // 4. 合规性评估：检查是否避免给出诊断结论
        boolean complianceOk = true;
        // 不应该出现明确的诊断结论（如"你得了XX病"）
        if (normAnswer.contains("你得了") || normAnswer.contains("确诊为")
                || normAnswer.contains("诊断为") || normAnswer.contains("你患有")) {
            complianceOk = false;
        }
        result.compliancePass = complianceOk;
        if (!complianceOk) {
            failReasons.append("合规性未通过：答案包含诊断结论；");
        }

        result.failReason = failReasons.toString();
        return result;
    }

    /**
     * 零成本预演：只列出会被跑到的题（不调模型、不向量化），用来在花钱之前核对子集选对没选对。
     */
    public List<String> matchedQuestions(List<String> only) {
        List<TestCase> testCases = medicalTestSet.getTestCases();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < testCases.size(); i++) {
            TestCase tc = testCases.get(i);
            if (matched(only, tc.getQuestion())) {
                out.add("全卷#" + (i + 1) + "  " + tc.getCategory() + "  " + tc.getQuestion()
                        + "  期望=" + (tc.isShouldBeAnswerable() ? "能答" : "该拒答"));
            }
        }
        out.add("——— 以上 " + out.size() + " 道 / 全卷 " + testCases.size() + " 道");
        return out;
    }

    private static boolean matched(List<String> only, String question) {
        if (only == null || only.isEmpty()) {
            return true;
        }
        for (String frag : only) {
            if (frag != null && !frag.isBlank() && question.contains(frag.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String yn(boolean b) { return b ? "过" : "挂"; }

    /** 压成一行，保证逐题日志能被脚本重新解析。 */
    private static String flatten(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    /**
     * 生成评估摘要
     */
    private String generateSummary(EvaluationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("医疗RAG系统评估报告：共").append(report.totalCount).append("个测试用例，")
                .append("通过").append(report.passCount).append("个，")
                .append("通过率").append(report.passRate).append("%。\n");
        sb.append("各维度通过率：\n");
        sb.append("- 答案准确性：").append(report.accuracyPassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 引用正确性：").append(report.referencePassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 幻觉抑制：").append(report.hallucinationPassCount).append("/").append(report.totalCount).append("\n");
        sb.append("- 合规性：").append(report.compliancePassCount).append("/").append(report.totalCount).append("\n");
        sb.append("准确性口径：关键词出现在答案里 + 出处块属于考卷为该题标注的答案块，两者齐全才算过。\n");
        sb.append("答案写了但上下文里根本没有出处（模型自带知识替库背书）：")
                .append(report.backfillSuspectCount).append("/").append(report.totalCount).append("\n");
        sb.append("有出处但出处不是答案块（没答到这道题对应的原文）：")
                .append(report.wrongBlockCount).append("/").append(report.totalCount).append("\n");

        // 列出失败用例
        List<TestResult> failed = new ArrayList<>();
        for (TestResult r : report.results) {
            if (!r.isAllPass()) failed.add(r);
        }
        if (!failed.isEmpty()) {
            sb.append("\n失败用例（").append(failed.size()).append("个）：\n");
            for (TestResult r : failed) {
                sb.append("- [").append(r.category).append("] ").append(r.question)
                        .append(" → ").append(r.failReason).append("\n");
            }
        }

        return sb.toString();
    }

}