package org.example.service;

import java.util.List;

/**
 * 医疗用例测试模型
 * 定义一个测试问题及其评估标准
 */
public class TestCase {

    /**
     * 问题内容
     */
    private String question;

    /**
     * 预期答案关键词（答案中包含任意一个即算准确）
     */
    private List<String> expectedKeywords;

    /**
     * 预期参考文档（文件名关键词，用于判断引用是否正确）
     */
    private List<String> expectedReferences;

    /**
     * 问题分类
     */
    private String category;

    /**
     * 是否应被能回答（true 表示应回答,知识库有答案，false 表示不应回答，应该返回“无法回答”）
     */
    private boolean shouldBeAnswerable;

    public TestCase(String question,
                    List<String> expectedKeywords,
                    List<String> expectedReferences,
                    String category, boolean shouldBeAnswerable) {
        this.question = question;
        this.expectedKeywords = expectedKeywords;
        this.expectedReferences = expectedReferences;
        this.category = category;
        this.shouldBeAnswerable = shouldBeAnswerable;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getExpectedKeywords() {
        return expectedKeywords;
    }

    public List<String> getExpectedReferences() {
        return expectedReferences;
    }

    public String getCategory() {
        return category;
    }

    public boolean isShouldBeAnswerable() {
        return shouldBeAnswerable;
    }
}
