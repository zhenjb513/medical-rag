package org.ragai.config;

import org.ragai.service.RagReferenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * demo 档两个假模型（{@code DemoModelConfig} 里的嵌套类）的口径守门测试。不连库、不打模型。
 * <p>
 * 钉住四件事：
 * ① 拒答必须逐字用 {@link RagReferenceService#REFUSAL_PHRASE}——判分器的负样本判据认的就是这句原话，
 * demo 档自创一句拒答词会让「超纲题拒答」这条在两个档之间悄悄漂移；
 * ② 假生成切上下文靠【知识库上下文】/【回答规则】两个标记字，而这两个字是
 * {@code RagReferenceService.buildSystemPrompt()} 打的，所以这里用**真方法**造提示词，
 * 以后改标记字会直接把这条测试打红，而不是让 demo 档静默变成「所有题都拒答」；
 * ③ 挑出来的句子必须是上下文里的原文，假生成不许改写、不许编；
 * ④ 假向量必须确定（同一句在任何 JVM 上算出同一个向量），否则换个机器重灌一次库，
 * 已有向量全作废、闸门分数也全变。
 */
class DemoExtractiveModelTest {

    private static final RagReferenceService REF_SVC = new RagReferenceService(null);
    private static final DemoModelConfig.ExtractiveChatModel GEN = new DemoModelConfig.ExtractiveChatModel();

    private static Document doc(String file, long chunkIndex, String text) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "/data/pdf/" + file);
        meta.put("file_name", file);
        meta.put("chunk_index", chunkIndex);
        return new Document("id-" + file + "-" + chunkIndex, text, meta);
    }

    /** 走和 ChatController 一样的两步：buildContext → buildSystemPrompt，再把整段喂给假生成。 */
    private static String answerWithRealPrompt(String question, List<Document> docs) {
        return GEN.answer(question, REF_SVC.buildSystemPrompt(REF_SVC.buildContext(docs)));
    }

    @Test
    @DisplayName("闸门没过（上下文是「(无相关文档)」）时，逐字返回判分器认的那句拒答原话")
    void emptyContextReturnsExactRefusal() {
        String answer = answerWithRealPrompt("血压达标后多久随访一次？", List.of());
        assertEquals(RagReferenceService.REFUSAL_PHRASE, answer,
                "拒答必须逐字等于 REFUSAL_PHRASE，多一个字少一个字都会让评测负样本判据失配");
    }

    @Test
    @DisplayName("上下文有内容但一句都对不上时，同样逐字拒答")
    void noOverlappingSentenceReturnsExactRefusal() {
        List<Document> docs = List.of(doc("示例成人高血压管理指南2026.pdf", 1,
                "65~79 岁老年人 低于 140/90 mmHg，耐受则 130/80 起始收缩压 150 mmHg 以上"));
        String answer = answerWithRealPrompt("红楼梦的作者是谁？", docs);
        assertEquals(RagReferenceService.REFUSAL_PHRASE, answer);
    }

    @Test
    @DisplayName("命中的题：答案带「没有调用大模型」声明，且每一句都是上下文里的原文")
    void hitAnswerIsVerbatimExcerptWithDisclaimer() {
        String chunk = "用药后每 4 周随访一次直到血压达标，达标后每 3 个月随访一次。"
                + "白蛋白尿存在时更严格。";
        List<Document> docs = List.of(doc("示例成人高血压管理指南2026.pdf", 2, chunk));
        String answer = answerWithRealPrompt("血压达标后多久随访一次？", docs);

        assertTrue(answer.startsWith("根据知识库内容："), () -> "抽取式答案前缀不对：" + answer);
        assertTrue(answer.contains("没有调用大模型"), "必须明写这是假生成，不能被当成模型真实水平");
        assertTrue(answer.contains("用药后每 4 周随访一次直到血压达标，达标后每 3 个月随访一次"),
                () -> "没挑到那句带数值的原文：" + answer);
        // 不许改写：去掉前缀和声明后，剩下的每句都必须是上下文子串
        String body = answer.substring("根据知识库内容：".length())
                .split("\n\n（demo 档")[0];
        for (String sentence : body.split("。")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            assertTrue(chunk.contains(s), () -> "这句不在原文里，属于编造：" + s);
        }
    }

    @Test
    @DisplayName("切上下文用的两个标记字必须和 buildSystemPrompt 打出来的一致")
    void markersMatchSystemPromptBuilder() {
        String prompt = REF_SVC.buildSystemPrompt("【文档1】\n正文一句。\n");
        String sliced = DemoModelConfig.between(prompt, "【知识库上下文】", "【回答规则】");
        assertTrue(sliced.contains("正文一句"), () -> "标记字改了，假生成会切不到上下文、所有题都变拒答：" + sliced);
        assertFalse(sliced.contains("【回答规则】"), "切出来的段落不该把回答规则也带进去");
    }

    @Test
    @DisplayName("短句碎片（少于 6 字）不进答案候选，【文档N】标记要切掉")
    void splitSentencesDropsShortFragmentsAndMarkers() {
        List<String> parts = DemoModelConfig.splitSentences("【文档1】\n第 1 页。\n血压达标后每 3 个月随访一次\n");
        assertFalse(parts.contains("第 1 页"), () -> "页码这类碎片不该当候选：" + parts);
        assertTrue(parts.contains("血压达标后每 3 个月随访一次"), () -> "正常句被吞了：" + parts);
        assertTrue(parts.stream().noneMatch(p -> p.startsWith("【文档")), () -> "标记字没切干净：" + parts);
    }

    @Test
    @DisplayName("between：找不到左标记返回空串，找不到右标记返回到结尾")
    void betweenHandlesMissingMarkers() {
        assertEquals("", DemoModelConfig.between("整段没有标记", "【知识库上下文】", "【回答规则】"));
        assertEquals("正文", DemoModelConfig.between("【知识库上下文】正文", "【知识库上下文】", "【回答规则】"));
    }

    @Test
    @DisplayName("假向量确定可复现：同串两次一致、非空向量 L2 归一化、空串全零不 NaN")
    void hashingEmbeddingIsDeterministicAndNormalized() {
        DemoModelConfig.HashingEmbeddingModel model = new DemoModelConfig.HashingEmbeddingModel(1024);
        float[] a = model.embed("血压达标后每 3 个月随访一次");
        float[] b = model.embed("血压达标后每 3 个月随访一次");
        assertArrayEquals(a, b, "同一句算出两个向量，说明假向量不可复现，换机器重灌会让已有向量全作废");
        assertEquals(1024, model.dimensions());

        double sum = 0;
        for (float x : a) sum += (double) x * x;
        assertEquals(1.0, Math.sqrt(sum), 1e-5, "没归一化的话余弦会被句长带偏，闸门阈值就没法按实测定");

        float[] zero = model.embed("");
        for (float x : zero) {
            assertFalse(Float.isNaN(x), "空文本必须给全零向量，不能出 NaN");
            assertEquals(0f, x);
        }
    }

    @Test
    @DisplayName("gram 归一化只留字母数字，短句末尾补一元组不被吞掉")
    void gramsNormalizesAndHandlesShortInput() {
        Set<String> two = DemoModelConfig.grams("血压。");
        assertTrue(two.contains("血压"), () -> "双字组丢了：" + two);
        assertTrue(two.contains("压"), () -> "末尾单字没补一元组：" + two);
        assertEquals(Set.of("血"), DemoModelConfig.grams(" 血 "));
    }
}
