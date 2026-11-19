package org.ragai.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 考卷自身的结构守门：判分口径再对，题目标注漂了也白测。
 * 数字锚在 C1 那轮全量跑（61 题，2026-10-16，README「评测口径」§2 的基线行）；
 * 加题/减题时要一起改 README 的基线数字和这里的期望值，否则这条测试就是提醒你不要偷偷改考卷。
 * 纯读内存里的考卷，不连库、不打模型。
 */
class MedicalTestSetStructureTest {

    private static final List<TestCase> CASES = new MedicalTestSet().getTestCases();

    @Test
    @DisplayName("题量与分类构成 = C1 基线那一版")
    void examComposition() {
        assertEquals(61, CASES.size(), "题量变了：C1 基线是 61 题，README 与判分数字要一起重测");
        Map<String, Integer> byCategory = countsByCategory();
        assertEquals(Map.of("事实查询", 24, "口语查询", 29, "综合分析", 5,
                "幻觉抑制", 2, "合规测试", 1), byCategory);
    }

    @Test
    @DisplayName("58 道作答题都有关键词、预期引用和答案块标注")
    void answerableCasesAreFullyAnnotated() {
        List<String> problems = new ArrayList<>();
        for (TestCase c : CASES) {
            if (!c.isShouldBeAnswerable()) {
                continue;
            }
            if (c.getExpectedKeywords() == null || c.getExpectedKeywords().isEmpty()) {
                problems.add(c.getQuestion() + " 缺 expectedKeywords");
            }
            if (c.getExpectedReferences() == null || c.getExpectedReferences().isEmpty()) {
                problems.add(c.getQuestion() + " 缺 expectedReferences");
            }
            if (c.getAnswerChunks() == null || c.getAnswerChunks().isEmpty()) {
                problems.add(c.getQuestion() + " 缺答案块标注（判分器无法核对出处）");
            }
        }
        assertTrue(problems.isEmpty(), () -> "标注缺口 " + problems);
    }

    @Test
    @DisplayName("答案块主键写法统一为 文件名#块号（判分器按这个字符串等值比对）")
    void answerChunkKeyFormat() {
        List<String> bad = new ArrayList<>();
        for (TestCase c : CASES) {
            for (String chunk : c.getAnswerChunks()) {
                if (chunk == null || !chunk.matches("[^#/]+#\\d+")) {
                    bad.add(c.getQuestion() + " → " + chunk);
                }
            }
        }
        assertTrue(bad.isEmpty(), () -> "块主键格式不对，judgeAccuracy 永远认不出答案块：" + bad);
    }

    @Test
    @DisplayName("3 道超纲题不带答案块也不带预期引用，类别就是它的判据")
    void negativeCasesShape() {
        List<TestCase> negatives = CASES.stream()
                .filter(c -> !c.isShouldBeAnswerable()).toList();
        assertEquals(3, negatives.size());
        for (TestCase c : negatives) {
            assertTrue(c.getAnswerChunks().isEmpty(), () -> "超纲题不该有答案块：" + c.getQuestion());
            assertTrue(c.getExpectedReferences().isEmpty(), () -> "超纲题不该有预期引用：" + c.getQuestion());
            assertFalse(c.getExpectedKeywords().isEmpty(),
                    () -> "超纲题的关键词字段留空也没人读，拒答判据是固定原话（见 EvalPromptGuardTest）");
        }
    }

    @Test
    @DisplayName("同一问题不重复出现（日志按题号回捞答案时重复题会串）")
    void questionsAreUnique() {
        Map<String, Integer> seen = new HashMap<>();
        for (TestCase c : CASES) {
            seen.merge(c.getQuestion(), 1, Integer::sum);
        }
        List<String> dup = seen.entrySet().stream().filter(e -> e.getValue() > 1)
                .map(e -> e.getKey() + "×" + e.getValue()).collect(Collectors.toList());
        assertTrue(dup.isEmpty(), () -> "重复题目：" + dup);
    }

    @Test
    @DisplayName("书面问法与口语问法成对出现：同一组答案块两套问法，才是自造题偏袒关键词检索的量尺")
    void factAndOralShareGroundTruth() {
        Map<String, List<String>> blocksByCategory = new HashMap<>();
        for (TestCase c : CASES) {
            // 排序后再拼 key：判分器按集合成员判出处命中，数组顺序无语义。
            // 不排序会把「同一集合、两种排列」当成两组，基线数字就随标注数组的书写顺序漂。
            List<String> sorted = new ArrayList<>(c.getAnswerChunks());
            java.util.Collections.sort(sorted);
            String key = String.join("|", sorted);
            blocksByCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(c.getCategory());
        }
        long paired = blocksByCategory.entrySet().stream()
                .filter(e -> e.getValue().contains("事实查询") || e.getValue().contains("综合分析"))
                .filter(e -> e.getValue().contains("口语查询"))
                .count();
        // 实测 28 组。29 道口语题里有一道不与书面版共用块集：
        //   非HDL-C 口语版（G_NON_HDL_ORAL）额外认 血脂#35/#37/#52 表3 参考水平，与事实版
        //   G_NON_HDL 集合不同，按集合比不配对。
        // G_BP_TARGET 与 G_VERY_OLD 在 961 块代曾是同一集合（基线 27）：那时 基层#11/#19 整块
        //   同时含 <80岁 130/80 与 ≥80岁 150/90 两档。V4 句子优先分块把两档拆进不同块——
        //   <80岁 130/80 落在 基层#11，≥80岁 150/90、可耐受 140/90 落在 基层#21——于是
        //   G_BP_TARGET 认 基层#11、G_VERY_OLD 不认，两组答案集真实发散，基线回到 28。
        //   这是切分变细带来的真区分，不是放宽断言（证据：scratch/phase3_relocate.out [3]）。
        // 数字再动就是有人在偷偷拆对照，或又有一组答案块收敛/发散了。
        assertEquals(28, paired, () -> "同时被书面和口语问法问到的答案块组数变了（现测 " + paired
                + " 组，基线 28 组）：书面/口语两套问法必须共用同一份 ground truth");
    }

    private static Map<String, Integer> countsByCategory() {
        Map<String, Integer> out = new HashMap<>();
        for (TestCase c : CASES) {
            out.merge(c.getCategory(), 1, Integer::sum);
        }
        return out;
    }
}
