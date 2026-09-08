package org.example.service;

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
        List<Document> rawResults = vectorStore.similaritySearch(builder.build());

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
    public String buildContext(List<Document> docs){
        if(docs == null || docs.isEmpty()){
            return "(无相关文档)";
        }
        StringBuilder context = new StringBuilder();
       for(int i = 0;i<docs.size();i++){
           Document doc = docs.get(i);
           context.append("【文档").append(i+1).append("】\n");
           context.append(doc.getText()).append("\n");
       }
       return context.toString();
    }

    /**
     * 提取引用来源（去重），返回文件名列表
     * 从Document的metadata中提取source/file_name/filename字段
     * 去重并返回文件名列表
     */
    public List<String> extractReferences(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> references = new LinkedHashSet<>();
        for (Document doc : docs) {
            Map<String, Object> metadata = doc.getMetadata();
            String source = null;
            //尝试多种(metadata中可能的键名)方式提取文件名
            if (metadata.containsKey("source")) {
                source = (String) metadata.get("source");
            } else if (metadata.containsKey("file_name")) {
                source = (String) metadata.get("file_name");
            } else if (metadata.containsKey("filename")) {
                source = (String) metadata.get("filename");
            }
            if (source != null && !source.isEmpty()) {
                //提取文件名（去掉路径前缀）
                int lastSlash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
                if (lastSlash > 0 && lastSlash < source.length() - 1) {
                    source = source.substring(lastSlash + 1);
                }
                references.add(source);
            }
        }
        return new ArrayList<>(references);
    }

    /**
     * 构建系统提示（包含上下文和回答规则）
     * 组装带医疗合规约束的System Prompt
     */
    public String buildSystemPrompt(String context){
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的医疗知识库问答助手，请根据以下知识库上下文回答用户的问题。\n\n");
        prompt.append("【知识库上下文】\n");
        prompt.append(context).append("\n\n");
        prompt.append("【回答规则】\n");
        prompt.append("1. 只根据上下文内容回答，不要编造上下文没有的信息\n");
        prompt.append("2. 如果上下文没有相关信息，直接回答：根据现有知识库资料，无法回答该问题\n");
        prompt.append("3. 回答要准确、简洁，尽量引用原文\n");
        prompt.append("4. 不要给出诊断结论，只提供知识查询和信息参考\n");
        prompt.append("5. 涉及药物、治疗方案时，提醒用户咨询专业医生\n");
        return prompt.toString();
    }

    /**
     * 格式化引用来源（添加序号）,附加到答案末尾
     * 组装引用来源文本，将提取到的文件名列表拼接成引用来源
     */
    public String formatReferences(List<String> references){
        if(references == null || references.isEmpty()){
            return "(无引用)";
        }
        StringBuilder refs = new StringBuilder();
        refs.append("\n\n---\n");
        refs.append("【引用(参考)来源】\n");
       for(int i = 0;i<references.size();i++){
           refs.append(i+1).append(".").append(references.get(i)).append("\n");
       }
        return refs.toString();
    }
}