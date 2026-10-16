package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准确性判分器（EvaluationService.judgeAccuracy）的口径守门测试：
 * 关键词在答案里 + 在本次上下文里 + 出处那块正是考卷标注的答案块，三者齐全才算过。
 * 场景与断言同 scratch/AccuracyCheck.java（12 场景 16 断言）；块文本是为覆盖分支造的，
 * #2/#10/#11/#12 四条直接抄自 2026-10-13 的实测错题形态（见 scratch/f41_blocks.txt、scratch/xz_chk.out）。
 * 纯静态方法，不连库、不打模型。
 */
class AccuracyJudgeTest {

    private static final String XZ = "中国血脂管理指南2023年.pdf";
    private static final String YJ = "中国心血管病一级预防指南2020.pdf";

    private static Document doc(String file, int chunkIndex, String text) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "/data/pdf/" + file);
        meta.put("file_name", file);
        meta.put("chunk_index", chunkIndex);
        return new Document("id-" + file + "-" + chunkIndex, text, meta);
    }

    private static List<String> normDocs(List<Document> docs) {
        List<String> out = new ArrayList<>();
        for (Document d : docs) {
            out.add(EvaluationService.normalizeForMatch(d.getText()));
        }
        return out;
    }

    /** 把每条子断言的失败信息攒起来一次性报，改口径时能一眼看到全部影响面。 */
    private static void assertVerdict(String label, EvaluationService.AccuracyVerdict v, Executable... checks) {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < checks.length; i++) {
            try {
                checks[i].execute();
            } catch (Throwable t) {
                bad.add("[" + label + "] 断言" + (i + 1) + "：" + t.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad)
                + "\n  evidence=" + v.evidence() + "\n  failReason=" + v.failReason());
    }

    @Test
    @DisplayName("出处就是答案块：判过，且出处指到具体那块")
    void sourcedFromAnswerChunkPasses() {
        List<Document> docs = List.of(
                doc(YJ, 7, "他汀类药物是降脂治疗的基础用药。"),
                doc(XZ, 55, "首次服用降脂药物者，建议用药4~6周内复查血脂、肝酶和肌酸激酶。"));
        var v = EvaluationService.judgeAccuracy(
                EvaluationService.normalizeForMatch("建议您在开始用药后 4–6 周内复查血脂。"),
                normDocs(docs), docs, List.of("4~6周"), List.of(XZ + "#55"));
        assertVerdict("答案块", v,
                () -> assertTrue(v.pass(), "出处就是答案块却判挂"),
                () -> assertFalse(v.wrongBlock()),
                () -> assertFalse(v.backfill()),
                () -> assertTrue(v.evidence().contains("答案块") && v.evidence().contains("#55"),
                        "出处没写成答案块：" + v.evidence()));
    }

    @Test
    @DisplayName("实测 #43 形态：有出处但出处不是答案块 → 判挂，且不算是库外背书")
    void wrongBlockIsNotBackfill() {
        List<Document> docs = List.of(
                doc(XZ, 32, "ASCVD低危人群：LDL-C理想水平<2.6mmol/L，合适水平<3.4mmol/L。"),
                doc(XZ, 90, "糖尿病患者的血脂管理需按危险分层。"));
        String ans = EvaluationService.normalizeForMatch(
                "FH患者通常建议LDL-C<2.6 mmol/L，具体目标需结合心血管风险分层。");
        var v = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs, List.of("2.6"),
                List.of(XZ + "#107", XZ + "#108"));
        assertVerdict("错块", v,
                () -> assertFalse(v.pass()),
                () -> assertTrue(v.wrongBlock(), "该标成出处不在答案块"),
                () -> assertFalse(v.backfill(), "错块不该被算成库外背书"),
                () -> assertTrue(v.failReason().contains(XZ + "#107") && v.failReason().contains(XZ + "#108"),
                        "失败原因要写明考卷标注的答案块"),
                () -> assertFalse(v.failReason().contains("模型自带知识"), "错块不能说成模型自带知识"));
    }

    @Test
    @DisplayName("出处块与答案块只差 1 号：仍判挂，但给出「可能考卷漏标」线索")
    void neighborChunkHintsMissingAnnotation() {
        List<Document> docs = List.of(doc(XZ, 106, "成人FH患者LDL-C目标值：不伴ASCVD应<2.6mmol/L。"));
        String ans = EvaluationService.normalizeForMatch(
                "FH患者通常建议LDL-C<2.6 mmol/L，具体目标需结合心血管风险分层。");
        var v = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs, List.of("2.6"),
                List.of(XZ + "#107", XZ + "#108"));
        assertVerdict("邻块", v,
                () -> assertFalse(v.pass()),
                () -> assertTrue(v.wrongBlock()),
                () -> assertTrue(v.evidence().contains("可能考卷漏标"),
                        "相邻块要给出漏标线索：" + v.evidence()));
    }

    @Test
    @DisplayName("同一关键词既在答案块又在别的块：有一处是答案块就判过")
    void answerChunkWinsOverOtherBlocks() {
        List<Document> docs = List.of(
                doc(XZ, 32, "ASCVD低危人群LDL-C理想水平<2.6mmol/L。"),
                doc(XZ, 107, "成人FH患者LDL-C目标值：不伴ASCVD应<2.6mmol/L；伴亚临床ASCVD应<1.8mmol/L。"));
        String ans = EvaluationService.normalizeForMatch(
                "FH患者通常建议LDL-C<2.6 mmol/L，具体目标需结合心血管风险分层。");
        var v = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs, List.of("2.6"),
                List.of(XZ + "#107", XZ + "#108"));
        assertVerdict("答案块优先", v,
                () -> assertTrue(v.pass()),
                () -> assertTrue(v.evidence().contains("#107"), "出处应指到答案块"));
    }

    @Test
    @DisplayName("答案写了、上下文一块都没有：库外背书，不是错块")
    void unsourcedIsBackfill() {
        List<Document> docs = List.of(doc(XZ, 90, "糖尿病患者的血脂管理需按危险分层。"));
        String ans = EvaluationService.normalizeForMatch("通常推荐LDL-C较基线降低≥50%且绝对值<1.8 mmol/L。");
        var v = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs, List.of("1.8mmol/L"),
                List.of(XZ + "#107"));
        assertVerdict("库外背书", v,
                () -> assertFalse(v.pass()),
                () -> assertTrue(v.backfill()),
                () -> assertFalse(v.wrongBlock()),
                () -> assertTrue(v.failReason().contains("模型自带知识"),
                        "失败原因要写明模型自带知识：" + v.failReason()));
    }

    @Test
    @DisplayName("答案里根本没有关键词：判挂，且背书/错块两个标记都为假")
    void missingKeywordIsNeitherFlag() {
        List<Document> docs = List.of(doc(XZ, 90, "糖尿病患者的血脂管理需按危险分层。"));
        var v = EvaluationService.judgeAccuracy(
                EvaluationService.normalizeForMatch("根据现有知识库资料，无法回答该问题。"),
                normDocs(docs), docs, List.of("1.8mmol/L"), List.of(XZ + "#107"));
        assertVerdict("未命中关键词", v,
                () -> assertFalse(v.pass()),
                () -> assertFalse(v.backfill()),
                () -> assertFalse(v.wrongBlock()));
    }

    @Test
    @DisplayName("多关键词：一个落在答案块、另一个无出处 → 有任一实证即判过")
    void anySourcedKeywordIsEnough() {
        String ans = EvaluationService.normalizeForMatch("FH患者LDL-C<2.6 mmol/L，建议1.8 mmol/L起步强化。");
        List<Document> docs = List.of(
                doc(XZ, 107, "成人FH患者LDL-C目标值：不伴ASCVD应<2.6mmol/L。"),
                doc(XZ, 108, "伴亚临床ASCVD应<1.8mmol/L且较基线降幅≥50%。"));
        var v = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs, List.of("2.6", "1.8"),
                List.of(XZ + "#107", XZ + "#108"));
        assertVerdict("任一实证", v,
                () -> assertTrue(v.pass()),
                () -> assertFalse(v.backfill()));
    }

    @Test
    @DisplayName("闸门拦掉上下文（0 块）时不抛异常，判挂并写明块数")
    void emptyContextDoesNotThrow() {
        String ans = EvaluationService.normalizeForMatch("通常推荐LDL-C较基线降低≥50%且绝对值<1.8 mmol/L。");
        var v = EvaluationService.judgeAccuracy(ans, List.of(), List.of(), List.of("1.8mmol/L"),
                List.of(XZ + "#107"));
        assertVerdict("空上下文", v,
                () -> assertFalse(v.pass()),
                () -> assertTrue(v.backfill()),
                () -> assertTrue(v.failReason().contains("0 块上下文"),
                        "文案里块数应为 0：" + v.failReason()));
    }

    @Test
    @DisplayName("出处块没有 metadata 时不抛异常，出处名写成 <unknown> 并算错块")
    void documentWithoutMetadata() {
        String ans = EvaluationService.normalizeForMatch("通常推荐LDL-C较基线降低≥50%且绝对值<1.8 mmol/L。");
        Document d9 = new Document("id9", "绝对值<1.8 mmol/L。", new LinkedHashMap<>());
        var v = EvaluationService.judgeAccuracy(ans, normDocs(List.of(d9)), List.of(d9),
                List.of("1.8mmol/L"), List.of(XZ + "#107"));
        assertVerdict("无元数据", v,
                () -> assertFalse(v.pass()),
                () -> assertTrue(v.wrongBlock()),
                () -> assertTrue(v.evidence().contains("<unknown>"),
                        "认不出块主键时要留 <unknown> 而不是抛异常：" + v.evidence()));
    }

    @Test
    @DisplayName("改标注后的 #11：21% 出处 YJ#88 已进答案块集合 → 判过")
    void relabeledCase11() {
        List<Document> docs = List.of(
                doc(YJ, 88, "荟萃分析结果显示，LDL-C每降低1mmol/L、持续5年，人群心血管病风险降低21%。"),
                doc(XZ, 40, "荟萃分析显示LDL-C每降低1mmol/L，ASCVD事件降低20%~23%。"));
        var v = EvaluationService.judgeAccuracy(
                EvaluationService.normalizeForMatch("LDL-C每降低1 mmol/L，心血管事件风险约降低21%。"),
                normDocs(docs), docs, List.of("20%", "23%", "21%"), List.of(XZ + "#40", YJ + "#88"));
        assertVerdict("#11 改标注", v,
                () -> assertTrue(v.pass()),
                () -> assertTrue(v.evidence().contains("#88"), "出处应指到 YJ#88"));
    }

    @Test
    @DisplayName("口语题认表3（XZ#32）后判过；同一上下文走事实题严格集合仍判挂")
    void oralSetIsLooserThanFactSet() {
        List<Document> docs = List.of(
                doc(XZ, 32, "表3 中国ASCVD一级预防低危人群主要血脂指标的参考标准：非HDL-C 理想水平<3.4，"
                        + "合适水平<4.1，边缘升高≥4.1且<4.9，升高≥4.9。"),
                doc(XZ, 17, "非HDL-C是指血液中除HDL以外其他脂蛋白所含胆固醇的总和，计算公式为TC减去HDL-C。"));
        String ans = EvaluationService.normalizeForMatch(
                "非HDL-C 理想水平<3.4 mmol/L，合适水平<4.1 mmol/L，边缘升高≥4.1且<4.9，升高≥4.9。");
        var oral = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs,
                List.of("0.8", "3.4", "4.1", "4.9"),
                List.of(YJ + "#75", YJ + "#76", XZ + "#47", XZ + "#92", XZ + "#32"));
        assertVerdict("口语题认表3", oral,
                () -> assertTrue(oral.pass()),
                () -> assertTrue(oral.evidence().contains(XZ + "#32")));

        var fact = EvaluationService.judgeAccuracy(ans, normDocs(docs), docs,
                List.of("0.8", "非HDL"), List.of(YJ + "#75", YJ + "#76", XZ + "#47", XZ + "#92"));
        assertVerdict("事实题不认表3", fact,
                () -> assertFalse(fact.pass(), "放宽渗到事实题了：目标值不等于低危人群参考标准"),
                () -> assertTrue(fact.wrongBlock()));
    }

    @Test
    @DisplayName("块主键写法与考卷标注一致：文件名#chunk_index，路径前缀要剥掉")
    void chunkKeyFormatMatchesExam() {
        List<Document> docs = List.of(doc(XZ, 55, "用药4~6周内复查血脂。"));
        var v = EvaluationService.judgeAccuracy(
                EvaluationService.normalizeForMatch("建议4~6周内复查血脂"), normDocs(docs), docs,
                List.of("4~6周"), List.of(XZ + "#55"));
        // source 元数据带的是 /data/pdf/ 全路径，answerChunks 只写文件名；对不上就永远判不成答案块
        assertEquals(true, v.pass(), "块主键对不上考卷标注：" + v.evidence());
    }
}
