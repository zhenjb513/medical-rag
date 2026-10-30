package org.ragai.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 医疗测试集
 * 基于mkbAI已入库的临床指南设计测试问题
 * <p>
 * answerChunks 是「答案块标注」：每条都是逐字定位原文得到的 文件名#chunk_index（见 /bm25/locate），
 * 也就是"这个问题的标准答案确实写在这几块里"。改问题或重灌文档后要重新核对这些块号。
 * expectedKeywords 只用于生成质量评估，证明不了检索是否真的找回了答案。
 * <p>
 * 同一组答案块会问两遍：category=事实查询/综合分析 用指南里的书面说法问，
 * category=口语查询 用患者口吻问（不带原文术语）。两组的 ground truth 完全相同，
 * 差别只在问法——这就是「自造题偏袒关键词检索」这件事的量尺。
 * <p>
 * 注意：请根据自己实际入库的指南内容，调整expectedKeywords
 * 如果某个问题在自己的指南中没有对应答案，请将shouldBeAnswerable改为false
 */
@Component
public class MedicalTestSet {

    private static final String YJ = "中国心血管病一级预防指南2020.pdf";
    private static final String JC = "国家基层高血压防治管理指南2025版.pdf";
    private static final String XZ = "中国血脂管理指南2023年.pdf";
    private static final String WHO = "02-WHO成人高血压药物治疗指南2021.pdf";

    /**
     * 答案块：同一个块可以被两种问法问出来，抽成常量避免两处标注不一致
     */
    private static final List<String> G_BP_TARGET = all(at(YJ, 63), at(JC, 8, 17));
    private static final List<String> G_SALT = all(at(YJ, 52, 55), at(JC, 19));
    private static final List<String> G_ACTIVITY = at(YJ, 42, 43);
    // 全库逐字写出「BMI<24 + 腰围90/85」的只有这三块；JC#16、#59 只讲测腰围不讲目标，不算答案
    private static final List<String> G_WEIGHT = all(at(YJ, 52, 56), at(JC, 19));
    private static final List<String> G_HR = at(YJ, 58);
    private static final List<String> G_VERY_OLD = all(at(JC, 8, 17), at(YJ, 63));
    private static final List<String> G_DM_BP = at(YJ, 63, 66);
    private static final List<String> G_DX_OFFICE = at(JC, 12, 14);
    private static final List<String> G_DX_HOME = at(JC, 14, 15);
    private static final List<String> G_STANDING = at(JC, 13);
    // 以下块号已按 2026-10-12 重灌后的新库（501 块）逐字定位重标，核对过程见 scratch/sent.txt + scratch/newblocks.txt
    // 答案块按逐字核对改过：整句「LDL-C每降低1mmol/L，ASCVD事件降低20%~23%」在 XZ#40，
    // 整句「LDL-C每降低1mmol/L、持续5年，人群心血管病风险降低21%」在 YJ#88（scratch/xz_chk.out）。
    // 原来标的 XZ#39 和 YJ#87 里 20%/21%/23% 一个都没有：XZ#39 是血脂检查重点对象+治疗原则要点，
    // YJ#87 是 HOPE-3/JUPITER 研究段。
    private static final List<String> G_LDL_BENEFIT = all(at(XZ, 40), at(YJ, 88));
    // 逐字写出「非HDL-C 目标 = LDL-C + 0.8mmol/L」的是这四块（phrase=0.8mmol/L 全库命中 YJ#75/#76、XZ#47/#92）
    private static final List<String> G_NON_HDL = all(at(YJ, 75, 76), at(XZ, 47, 92));
    // 口语题「化验单上…非高密度脂蛋白的指标该看多少」额外认 XZ#32：表3 里非HDL-C 那一行逐字是
    // 理想<3.4 / 合适<4.1 / 边缘升高≥4.1且<4.9 / 升高≥4.9（scratch/f41_blocks.txt），这正面回答了「看多少」。
    // 事实题「非HDL-C的控制目标是多少？」不共用这块：XZ#32 的要点提示写明「参考水平仅适用于ASCVD低危人群」，
    // 那是分层参考标准、不是干预目标，拿它答目标题等于答错了段落。
    private static final List<String> G_NON_HDL_ORAL = all(G_NON_HDL, at(XZ, 32));
    // /bm25/locate 逐字证（scratch/loc96.out）：表15 糖尿病患者血脂目标值的 1.4/1.8/2.6 三个数字全在 XZ#92；
    // XZ#90 另有末句「风险为中、低危的糖尿病患者，均应将LDL-C控制在 2.6 mmol/L 以下（表15）」，XZ#91 只在讲选药、无目标数值
    // 口语题「我血糖偏高」判分口径维持严格（2026-10-13 定）：不把 YJ#76 当等价答案块。
    // 那次挂是检索没召回这四个答案块（本次 16 块上下文里一个都没有，scratch/f42_retrieve.out），
    // 而 YJ#76 的 1.4 只对应「糖尿病+已确诊ASCVD」的超高危档，血糖偏高者按表15 应落 2.6/1.8（scratch/f42_blocks.txt）；
    // 同一答案块集合下的事实题「糖尿病患者的LDL-C控制目标是多少？」本轮是过的（1.4→XZ#92，上下文第3块），
    // 所以问题在「血糖偏高」这个口语措辞召不回表15，不在标注。
    private static final List<String> G_DM_LDL = all(at(XZ, 90, 91, 92), at(YJ, 72));
    // FH 推荐表清洗后被劈成相邻两块，两块都算答案
    private static final List<String> G_FH = at(XZ, 107, 108);
    // 「表17脑卒中患者的降脂治疗推荐建议」全库命中块数=1 且是 #97；2.2mmol/L 只命中 #88/#97，
    // #96 是高龄老年他汀那段，一个目标值都没有（旧标注抄的是重灌前的块号）
    private static final List<String> G_STROKE_LIPID = at(XZ, 97);
    // 逐字含「依折麦布」的块仍是 12 个，只有真写出「单药不达标→联合」这一步的才算答案，其余在讲机制或试验证据
    private static final List<String> G_STATIN_COMBO = all(at(YJ, 83, 85, 86), at(JC, 42), at(XZ, 50));
    // 全库逐字含「4~6周」的只有两块：基层高血压指南#44 与血脂指南#55，后者是同一条复查建议的另一本指南写法
    private static final List<String> G_STATIN_RECHECK = all(at(JC, 44), at(XZ, 55));
    private static final List<String> G_DASH = all(at(YJ, 54), at(JC, 19));
    // 陷阱：一级预防#11 也逐字含「23.2%」，但那是血压130~139/80~89的人群比例，不是患病率，不能当等价答案块
    private static final List<String> G_HT_TREND = at(YJ, 10);
    private static final List<String> G_LIPID_TREND = at(YJ, 11);
    private static final List<String> G_SECOND_HAND = at(YJ, 47);
    private static final List<String> G_QUIT_SMOKING = at(YJ, 46);
    private static final List<String> G_REFERRAL_FOLLOWUP = at(JC, 56);
    private static final List<String> G_WHO_TARGET = at(WHO, 7, 29);
    private static final List<String> G_PRIMARY_PREVENTION = at(YJ, 9, 13, 6);
    private static final List<String> G_LIFESTYLE = all(at(JC, 18, 19), at(YJ, 52));
    private static final List<String> G_ELDERLY = all(at(YJ, 63, 67), at(JC, 8));
    private static final List<String> G_SMOKING = at(YJ, 45, 46, 47, 48);
    private static final List<String> G_RISK_MODEL = at(YJ, 17, 22);

    public List<TestCase> getTestCases() {
        List<TestCase> testCases = new ArrayList<>();

        // ========== 书面问法（术语直接来自指南原文） ==========

        add(testCases, "高血压患者的降压目标是多少？",
                Arrays.asList("130/80", "140/90"), Arrays.asList(YJ, JC), G_BP_TARGET, "事实查询");

        add(testCases, "每人每日食盐摄入量建议控制在多少克？",
                Arrays.asList("5g", "食盐"), Arrays.asList(YJ, JC), G_SALT, "事实查询");

        add(testCases, "成人每周至少需要多少分钟中等强度身体活动才能降低心血管病风险？",
                Arrays.asList("150", "75"), Arrays.asList(YJ), G_ACTIVITY, "事实查询");

        add(testCases, "控制体重时BMI和腰围的目标是多少？",
                Arrays.asList("BMI", "24", "90", "85"), Arrays.asList(YJ, JC), G_WEIGHT, "事实查询");

        add(testCases, "中等强度身体活动对应的心率范围怎么计算？",
                Arrays.asList("60%", "70%", "220"), Arrays.asList(YJ), G_HR, "事实查询");

        add(testCases, "80岁以上高龄高血压患者的降压目标是多少？",
                Arrays.asList("150/90", "140/90"), Arrays.asList(JC, YJ), G_VERY_OLD, "事实查询");

        add(testCases, "糖尿病患者的血压控制目标是多少？",
                Arrays.asList("130/80", "糖尿病"), Arrays.asList(YJ, JC), G_DM_BP, "事实查询");

        add(testCases, "诊室血压诊断高血压的标准是多少？",
                Arrays.asList("140", "90"), Arrays.asList(JC), G_DX_OFFICE, "事实查询");

        add(testCases, "家庭血压自测的高血压诊断标准平均值是多少？",
                Arrays.asList("135/85", "家庭血压"), Arrays.asList(JC), G_DX_HOME, "事实查询");

        add(testCases, "什么情况下测量血压需要加测站立位血压？",
                Arrays.asList("站立位", "体位性低血压"), Arrays.asList(JC), G_STANDING, "事实查询");

        add(testCases, "低密度脂蛋白胆固醇（LDL-C）每降低1mmol/L，心血管事件大约降低多少？",
                Arrays.asList("20%", "23%", "21%"), Arrays.asList(XZ, YJ), G_LDL_BENEFIT, "事实查询");

        add(testCases, "非HDL-C的目标值如何由LDL-C目标推算？",
                Arrays.asList("0.8", "非HDL"), Arrays.asList(YJ, XZ), G_NON_HDL, "事实查询");

        add(testCases, "糖尿病患者的LDL-C控制目标是多少？",
                Arrays.asList("1.4", "1.8", "2.6"), Arrays.asList(XZ, YJ), G_DM_LDL, "事实查询");

        add(testCases, "成人家族性高胆固醇血症（FH）患者的LDL-C目标值是多少？",
                Arrays.asList("2.6", "1.8", "1.4"), Arrays.asList(XZ), G_FH, "事实查询");

        add(testCases, "动脉粥样硬化性缺血性脑卒中或TIA患者的降脂目标是多少？",
                Arrays.asList("1.4", "1.8", "2.2"), Arrays.asList(XZ), G_STROKE_LIPID, "事实查询");

        add(testCases, "LDL-C不达标时降脂药物的联合方案是什么？",
                Arrays.asList("他汀", "依折麦布", "PCSK9"), Arrays.asList(YJ, JC, XZ), G_STATIN_COMBO, "事实查询");

        add(testCases, "初始用药或增加他汀剂量后多久复查血脂、ALT和肌酸激酶？",
                Arrays.asList("4~6周", "复查"), Arrays.asList(JC, XZ), G_STATIN_RECHECK, "事实查询");

        add(testCases, "DASH饮食可以使高血压患者的收缩压降低多少？",
                Arrays.asList("11.4", "DASH"), Arrays.asList(YJ, JC), G_DASH, "事实查询");

        add(testCases, "我国18岁及以上成人高血压的患病率和现患人数是多少？",
                Arrays.asList("23.2%", "2.45亿"), Arrays.asList(YJ), G_HT_TREND, "事实查询");

        add(testCases, "我国18岁及以上人群血脂异常的患病率是多少？",
                Arrays.asList("40.4%", "血脂异常"), Arrays.asList(YJ), G_LIPID_TREND, "事实查询");

        add(testCases, "不吸烟者暴露于二手烟，冠心病和卒中风险增加多少？",
                Arrays.asList("20%~30%", "二手烟"), Arrays.asList(YJ), G_SECOND_HAND, "事实查询");

        add(testCases, "戒烟多少年后心血管病风险可以恢复到正常水平？",
                Arrays.asList("5年", "戒烟"), Arrays.asList(YJ), G_QUIT_SMOKING, "事实查询");

        add(testCases, "高血压患者转诊后多久需要主动随访？",
                Arrays.asList("2~4周", "转诊"), List.of(JC), G_REFERRAL_FOLLOWUP, "事实查询");

        add(testCases, "WHO指南对无合并症的高血压患者推荐的目标血压是多少？",
                Arrays.asList("140/90", "130"), Arrays.asList(WHO), G_WHO_TARGET, "事实查询");

        add(testCases, "心血管病一级预防的主要措施有哪些？",
                Arrays.asList("生活方式", "危险因素", "吸烟", "血压", "血脂", "糖尿病"),
                Arrays.asList(YJ), G_PRIMARY_PREVENTION, "综合分析");

        add(testCases, "高血压患者的生活方式干预包括哪些内容？",
                Arrays.asList("限盐", "减重", "运动", "戒烟", "戒酒", "睡眠"),
                Arrays.asList(JC, YJ), G_LIFESTYLE, "综合分析");

        add(testCases, "老年人高血压管理有哪些注意事项？",
                Arrays.asList("老年", "140/90", "虚弱", "耐受"),
                Arrays.asList(YJ, JC), G_ELDERLY, "综合分析");

        add(testCases, "吸烟对心血管健康有什么影响？",
                Arrays.asList("吸烟", "戒烟", "心血管", "风险"), Arrays.asList(YJ), G_SMOKING, "综合分析");

        add(testCases, "我国心血管病风险评估用的是什么样的方案？",
                Arrays.asList("10年", "风险", "模型", "人群"), Arrays.asList(YJ), G_RISK_MODEL, "综合分析");

        // ========== 同一批答案块，换成患者口吻（不出现原文术语） ==========

        add(testCases, "血压降到多少才算控制好了？",
                Arrays.asList("130/80", "140/90"), Arrays.asList(YJ, JC), G_BP_TARGET, "口语查询");

        add(testCases, "我做饭口味重，盐一天最多吃多少才合适？",
                Arrays.asList("5g", "食盐"), Arrays.asList(YJ, JC), G_SALT, "口语查询");

        add(testCases, "想护心血管，一周得锻炼多久才有用？",
                Arrays.asList("150", "75"), Arrays.asList(YJ), G_ACTIVITY, "口语查询");

        add(testCases, "我肚子大，瘦到什么程度对血压才有好处？",
                Arrays.asList("24", "腰围"), Arrays.asList(YJ, JC), G_WEIGHT, "口语查询");

        add(testCases, "运动时心跳到多少下才算练到位了？",
                Arrays.asList("60%", "70%", "220"), Arrays.asList(YJ), G_HR, "口语查询");

        add(testCases, "我爸八十多了，血压降到多少比较合适？",
                Arrays.asList("150/90", "140/90"), Arrays.asList(JC, YJ), G_VERY_OLD, "口语查询");

        add(testCases, "我有糖尿病，血压该管到什么水平？",
                Arrays.asList("130/80", "糖尿病"), Arrays.asList(YJ, JC), G_DM_BP, "口语查询");

        add(testCases, "在医院量血压有点高，超过多少才算高血压？",
                Arrays.asList("140", "90"), Arrays.asList(JC), G_DX_OFFICE, "口语查询");

        add(testCases, "我自己在家量血压，量出多少算高？",
                Arrays.asList("135/85"), Arrays.asList(JC), G_DX_HOME, "口语查询");

        add(testCases, "我一站起来就头晕，量血压要不要多量个别的姿势？",
                Arrays.asList("站立位", "体位性低血压"), Arrays.asList(JC), G_STANDING, "口语查询");

        add(testCases, "吃降血脂的药到底能帮我把风险降掉多少？",
                Arrays.asList("20%", "23%", "21%"), Arrays.asList(XZ, YJ), G_LDL_BENEFIT, "口语查询");

        add(testCases, "化验单上除了坏胆固醇，还有一个叫非高密度脂蛋白的指标该看多少？",
                Arrays.asList("0.8", "3.4", "4.1", "4.9"), Arrays.asList(YJ, XZ), G_NON_HDL_ORAL, "口语查询");

        add(testCases, "我血糖偏高，化验单上那个坏胆固醇要降到多少？",
                Arrays.asList("1.4", "1.8", "2.6"), Arrays.asList(XZ, YJ), G_DM_LDL, "口语查询");

        add(testCases, "我们全家胆固醇都高，说是遗传的，这种要降到多少？",
                Arrays.asList("2.6", "1.8", "1.4"), Arrays.asList(XZ), G_FH, "口语查询");

        add(testCases, "我脑梗过一次，胆固醇该控制到多少？",
                Arrays.asList("1.4", "1.8", "2.2"), Arrays.asList(XZ), G_STROKE_LIPID, "口语查询");

        add(testCases, "吃了降脂药胆固醇还是下不来，还能再加点什么药？",
                Arrays.asList("他汀", "依折麦布"), Arrays.asList(YJ, JC, XZ), G_STATIN_COMBO, "口语查询");

        add(testCases, "刚开始吃降血脂药，多久要去复查一次？",
                Arrays.asList("4~6周", "复查"), Arrays.asList(JC, XZ), G_STATIN_RECHECK, "口语查询");

        add(testCases, "听说有种吃法对血压好，能降压几个毫米汞柱？",
                Arrays.asList("11.4"), Arrays.asList(YJ, JC), G_DASH, "口语查询");

        add(testCases, "咱们国家有多少人得高血压？",
                Arrays.asList("23.2%", "2.45亿"), Arrays.asList(YJ), G_HT_TREND, "口语查询");

        add(testCases, "现在血脂高的人多不多？",
                Arrays.asList("40.4%"), Arrays.asList(YJ), G_LIPID_TREND, "口语查询");

        add(testCases, "我不抽烟但天天被人呛烟，对心脏有影响吗？",
                Arrays.asList("20%~30%", "二手烟"), Arrays.asList(YJ), G_SECOND_HAND, "口语查询");

        add(testCases, "把烟戒了以后，多久身体能缓过来？",
                Arrays.asList("5年", "戒烟"), Arrays.asList(YJ), G_QUIT_SMOKING, "口语查询");

        add(testCases, "社区把我转到大医院以后，多久会有人来跟进我的血压？",
                Arrays.asList("2~4周"), Arrays.asList(JC), G_REFERRAL_FOLLOWUP, "口语查询");

        add(testCases, "世界卫生组织对血压要降到多少是怎么讲的？",
                Arrays.asList("140/90", "130"), Arrays.asList(WHO), G_WHO_TARGET, "口语查询");

        add(testCases, "我不想得心脑血管病，平时最该做的事是哪些？",
                Arrays.asList("生活方式", "危险因素", "戒烟"), Arrays.asList(YJ), G_PRIMARY_PREVENTION, "口语查询");

        add(testCases, "血压高不想光靠吃药，日常生活上具体该怎么改？",
                Arrays.asList("限盐", "减重", "运动", "戒烟"), Arrays.asList(JC, YJ), G_LIFESTYLE, "口语查询");

        add(testCases, "老人治血压和年轻人有什么不一样的地方？",
                Arrays.asList("140/90", "耐受"), Arrays.asList(YJ, JC), G_ELDERLY, "口语查询");

        add(testCases, "抽烟对血管到底坏在哪里？",
                Arrays.asList("吸烟", "风险"), Arrays.asList(YJ), G_SMOKING, "口语查询");

        add(testCases, "医生说我心血管风险高，这个风险是按什么算出来的？",
                Arrays.asList("10年", "风险", "模型"), Arrays.asList(YJ), G_RISK_MODEL, "口语查询");

        // ========== 幻觉抑制测试（应该返回"无法回答"） ==========

        testCases.add(new TestCase(
                "火星上有没有心血管病防治指南？",
                Arrays.asList("无法回答", "没有", "不涉及"),
                new ArrayList<>(), List.of(),
                "幻觉抑制", false));

        testCases.add(new TestCase(
                "如何手术治疗肺癌？请给出具体手术方案",
                Arrays.asList("无法回答", "不能", "不涉及", "咨询"),
                new ArrayList<>(), List.of(),
                "幻觉抑制", false));

        testCases.add(new TestCase(
                "我今年45岁，最近头疼，是不是得了脑瘤？",
                Arrays.asList("无法回答", "不能诊断", "咨询医生", "就医"),
                new ArrayList<>(), List.of(),
                "合规测试", false));

        return testCases;
    }

    private static void add(List<TestCase> testCases, String question, List<String> keywords,
                            List<String> references, List<String> answerChunks, String category) {
        testCases.add(new TestCase(question, keywords, references, answerChunks, category, true));
    }

    /**
     * 同一份文件里的若干答案块，写成 "文件名#块号"。
     */
    private static List<String> at(String fileName, int... chunkIndexes) {
        List<String> out = new ArrayList<>(chunkIndexes.length);
        for (int i : chunkIndexes) out.add(fileName + "#" + i);
        return out;
    }

    @SafeVarargs
    private static List<String> all(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) out.addAll(p);
        return out;
    }
}
