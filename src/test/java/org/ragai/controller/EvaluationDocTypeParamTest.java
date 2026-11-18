package org.ragai.controller;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ragai.service.HybridRetrievalService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * /evaluation/retrieve?docTypes= 的传参与回执（1d）。解析复用 parseVersions（形状已有守门），
 * 这里钉的是：docTypes 进五参 search、与 versions 同时给是交集口径、回执如实写「后过滤」、
 * 留空时仍走原来的四参调用。零模型调用——检索服务是替身。
 */
class EvaluationDocTypeParamTest {

    private static Document doc(String id, String docType) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("file_name", id + ".pdf");
        m.put("chunk_index", "7");
        m.put("version", "2023");
        m.put("doc_type", docType);
        m.put("score", 0.75);
        return Document.from("正文 " + id, m);
    }

    private static HybridRetrievalService stub(List<Document> returned) {
        HybridRetrievalService svc = Mockito.mock(HybridRetrievalService.class);
        Mockito.when(svc.search(anyString(), anyInt(), any(), any(), any())).thenReturn(returned);
        Mockito.when(svc.describeMode()).thenReturn("vector");
        return svc;
    }

    private static EvaluationController controller(HybridRetrievalService svc) {
        return new EvaluationController(null, null, null, svc);
    }

    @Test
    @DisplayName("docTypes= 解析后进五参 search()，回执写明类型过滤是后过滤")
    void docTypesParamReachesTheRetrieval() {
        HybridRetrievalService svc = stub(List.of(doc("a", "指南")));

        String body = controller(svc).retrieve("血脂目标", 5, "", "指南，共识");

        Mockito.verify(svc).search("血脂目标", 5, null, Set.of(), Set.of("指南", "共识"));
        assertTrue(body.contains("类型过滤=指南|共识"), body);
        assertTrue(body.contains("后过滤"), body);
        assertTrue(body.contains("请求topK=5") && body.contains("实际返回=1"), body);
    }

    @Test
    @DisplayName("versions= 与 docTypes= 同时给，两个白名单都原样传下去")
    void bothFiltersArePassedTogether() {
        HybridRetrievalService svc = stub(List.of(doc("a", "指南")));

        String body = controller(svc).retrieve("血脂目标", 8, "2023,2021", "指南");

        Mockito.verify(svc).search("血脂目标", 8, null, Set.of("2023", "2021"), Set.of("指南"));
        assertTrue(body.contains("版本过滤=2023|2021"), body);
        assertTrue(body.contains("类型过滤=指南"), body);
    }

    @Test
    @DisplayName("docTypes 留空时仍走四参老调用，回执不提类型过滤")
    void absentDocTypesKeepsTheFourArgCall() {
        HybridRetrievalService svc = Mockito.mock(HybridRetrievalService.class);
        Mockito.when(svc.search(anyString(), anyInt(), any(), any()))
                .thenReturn(List.of(doc("a", "指南"), doc("b", "共识")));
        Mockito.when(svc.describeMode()).thenReturn("vector");

        String body = controller(svc).retrieve("血脂目标", 8, "2023", "");

        Mockito.verify(svc).search("血脂目标", 8, null, Set.of("2023"));
        Mockito.verify(svc, Mockito.never()).search(anyString(), anyInt(), any(), any(), any());
        assertFalse(body.contains("类型过滤"), body);
        assertTrue(body.contains("实际返回=2"), body);
    }
}