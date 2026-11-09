package org.ragai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 医疗测试集
 * 题目和答案块标注都在 classpath 的 eval/medical_test_set.json 里，这个类只负责读进来。
 * <p>
 * answerChunks 是「答案块标注」：每条都是逐字定位原文得到的 文件名#chunk_index（见 /bm25/locate），
 * 也就是"这个问题的标准答案确实写在这几块里"。改问题或重灌文档后要重新核对这些块号。
 * expectedKeywords 只用于生成质量评估，证明不了检索是否真的找回了答案。
 * <p>
 * 同一组答案块会问两遍：书面问法（category=事实查询/综合分析）用指南里的原词，
 * 口语问法（category=口语查询）用患者口吻、不出现原文术语。JSON 把这层共用做成 answerGroup，
 * 两种问法指向同一份 ground truth、只许问法不同——这就是「自造题偏袒关键词检索」的量尺，
 * 拆成两份标注等于把尺子折了（MedicalTestSetStructureTest 会拦）。
 * <p>
 * 注意：请根据自己实际入库的指南内容，调整expectedKeywords
 * 如果某个问题在自己的指南中没有对应答案，请将shouldBeAnswerable改为false
 */
@Component
public class MedicalTestSet {

    private static final String RESOURCE = "/eval/medical_test_set.json";

    public List<TestCase> getTestCases() {
        JsonNode root;
        try (InputStream in = MedicalTestSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("考卷不在 classpath 上：" + RESOURCE);
            }
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("考卷读不出来：" + RESOURCE, e);
        }

        Map<String, List<String>> answerGroups = readAnswerGroups(root);
        List<TestCase> testCases = new ArrayList<>();
        for (JsonNode section : root.path("sections")) {            for (JsonNode caseNode : section) {
                testCases.add(toTestCase(caseNode, answerGroups));
            }
        }
        return testCases;
    }

    private static Map<String, List<String>> readAnswerGroups(JsonNode root) {
        Map<String, List<String>> groups = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.path("answerGroups").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            groups.put(entry.getKey(), toList(entry.getValue().path("answerChunks")));
        }
        return groups;
    }

    private static TestCase toTestCase(JsonNode node, Map<String, List<String>> answerGroups) {
        List<String> answerChunks = node.hasNonNull("answerGroup")
                ? answerGroups.get(node.get("answerGroup").asText())
                : toList(node.path("answerChunks"));
        if (answerChunks == null) {
            throw new IllegalStateException("考卷引用了不存在的答案块组："
                    + node.path("answerGroup").asText() + "（题面：" + node.path("question").asText() + "）");
        }
        return new TestCase(
                node.path("question").asText(),
                toList(node.path("expectedKeywords")),
                toList(node.path("expectedReferences")),
                answerChunks,
                node.path("category").asText(),
                node.path("shouldBeAnswerable").asBoolean());
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }
}
