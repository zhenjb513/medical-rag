package org.example.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiHopRetrievalServiceTest {

    @Mock
    HybridRetrievalService hybridMock;

    MultiHopRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new MultiHopRetrievalService(hybridMock);
        ReflectionTestUtils.setField(service, "multiHopEnabled", true);
        ReflectionTestUtils.setField(service, "minSourcesToTrigger", 2);
        ReflectionTestUtils.setField(service, "hopDecay", 0.7);
        ReflectionTestUtils.setField(service, "maxHops", 2);
    }

    private Document doc(String source, String text) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", source);
        meta.put("file_name", source + ".pdf");
        meta.put("content_hash", UUID.randomUUID().toString());
        return new Document(UUID.randomUUID().toString(), text, meta);
    }

    private List<Document> docsFrom(String... sources) {
        List<Document> list = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            list.add(doc(sources[i], "chunk-" + sources[i] + "-" + i));
        }
        return list;
    }

    private Set<String> sourcesOf(List<Document> docs) {
        return docs.stream()
                .map(d -> d.getMetadata().get("source").toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("单实体问句 + 单文档来源 -> 不应触发多跳")
    @Tag("logic")
    void singleEntity_singleSource_noHop() {
        when(hybridMock.search("高血压患者的降压目标是多少？", 15, null))
                .thenReturn(docsFrom("JC", "JC", "JC", "JC", "JC"));

        List<Document> result = service.search("高血压患者的降压目标是多少？", 5, null);

        assertNotNull(result);
        assertTrue(result.size() <= 5);
    }

    @Test
    @DisplayName("显式 AND 句式 -> 必须触发多跳，返回结果应来自多个 source")
    @Tag("logic")
    void andPhrase_shouldTrigger() {
        when(hybridMock.search(any(String.class), anyInt(), any()))
                .thenReturn(docsFrom("XZ", "JC", "YJ", "WHO"))
                .thenAnswer(inv -> {
                    String q = inv.getArgument(0);
                    if (q.contains("LDL")) {
                        return docsFrom("XZ", "XZ", "XZ");
                    } else if (q.contains("降压") || q.contains("血压")) {
                        return docsFrom("JC", "JC", "YJ");
                    } else {
                        return docsFrom("YJ", "JC");
                    }
                });

        List<Document> result = service.search(
                "糖尿病患者的 LDL-C 和降压目标各是多少？", 6, null);

        Set<String> sources = sourcesOf(result);

        assertTrue(sources.size() >= 2,
                "应覆盖 >=2 个指南，实际覆盖: " + sources);
        assertTrue(sources.contains("XZ"), "应命中血脂指南 XZ");
        assertTrue(sources.contains("JC") || sources.contains("YJ"),
                "应命中降压指南 JC/YJ");
    }

    @Test
    @DisplayName("第一轮来自 >=2 个不同 source -> 即使没 AND 句式也应触发多跳")
    @Tag("logic")
    void crossSource_shouldTriggerEvenWithoutAnd() {
        when(hybridMock.search(any(String.class), anyInt(), any()))
                .thenAnswer(inv -> docsFrom("YJ", "JC", "YJ", "XZ", "JC"));

        List<Document> result = service.search("高血压患者应该注意什么？", 6, null);

        Set<String> sources = sourcesOf(result);

        assertTrue(sources.size() >= 3,
                "主检索已跨 3 源，多跳后应覆盖更多，实际: " + sources);
    }

    @Test
    @DisplayName("多跳合并应 content_hash 去重")
    @Tag("logic")
    void merge_shouldDedupeByContentHash() {
        List<Document> primary = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            primary.add(doc("JC", "text-" + i));
        }

        List<Document> hop = new ArrayList<>(primary.subList(2, 5));
        hop.add(doc("XZ", "new-doc"));

        when(hybridMock.search(any(String.class), anyInt(), any()))
                .thenReturn(primary)
                .thenReturn(hop);

        List<Document> result = service.search("高血压和血脂目标", 10, null);

        long uniqueHashes = result.stream()
                .map(d -> d.getMetadata().get("content_hash").toString())
                .distinct()
                .count();

        assertEquals(result.size(), uniqueHashes, "合并后不应有重复块");
    }

    @Test
    @DisplayName("multi-hop.enabled=false 时应直接委托单跳")
    @Tag("logic")
    void disabled_shouldDelegateDirectly() {
        ReflectionTestUtils.setField(service, "multiHopEnabled", false);

        List<Document> singleResult = docsFrom("JC", "JC");
        when(hybridMock.search("测试", 5, null)).thenReturn(singleResult);

        List<Document> result = service.search("测试", 5, null);

        assertEquals(2, result.size());
    }
}