package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc_type 入库元数据的解析形状（1d）。纯文件名关键词、闭集、认不准落「其他」——
 * 与章节号同纪律：不猜。doc_year 不另建字段——入库的 version 就是按文件名解析的年份轴。
 * 零模型调用、零 IO。
 */
class DocTypeParseTest {

    @Test
    @DisplayName("库里现有四份真实文件名全部落「指南」")
    void realCorpusFilesAreGuidelines() {
        for (String name : List.of(
                "02-WHO成人高血压药物治疗指南2021.pdf",
                "中国心血管病一级预防指南2020.pdf",
                "中国血脂管理指南2023年.pdf",
                "国家基层高血压防治管理指南2025版.pdf")) {
            assertEquals("指南", RagInitializer.parseDocTypeFromFileName(name), name);
        }
    }

    @Test
    @DisplayName("四个关键词各归各类，扩展名不影响")
    void keywordsMapToClosedSet() {
        assertEquals("共识", RagInitializer.parseDocTypeFromFileName("高血压合理用药专家共识.pdf"));
        assertEquals("手册", RagInitializer.parseDocTypeFromFileName("基层高血压防治管理手册.docx"));
        assertEquals("报告", RagInitializer.parseDocTypeFromFileName("中国心血管健康与疾病报告2023.pdf"));
        assertEquals("其他", RagInitializer.parseDocTypeFromFileName("某医院心内科简介.pdf"));
    }

    @Test
    @DisplayName("同时含多个关键词时按 指南>共识>手册>报告 的声明顺序取第一个")
    void priorityOrderIsDeclaredOrder() {
        assertEquals("指南", RagInitializer.parseDocTypeFromFileName("xx共识与指南汇编.pdf"));
        assertEquals("共识", RagInitializer.parseDocTypeFromFileName("xx手册专家共识.pdf"));
    }

    @Test
    @DisplayName("null/空白/认不出都落「其他」，不抛异常")
    void degenerateInputFallsToOther() {
        assertEquals("其他", RagInitializer.parseDocTypeFromFileName(null));
        assertEquals("其他", RagInitializer.parseDocTypeFromFileName(""));
        assertEquals("其他", RagInitializer.parseDocTypeFromFileName("   "));
        assertEquals("其他", RagInitializer.parseDocTypeFromFileName("无关键词文件.pdf"));
    }

    @Test
    @DisplayName("输出永远在闭集内")
    void outputIsAlwaysInClosedSet() {
        Set<String> closed = Set.of("指南", "共识", "手册", "报告", "其他");
        for (String name : List.of("指南.pdf", "共识.pdf", "手册.pdf", "报告.pdf", "a.pdf", "", "指南共识手册报告.pdf")) {
            assertTrue(closed.contains(RagInitializer.parseDocTypeFromFileName(name)), name);
        }
    }
}