package org.ragai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG检索与引用溯源服务
 * 负责向量检索、上下文组装、引用来源提取、格式化引用来源（System prompt组装）
 * <p>
 * 核心价值：医疗答案可追溯，明确标注答案基于哪些文档生成
 */
@Service
public class RagReferenceService {

    /**
     * 超纲题的固定拒答原话：提示词规则 2、判分器（{@code EvaluationService}）的幻觉抑制判据、
     * demo 档的假生成模型三处都逐字认它，所以放成 public 常量而不是各写一遍。
     */
    public static final String REFUSAL_PHRASE = "根据现有知识库资料，无法回答该问题";

    private final VectorStore vectorStore;

    @Autowired
    public RagReferenceService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 搜索相似文档（向量检索，返回top-k相关文档）
     * 不设相似度阈值，返回全部 topK 结果（由调用方自行过滤）
     */
    public List<Document> search(String query, int topk) {
        return search(query, topk, null);
    }

    /**
     * 搜索相似文档（向量检索，返回top-k相关文档）
     * 双保险：同时在 DB 层（similarityThreshold）和 Java 层手动过滤 score，
     * 防止部分 VectorStore 实现的 DB 层阈值不生效。
     *
     * @param query               用户查询
     * @param topk                最多返回数量
     * @param similarityThreshold 相似度阈值（0.0~1.0），低于此值的结果被过滤；null 表示不设阈值
     */
    public List<Document> search(String query, int topk, Double similarityThreshold) {
        if (vectorStore == null) {
            return Collections.emptyList();
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topk);
        if (similarityThreshold != null) {
            builder.similarityThreshold(similarityThreshold);
        }
        List<Document> rawResults;
        try {
            rawResults = vectorStore.similaritySearch(builder.build());
        } catch (RuntimeException e) {
            System.err.println("[向量检索] 调用失败（向量化或数据库层异常），降级为空："
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return Collections.emptyList();
        }

        System.out.print("[RAG原始分数] Top " + (rawResults == null ? 0 : rawResults.size())
                + " score=[ ");
        if (rawResults != null) {
            for (Document d : rawResults) {
                Double s = d.getScore();
                System.out.printf("%.4f ", s != null ? s : -1.0);
            }
        }
        System.out.println("]  阈值=" + similarityThreshold);

        // Java 层二次过滤：防止 DB 层阈值未生效
        if (similarityThreshold != null && rawResults != null && !rawResults.isEmpty()) {
            List<Document> filtered = new ArrayList<>();
            for (Document doc : rawResults) {
                Double score = doc.getScore();
                if (score != null && score >= similarityThreshold) {
                    filtered.add(doc);
                }
            }
            return filtered;
        }
        return rawResults == null ? Collections.emptyList() : rawResults;
    }

    /**
     * 构建上下文（将文档内容格式化为指定格式）
     * 组装上下文文本，将检索到的文件片段拼接成上下文
     */
    public String buildContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "(无相关文档)";
        }
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            context.append("【文档").append(i + 1).append("】\n");
            context.append(doc.getText()).append("\n");
        }
        return context.toString();
    }

    /**
     * 提取引用来源（按文件聚合到块级），返回可以直接展示的引用行。
     * <p>
     * 以前只回文件名：一份指南几百块，用户拿到「中国血脂管理指南2023年.pdf」根本没法核对
     * 答案到底出自哪一段，「引用溯源」只是看着像。现在每行带本次真正送进模型的那几块的 chunk_index，
     * 配合只读口子 {@code GET /rag/chunk?file=文件名&chunk=块号} 就能把那一块的正文、它在 PDF 里第几页、
     * 以及那一页角上印的页码读出来（印码只在整份文件规律一致时才给，见 {@link PdfPageLocator}），
     * 还有一行「章节=」给出这块归在哪个标题底下（同一遍扫描现算，跨节报范围、认不出就明说，见 {@link SectionIndex}）。
     * <p>
     * 文件名仍排在每行最前：评测的引用判据是「预期文档名是否为答案文本的子串」
     * （EvaluationService 第 375-387 行），在后面追加块号不影响它，98.4% 基线不用重测。
     */
    public List<String> extractReferences(List<Document> docs) {
        List<String> references = new ArrayList<>();
        for (Citation c : groupCitations(docs)) {
            references.add(formatCitation(c));
        }
        return references;
    }

    /**
     * 一条引用：去掉路径的文件名 + 该文件被送进模型的块号（升序去重）+ 有几个块查不到块号。
     * chunk_index 是入库时的连续编号，和上下文里「【文档N】」那个展示序号不是一套序号，别混。
     */
    record Citation(String fileName, String version, List<Integer> chunks, int missingChunkIndex) {}

    /** 按文件聚合引用块（纯逻辑，只读 Document 的字段，不连库不打模型）。 */
    static List<Citation> groupCitations(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        Map<String, TreeSet<Integer>> byFile = new LinkedHashMap<>();
        Map<String, Integer> missingByFile = new LinkedHashMap<>();
        Map<String, String> versionByFile = new LinkedHashMap<>();
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata() == null ? Map.of() : doc.getMetadata();
            String source = firstNonEmpty(metadata.get("source"), metadata.get("file_name"), metadata.get("filename"));
            if (source == null) {
                continue;
            }
            String fileName = stripPath(source);
            Integer chunkIndex = parseChunkIndex(metadata.get("chunk_index"));
            String version = metadata.get("version") == null ? null : String.valueOf(metadata.get("version"));
            versionByFile.putIfAbsent(fileName, version);
            if (chunkIndex == null) {
                missingByFile.merge(fileName, 1, Integer::sum);
                byFile.computeIfAbsent(fileName, k -> new TreeSet<>());
            } else {
                byFile.computeIfAbsent(fileName, k -> new TreeSet<>()).add(chunkIndex);
            }
        }
        List<Citation> citations = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> e : byFile.entrySet()) {
            citations.add(new Citation(e.getKey(), versionByFile.get(e.getKey()),
                    new ArrayList<>(e.getValue()),
                    missingByFile.getOrDefault(e.getKey(), 0)));
        }
        return citations;
    }

    /**
     * 引用行的展示文本。三种形态：
     * 「x.pdf（块号 3、4、12）」／「x.pdf（块号 3、4；另有 2 块未记录块号）」／「x.pdf（本批块未记录块号）」。
     * 最后一行是退路：SimpleVectorStore 模式下 metadata 里没有 chunk_index，宁可承认查不到也不编造编号。
     */
    static String formatCitation(Citation c) {
        StringBuilder sb = new StringBuilder(c.fileName());
        if (c.version() != null && !c.version().equals("unknown")) {
            sb.append("【").append(c.version()).append("版】");
        }
        if (c.chunks().isEmpty()) {
            return c.missingChunkIndex() > 0 ? sb.append("（本批块未记录块号）").toString() : sb.toString();
        }
        StringJoiner numbers = new StringJoiner("、");
        for (Integer ci : c.chunks()) {
            numbers.add(String.valueOf(ci));
        }
        sb.append("（块号 ").append(numbers);
        if (c.missingChunkIndex() > 0) {
            sb.append("；另有 ").append(c.missingChunkIndex()).append(" 块未记录块号");
        }
        return sb.append("）").toString();
    }

    /** metadata 里的块号可能是 Integer、Long，也可能是 JSON 里的字符串；认不出来就返回 null。 */
    static Integer parseChunkIndex(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 按顺序取第一个有值的键（同一份文件可能写在 source / file_name / filename 任一键上）。
     * 取 {@code String.valueOf} 而不是强转：和 {@code ChatController.extractFileName}、
     * {@code EvaluationService.chunkKeyOf} 用同一套取法，日志里的文件名、引用行的文件名、判分用的块主键三者必须一致。
     */
    private static String firstNonEmpty(Object... values) {
        for (Object v : values) {
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static String stripPath(String source) {
        int lastSlash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        return lastSlash > 0 && lastSlash < source.length() - 1 ? source.substring(lastSlash + 1) : source;
    }

    /**
     * 构建系统提示（包含上下文和回答规则）
     * 组装带医疗合规约束的System Prompt
     */
    public String buildSystemPrompt(String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的医疗知识库问答助手，请根据以下知识库上下文回答用户的问题。\n\n");
        prompt.append("【知识库上下文】\n");
        prompt.append(context).append("\n\n");
        prompt.append("【回答规则】\n");
        prompt.append("1. 只根据上下文内容回答，不要编造上下文没有的信息\n");
        prompt.append("2. 如果上下文没有相关信息，直接回答：" + REFUSAL_PHRASE + "\n");
        prompt.append("3. 回答要准确、简洁，尽量引用原文\n");
        // 这里点名的四种句式与 EvaluationService.containsDiagnosisConclusion 逐字对应（两侧由 EvalPromptGuardTest 钉住）。
        // 注意：判分器按小句放行带条件标记的写法（「即可诊断为」这类），提示词的禁令比判据更严，是有意为之。
        prompt.append("4. 不要给出诊断结论，只提供知识查询和信息参考；不要使用「诊断为」「确诊为」「你得了」「你患有」这类判定句式，"
                + "改成陈述资料里的界值本身，例如「指南给出的诊室高血压界值是140/90mmHg，是否确诊需由医生结合非同日多次测量判断」\n");
        prompt.append("5. 涉及药物、治疗方案时，提醒用户咨询专业医生\n");
        return prompt.toString();
    }

    /**
     * 格式化引用来源（添加序号）,附加到答案末尾
     * 组装引用来源文本，将提取到的引用行拼接成引用来源
     */
    public String formatReferences(List<String> references) {
        if (references == null || references.isEmpty()) {
            return "(无引用)";
        }
        StringBuilder refs = new StringBuilder();
        refs.append("\n\n---\n");
        refs.append("【引用来源】（括号内是本次实际送入模型的文档块编号，即库里的 chunk_index；"
                + "回读该块正文、它在 PDF 里的第几页、页上印的页码和它归在哪一节：GET /rag/chunk?file=文件名&chunk=块号）\n");
        for (int i = 0; i < references.size(); i++) {
            refs.append(i + 1).append(".").append(references.get(i)).append("\n");
        }
        return refs.toString();
    }
}