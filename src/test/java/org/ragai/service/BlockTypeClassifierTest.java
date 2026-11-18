package org.ragai.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 块类型打标的守门：判据是行形状启发式，钉住的不是「每个阈值」而是两类不许翻的车——
 * ① 完整句子的正文（中/英）永远不许被标成 table/figure（误标会让后续策略把正常答案块降权）；
 * ② 四种真实压平形状（推荐类别表、联合用药表、数字表、竖排流程图，取自 scratch/v4_prod_check.out
 * 里劈在句中的那 5 个答案块）必须被认出来。认不准标 text 是纪律，不是漏报。
 */
class BlockTypeClassifierTest {

    private static final String PROSE_ZH = """
            高血压患者的血压控制目标应个体化。一般高血压患者应将血压降至140/90 mmHg以下；
            能耐受者和部分高危及以上患者，可进一步降至130/80 mmHg以下。
            对于老年高血压患者，应根据合并症情况和耐受性确定降压目标，避免过度降压带来的风险。
            生活方式干预应贯穿治疗的始终，包括限盐、减重、规律运动和戒烟限酒。""";

    private static final String PROSE_EN = """
            Pharmacological antihypertensive treatment is recommended for adults with
            systolic blood pressure of 140 mmHg or higher. Treatment should be initiated
            with a single agent in most patients. Combination therapy is preferred when
            blood pressure remains above target after titration. Lifestyle modification
            should accompany drug treatment in all patients. Follow-up visits are needed
            every three to six months once blood pressure is controlled.""";

    /** 一级预防#73：推荐类别/证据等级表压平后的真实形状 */
    private static final String TABLE_RECOMMENDATION = """
            推荐
            类别
            Ⅰ
            Ⅰ
            Ⅰ
            Ⅱa
            Ⅱa
            Ⅱb
            Ⅱa
            Ⅲ
            证据
            级别
            B
            A
            B
            B
            B""";

    /** 一级预防#77：联合用药表，行不短但整块没有一个句末标点 */
    private static final String TABLE_DRUG_COMBO = """
            氟伐他汀40 mg+依折麦布
            匹伐他汀1 mg+依折麦布
            血脂康1.2 g+依折麦布
            阿托伐他汀40 mg+依折麦布
            瑞舒伐他汀10 mg+依折麦布
            辛伐他汀20 mg+依折麦布""";

    private static final String TABLE_NUMERIC = """
            收缩压（mmHg）
            120
            130
            140
            160
            180
            舒张压（mmHg）
            80
            85
            90
            100
            110""";

    /** 基层#12：转诊流程图竖排文字压平后的真实形状 */
    private static final String FIGURE_FLOWCHART = """
            病情平稳\s
            或经治疗
            稳定的原\s
            发性高血\s
            压患者
            建\s
            议下转基\s
            层
            下转
            基层\s
            纳入\s
            管理""";

    @Test
    @DisplayName("中文正文永远是 text：有完整句子就不许标成表格/图")
    void chineseProseIsText() {
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(PROSE_ZH));
    }

    @Test
    @DisplayName("英文正文永远是 text：句末 '.' 后面是空白才算句号，小数点不算")
    void englishProseIsText() {
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(PROSE_EN));
    }

    @Test
    @DisplayName("带数字/小数/单位的正文仍是 text：3.01 和 140/90 不是表格信号")
    void proseWithNumbersIsText() {
        String s = """
                研究显示 LDL-C 每降低 1 mmol/L，主要心血管事件风险降低约 22%。
                比值比为 0.78（95%CI 0.75~0.81），P 值小于 0.001。
                他汀类药物可使 LDL-C 降低 30%~50%，具体幅度取决于品种与剂量。
                用药 4~6 周后应复查血脂，评估达标情况并调整方案。""";
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(s));
    }

    @Test
    @DisplayName("推荐类别表（Ⅰ/Ⅱa/Ⅲ 独立单元格）→ table")
    void recommendationTableIsTable() {
        assertEquals(BlockTypeClassifier.TABLE, BlockTypeClassifier.classify(TABLE_RECOMMENDATION));
    }

    @Test
    @DisplayName("联合用药表（整块零句末、行长均匀偏短）→ table")
    void drugComboTableIsTable() {
        assertEquals(BlockTypeClassifier.TABLE, BlockTypeClassifier.classify(TABLE_DRUG_COMBO));
    }

    @Test
    @DisplayName("数字单元格占一半以上的检验值表 → table")
    void numericTableIsTable() {
        assertEquals(BlockTypeClassifier.TABLE, BlockTypeClassifier.classify(TABLE_NUMERIC));
    }

    @Test
    @DisplayName("竖排流程图压平块（几乎全是 ≤6 字短行）→ figure")
    void verticalFlowchartIsFigure() {
        assertEquals(BlockTypeClassifier.FIGURE, BlockTypeClassifier.classify(FIGURE_FLOWCHART));
    }

    @Test
    @DisplayName("表格脚注是完整句子 → text（混排块认不准就不标，与章节号同纪律）")
    void tableFootnoteProseIsText() {
        String s = """
                注：HDL-C为高密度脂蛋白胆固醇；LDL-C为低密度脂蛋白胆固醇。
                表中数值均为空腹静脉血测定结果，单位为 mmol/L。
                不同实验室的参考区间可能存在差异，应以当地实验室标准为准。""";
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(s));
    }

    @Test
    @DisplayName("null/空白/行数太少 → text，不抛异常")
    void degenerateInputIsText() {
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(null));
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify(""));
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify("  \n  "));
        assertEquals(BlockTypeClassifier.TEXT, BlockTypeClassifier.classify("Ⅰ\nⅡa\nⅢ"));
    }

    @Test
    @DisplayName("确定性 + 值域封闭：同一正文两次结果相同，且只会是三个值之一")
    void deterministicAndClosedValueSet() {
        Set<String> allowed = Set.of(BlockTypeClassifier.TEXT, BlockTypeClassifier.TABLE, BlockTypeClassifier.FIGURE);
        for (String s : List.of(PROSE_ZH, PROSE_EN, TABLE_RECOMMENDATION, TABLE_DRUG_COMBO,
                TABLE_NUMERIC, FIGURE_FLOWCHART)) {
            String a = BlockTypeClassifier.classify(s);
            assertEquals(a, BlockTypeClassifier.classify(s));
            assertTrue(allowed.contains(a), () -> "非法值：" + a);
        }
    }

    @Test
    @DisplayName("入库闸门给每块写 block_type，且不影响 chunk_hash（hash 只算正文）")
    void gateWritesBlockTypeWithoutTouchingHash() {
        RagInitializer init = new RagInitializer(null, null, null, null);
        TextSegment prose = TextSegment.from(PROSE_ZH, new Metadata());
        TextSegment table = TextSegment.from(TABLE_RECOMMENDATION, new Metadata());
        List<TextSegment> kept = init.filterAndDeduplicateChunks(
                List.of(prose, table), new String[]{"self"}, "  ");
        assertEquals(2, kept.size());
        assertEquals(BlockTypeClassifier.TEXT, kept.get(0).metadata().getString("block_type"));
        assertEquals(BlockTypeClassifier.TABLE, kept.get(1).metadata().getString("block_type"));
        String hashProse = kept.get(0).metadata().getString("chunk_hash");
        String hashTable = kept.get(1).metadata().getString("chunk_hash");
        assertNotNull(hashProse);
        assertNotNull(hashTable);
        // 同一正文再过一遍闸门，hash 不因 metadata 里多了 block_type 而变
        List<TextSegment> again = init.filterAndDeduplicateChunks(
                List.of(TextSegment.from(PROSE_ZH, new Metadata())), new String[]{"self"}, "  ");
        assertEquals(hashProse, again.get(0).metadata().getString("chunk_hash"));
    }
}