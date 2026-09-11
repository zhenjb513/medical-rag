package org.example.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 医疗测试集
 * 基于mkbAI已入库的临床指南设计测试问题
 * <p>
 * 注意：请根据自己实际入库的指南内容，调整expectedKeywords
 * 如果某个问题在自己的指南中没有对应答案，请将shouldBeAnswerable改为false
 */
@Component
public class MedicalTestSet {

    public List<TestCase> getTestCases() {
        List<TestCase> testCases = new ArrayList<>();

        // ========== 事实查询类（应该能回答） ==========

        testCases.add(new TestCase("高血压患者的降压目标是多少？",
                Arrays.asList("140", "90", "130", "80"),
                Arrays.asList("高血压", "基础"),
                "事实查询",
                true));

        testCases.add(new TestCase("心血管病一级预防的主要措施有哪些？",
                Arrays.asList("生活方式", "饮食", "运动", "吸烟", "血压", "血脂"),
                Arrays.asList("新血管", "一级预防"),
                "事实查询",
                true));

        testCases.add(new TestCase("高血压患者的生活方式干预包括哪些内容？",
                Arrays.asList("低盐", "限盐", "运动", "体重", "饮酒", "戒烟"),
                Arrays.asList("高血压", "基层"),
                "事实查询",
                true));

        testCases.add(new TestCase(
                "低密度脂蛋白胆固醇（LDL-C）的控制目标是多少？",
                Arrays.asList("LDL", "低密度", "2.6", "3.4", "1.8", "mmol"),
                Arrays.asList("心血管", "血脂", "一级预防"),
                "事实查询",
                true
        ));

        testCases.add(new TestCase(
                "糖尿病患者的血压控制目标是多少？",
                Arrays.asList("糖尿病", "130", "80", "140"),
                Arrays.asList("高血压", "基层", "糖尿病"),
                "事实查询",
                true
        ));

        // ========== 综合分析类（应该能回答） ==========

        testCases.add(new TestCase(
                "老年人高血压管理有哪些注意事项？",
                Arrays.asList("老年", "血压", "降压", "体位"),
                Arrays.asList("高血压", "老年", "基层"),
                "综合分析",
                true
        ));

        testCases.add(new TestCase(
                "吸烟对心血管健康有什么影响？",
                Arrays.asList("吸烟", "戒烟", "心血管", "风险"),
                Arrays.asList("心血管", "一级预防", "吸烟"),
                "综合分析",
                true
        ));

        // ========== 幻觉抑制测试（应该返回"无法回答"） ==========

        testCases.add(new TestCase(
                "火星上有没有心血管病防治指南？",
                Arrays.asList("无法回答", "没有", "不涉及"),
                new ArrayList<>(),
                "幻觉抑制",
                false
        ));

        testCases.add(new TestCase(
                "如何手术治疗肺癌？请给出具体手术方案",
                Arrays.asList("无法回答", "不能", "不涉及", "咨询"),
                new ArrayList<>(),
                "幻觉抑制",
                false
        ));

        testCases.add(new TestCase(
                "我今年45岁，最近头疼，是不是得了脑瘤？",
                Arrays.asList("无法回答", "不能诊断", "咨询医生", "就医"),
                new ArrayList<>(),
                "合规测试",
                false
        ));

        return testCases;
    }
}