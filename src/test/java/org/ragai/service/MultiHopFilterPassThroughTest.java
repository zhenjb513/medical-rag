package org.ragai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多跳链路上的白名单传参（1d）：主检索和每一跳都必须带同一份白名单，
 * 否则子查询会把被过滤掉的文档重新捞回合并结果。白名单全空时必须保持原来的三参调用，
 * 老调用点行为不变。零模型调用：检索服务是替身。
 */
@ExtendWith(MockitoExtension.class)
class MultiHopFilterPassThroughTest {

    @Mock
    HybridRetrievalService hybridMock;

    MultiHopRetrievalService service;

    private static final Set<String> VF = Set.of("2023");
    private static final Set<String> DT = Set.of("指南");

    @BeforeEach
    void setUp() {
        service = new MultiHopRetrievalService(hybridMock);
        ReflectionTestUtils.setField(service, "multiHopEnabled", true);
        ReflectionTestUtils.setField(service, "minSourcesToTrigger", 2);
        ReflectionTestUtils.setField(service, "hopDecay", 0.7);
        ReflectionTestUtils.setField(service, "maxHops", 2);
    }

    private static Document doc(String source, String text) {
        Metadata m = new Metadata();
        m.put("source", source);
        m.put("file_name", source + ".pdf");
        m.put("chunk_index", "1");
        m.put("content_hash", "hash-" + text.hashCode());
        m.put("score", 0.75);
        return Document.from(text, m);
    }

    private static List<Document> docsFrom(String... sources) {
        List<Document> list = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            list.add(doc(sources[i], "chunk-" + sources[i] + "-" + i));
        }
        return list;
    }

    @Test
    @DisplayName("多跳关闭时白名单原样传给单跳五参 search()")
    void disabledDelegatesWithFilters() {
        ReflectionTestUtils.setField(service, "multiHopEnabled", false);
        when(hybridMock.search("测试", 5, null, VF, DT)).thenReturn(docsFrom("JC", "JC"));

        List<Document> result = service.search("测试", 5, null, VF, DT);

        assertEquals(2, result.size());
        verify(hybridMock).search("测试", 5, null, VF, DT);
    }

    @Test
    @DisplayName("多跳开启：主检索带白名单（poolK 口径不变）")
    void primaryCarriesFilters() {
        when(hybridMock.search(anyString(), anyInt(), isNull(), eq(VF), eq(DT)))
                .thenReturn(docsFrom("JC", "JC", "JC"));

        List<Document> result = service.search("高血压患者的降压目标是多少？", 5, null, VF, DT);

        assertEquals(3, result.size());
        verify(hybridMock).search("高血压患者的降压目标是多少？", 15, null, VF, DT);
        verify(hybridMock, never()).search(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("触发多跳时每一跳都带同一份白名单，三参老口子一次都不走")
    void everyHopCarriesTheSameFilters() {
        when(hybridMock.search(anyString(), anyInt(), isNull(), eq(VF), eq(DT)))
                .thenAnswer(inv -> docsFrom("YJ", "JC", "XZ"));

        service.search("糖尿病患者的 LDL-C 和降压目标各是多少？", 6, null, VF, DT);

        verify(hybridMock, atLeast(2)).search(anyString(), anyInt(), isNull(), eq(VF), eq(DT));
        verify(hybridMock, never()).search(anyString(), anyInt(), any());
        verify(hybridMock, never()).search(anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("白名单全空时保持三参调用链，与加参数前完全一致")
    void emptyFiltersKeepTheOldThreeArgPath() {
        when(hybridMock.search(anyString(), anyInt(), isNull()))
                .thenReturn(docsFrom("JC", "JC"));

        service.search("高血压患者的降压目标是多少？", 5, null, Set.of(), null);

        verify(hybridMock).search("高血压患者的降压目标是多少？", 15, null);
        verify(hybridMock, never()).search(anyString(), anyInt(), any(), any(), any());
    }
}