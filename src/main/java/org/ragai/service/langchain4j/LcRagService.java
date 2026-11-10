package org.ragai.service.langchain4j;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ReRankingContentAggregator;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 侧的 RAG 编排：混合检索 → 重排 → 上下文注入 → 生成 → 引用溯源。
 *
 * <p>对应 Spring AI 侧 {@code ChatController} 的 RAG 主链路，是端到端的
 * LangChain4j 实现，用于和 Spring AI 侧做同题对照。
 */
@Service
public class LcRagService {

    private static final Logger log = LoggerFactory.getLogger(LcRagService.class);

    private final LcHybridRetrievalService hybridRetrieval;
    private final DashScopeScoringModel scoringModel;
    private final ChatLanguageModel chatModel;

    @Value("${app.rag.rerank-top-n:4}")
    private int rerankTopN;

    @Value("${app.rag.rerank-min-score:0.3}")
    private double rerankMinScore;

    @Value("${app.rag.enable-rerank:true}")
    private boolean enableRerank;

    public LcRagService(LcHybridRetrievalService hybridRetrieval,
                        DashScopeScoringModel scoringModel,
                        ChatLanguageModel chatModel) {
        this.hybridRetrieval = hybridRetrieval;
        this.scoringModel = scoringModel;
        this.chatModel = chatModel;
    }

    /**
     * 执行一次完整的 RAG 问答。
     */
    public RagAnswer ask(String question) {
        long start = System.currentTimeMillis();

        // ① 混合检索
        List<Content> candidates = hybridRetrieval.retrieve(question);

        // ② 重排
        List<Content> selected = candidates;
        if (enableRerank && candidates.size() > 1) {
            ReRankingContentAggregator aggregator = ReRankingContentAggregator.builder()
                    .scoringModel(scoringModel)
                    .minScore(rerankMinScore)
                    .maxResults(rerankTopN)
                    .build();
            var queryMap = java.util.Map.of(Query.from(question),
                    (java.util.Collection<List<Content>>) List.of(candidates));
            selected = aggregator.aggregate(queryMap);
        }

        // ③ 组装上下文
        StringBuilder context = new StringBuilder();
        List<String> references = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            Content content = selected.get(i);
            String text = content.textSegment().text();
            context.append("【文档").append(i + 1).append("】").append(text).append("\n\n");
            String fileName = content.textSegment().metadata().getString("file_name");
            if (fileName != null) {
                references.add("《" + fileName + "》");
            } else {
                references.add("【文档" + (i + 1) + "】");
            }
        }

        // ④ 注入 prompt 生成
        String prompt = "你是一个知识库问答助手。请严格根据下面的资料回答问题，"
                + "如果资料里没有相关信息，请回答『资料里没有』，不要编造。\n\n"
                + "【资料】\n" + context + "\n【问题】\n" + question;

        // 1.0.0-beta3 的 ChatLanguageModel 用 chat(List<ChatMessage>)，返回 ChatResponse
        dev.langchain4j.model.chat.response.ChatResponse response =
                chatModel.chat(List.of(UserMessage.from(prompt)));
        String answer = response.aiMessage().text();

        long elapsed = System.currentTimeMillis() - start;
        log.info("RAG 完成：candidates={}, selected={}, {}ms, tokens={}",
                candidates.size(), selected.size(), elapsed,
                response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0);

        return new RagAnswer(answer, references, selected.size(), elapsed);
    }

    /**
     * RAG 回答结果。
     */
    public record RagAnswer(String answer, List<String> references, int selectedCount, long elapsedMs) {}
}
