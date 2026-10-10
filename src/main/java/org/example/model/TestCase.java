package org.example.model;

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
     * 答案块标注（ground truth）：格式「文件名#chunk_index」。
     * <p>
     * 关键词只能证明"话题相关"，证明不了"答案真的被召回"。评测检索时需要这一份标注：
     * 检索回来的块里只要有任意一个标过的块，才算真正召回了答案。
     * 标注方式是逐字定位原文（见 Bm25IndexService#locate），不是凭印象猜。
     */
    private List<String> answerChunks;

    /**
     * 问题分类
     */
    private String category;

    /**
     * 是否应被能回答（true 表示应回答,知识库有答案，false 表示不应回答，应该返回"无法回答"）
     */
    private boolean shouldBeAnswerable;

    public TestCase(String question,
                    List<String> expectedKeywords,
                    List<String> expectedReferences,
                    List<String> answerChunks,
                    String category, boolean shouldBeAnswerable) {
        this.question = question;
        this.expectedKeywords = expectedKeywords;
        this.expectedReferences = expectedReferences;
        this.answerChunks = answerChunks;
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

    public List<String> getAnswerChunks() {
        return answerChunks;
    }

    public String getCategory() {
        return category;
    }

    public boolean isShouldBeAnswerable() {
        return shouldBeAnswerable;
    }
}