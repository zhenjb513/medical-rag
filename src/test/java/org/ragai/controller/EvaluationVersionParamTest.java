package org.ragai.controller;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ragai.service.HybridRetrievalService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /evaluation/retrieve?versions=} 的解析与回执（C1 的另一半）。
 * <p>
 * 值是从 HTTP 查询串直接进来的，所以这里钉的是解析形状：中文里粘过来的分隔符（全角逗号、顿号）
 * 要认；空白段要丢；条数要有上限——不限长度等于让手打的一串数字变成任意多个绑定参数。
 * <p>
 * 回执那两条钉的是「后过滤」这件事有没有如实写出来：过滤后条数少于请求 topK 是正常现象，
 * 不写明就会让人以为检索漏了。零模型调用——检索服务是替身。
 */
class EvaluationVersionParamTest {

    private static Document doc(String id, String version) {
        Metadata m = new Metadata();
        m.put("id", id);
        m.put("file_name", id + ".pdf");
        m.put("chunk_index", "7");
        m.put("version", version);
        m.put("score", 0.75);
        return Document.from("正文 " + id, m);
    }

    private static HybridRetrievalService stub(List<Document> returned) {
        HybridRetrievalService svc = Mockito.mock(HybridRetrievalService.class);
        Mockito.when(svc.search(Mockito.anyString(), Mockito.anyInt(), Mockito.any(), Mockito.any()))
                .thenReturn(returned);
        Mockito.when(svc.describeMode()).thenReturn("vector");
        return svc;
    }

    private static EvaluationController controller(HybridRetrievalService svc) {
        // 另外三个协作方在这个口子里根本没被用到，传 null 比再造三个替身更能说明「只用得到检索」
        return new EvaluationController(null, null, null, svc);
    }

    // ===== 解析 =====

    @Test
    @DisplayName("半角/全角逗号与顿号都算分隔符，顺序保持、重复去掉")
    void separatorsOrderAndDedup() {
        assertEquals(List.of("2023", "2021", "2020"),
                List.copyOf(EvaluationController.parseVersions("2023,2021，2020、2023")));
    }

    @Test
    @DisplayName("空白段丢掉、值两侧去空格；全空解析成空集＝不过滤")
    void blanksDroppedAndTrimmed() {
        assertEquals(List.of("2023", "2021"),
                List.copyOf(EvaluationController.parseVersions(" 2023 , , ，2021 ")));
        assertTrue(EvaluationController.parseVersions(null).isEmpty());
        assertTrue(EvaluationController.parseVersions("    ").isEmpty());
    }

    @Test
    @DisplayName("只认前 8 个值")
    void capsAtEight() {
        Set<String> parsed = EvaluationController.parseVersions("1,2,3,4,5,6,7,8,9,10,11");
        assertEquals(8, parsed.size(), parsed.toString());
        assertTrue(parsed.contains("8") && !parsed.contains("9"), "截断点在第 8 个之后：" + parsed);
    }

    @Test
    @DisplayName("认不出年份的文档按 unknown 传，解析不特殊处理它")
    void unknownIsJustAValue() {
        assertEquals(List.of("unknown"), List.copyOf(EvaluationController.parseVersions("unknown")));
    }

    // ===== 传参与回执 =====

    @Test
    @DisplayName("versions= 解析后原样传进 search()，回执分开写请求 topK 与实际返回")
    void versionsParamReachesTheRetrieval() {
        HybridRetrievalService svc = stub(List.of(doc("a", "2023")));

        String body = controller(svc).retrieve("血脂目标", 5, "2023,2021", "");

        Mockito.verify(svc).search("血脂目标", 5, null, Set.of("2023", "2021"));
        assertTrue(body.contains("版本过滤=2023|2021"), body);
        assertTrue(body.contains("请求topK=5"), body);
        assertTrue(body.contains("实际返回=1"),
                "过滤后变少要在同一行看得出没，否则像是检索漏召：" + body);
        assertTrue(body.contains("a.pdf#7"), "逐条列出的还是原来的形状：" + body);
    }

    @Test
    @DisplayName("不带 versions 时检索收到空集，回执不提过滤")
    void absentVersionsMeansNoFiltering() {
        HybridRetrievalService svc = stub(List.of(doc("a", "2023"), doc("b", "2021")));

        String body = controller(svc).retrieve("血脂目标", 8, "", "");

        Mockito.verify(svc).search("血脂目标", 8, null, Set.of());
        assertFalse(body.contains("版本过滤"), "没过滤就别写这一行：" + body);
        assertTrue(body.contains("实际返回=2"), body);
    }
}
