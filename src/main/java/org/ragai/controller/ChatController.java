package org.ragai.controller;

import org.ragai.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Validated
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    public static final String RAG_REFUSAL_PHRASE =
            "根据现有知识库资料，无法回答该问题。请换一种问法或补充更多背景信息。";

    private static final String SYSTEM_PROMPT = """
            你是三甲医院心内科主任医师，只能依据提供的临床指南回答。
            必须遵守：
            1. 不得编造指南外的医学事实；不确定时明确说明。
            2. 不得给出个体化诊断或用药方案，仅提供循证医学依据。
            3. 每一条结论/依据后面必须用括号标注出处，格式：（摘自《指南文件名》版本号 块号）。
               例如：诊室高血压诊断界值为收缩压≥140mmHg 且 舒张压≥90mmHg（摘自《中国高血压防治指南》2023版 块7）。
               上下文里的每个文档块都有编号（【文档1】/【文档2】...），对号入座即可。
            4. 患者隐私（姓名/身份证/病历号）不得在回复中出现。
            5. 回复结构：先给结论，再列带标注的依据，最后附引用来源清单。
            """;

    private static final String SYSTEM_PROMPT_NO_ANNOT = """
            你是三甲医院心内科主任医师，只能依据提供的临床指南回答。
            必须遵守：
            1. 不得编造指南外的医学事实；不确定时明确说明。
            2. 不得给出个体化诊断或用药方案，仅提供循证医学依据。
            3. 所有结论必须标注对应的指南名称与年份。
            4. 患者隐私（姓名/身份证/病历号）不得在回复中出现。
            5. 回复结构：先给结论，再列依据，最后附引用来源。
            """;

    private final ChatClient chatClient;
    private final HybridRetrievalService hybridRetrievalService;
    private final MultiHopRetrievalService multiHopRetrievalService;
    private final RagReferenceService ragReferenceService;
    private final ChatMemoryManager chatMemoryManager;

    @Value("${app.retrieval.chat-top-k:8}")
    private int chatTopK;

    public ChatController(ChatClient chatClient,
                          HybridRetrievalService hybridRetrievalService,
                          MultiHopRetrievalService multiHopRetrievalService,
                          RagReferenceService ragReferenceService,
                          ChatMemoryManager chatMemoryManager) {
        this.chatClient = chatClient;
        this.hybridRetrievalService = hybridRetrievalService;
        this.multiHopRetrievalService = multiHopRetrievalService;
        this.ragReferenceService = ragReferenceService;
        this.chatMemoryManager = chatMemoryManager;
    }

    public record ChatTrace(String answer, List<Document> docs, List<String> refs) {
    }

    /**
     * 给评估链路用：跑完整 RAG 流程，但同时返回送进模型的 docs。
     */
    public ChatTrace chatWithTrace(String message) {
        String currentQuery = QueryExpander.expand(message);
        List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
        String ragContext = ragReferenceService.buildContext(docs);
        List<String> refs = ragReferenceService.extractReferences(docs);
        String fullPrompt = SYSTEM_PROMPT + "\n\n【知识库上下文】\n" + ragContext + "\n\n【用户问题】\n" + message;

        List<Message> promptMessages = List.of(
                new org.springframework.ai.chat.messages.SystemMessage(SYSTEM_PROMPT),
                new org.springframework.ai.chat.messages.UserMessage(fullPrompt)
        );
        String answer;
        try {
            answer = chatClient.prompt(new Prompt(promptMessages)).call().content();
        } catch (Exception e) {
            answer = RAG_REFUSAL_PHRASE + "（服务暂时不可用：" + e.getMessage() + "）";
        }
        if (answer == null || answer.isBlank()) answer = RAG_REFUSAL_PHRASE;
        return new ChatTrace(answer.trim(), docs, refs);
    }

    @GetMapping("/chat")
    public String chat(@RequestParam(value = "message") @NotBlank(message = "问题不能为空") String message,
                       @RequestParam(value = "sessionId", required = false) @Size(max = 64) String sessionId) {
        try {
            String currentQuery = QueryExpander.expand(message);
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
            String ragContext = ragReferenceService.buildContext(docs);
            List<String> refs = ragReferenceService.extractReferences(docs);
            String fullPrompt = SYSTEM_PROMPT + "\n\n【知识库上下文】\n" + ragContext + "\n\n【用户问题】\n" + message;

            List<Message> promptMessages;
            if (sessionId != null && !sessionId.isBlank()) {
                promptMessages = chatMemoryManager.buildPromptMessages(sessionId, SYSTEM_PROMPT, fullPrompt);
            } else {
                promptMessages = List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(SYSTEM_PROMPT),
                        new org.springframework.ai.chat.messages.UserMessage(fullPrompt)
                );
            }

            String answer = chatClient.prompt(new Prompt(promptMessages)).call().content();
            String finalAnswer = (answer == null || answer.isBlank())
                    ? RAG_REFUSAL_PHRASE : answer.trim();
            String finalText = finalAnswer + "\n\n【引用来源】\n"
                    + String.join("\n", refs);

            if (sessionId != null && !sessionId.isBlank()) {
                chatMemoryManager.appendTurn(sessionId, message, finalText);
            }
            return finalText;
        } catch (Exception e) {
            log.error("chat error", e);
            return RAG_REFUSAL_PHRASE + "（服务暂时不可用：" + e.getMessage() + "）";
        }
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam("message") @NotBlank(message = "问题不能为空") String message,
            @RequestParam(value = "sessionId", required = false) @Size(max = 64) String sessionId) {

        try {
            String currentQuery = QueryExpander.expand(message);
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
            String ragContext = ragReferenceService.buildContext(docs);
            List<String> refs = ragReferenceService.extractReferences(docs);
            String fullPrompt = SYSTEM_PROMPT + "\n\n【知识库上下文】\n" + ragContext + "\n\n【用户问题】\n" + message;

            List<Message> promptMessages;
            if (sessionId != null && !sessionId.isBlank()) {
                promptMessages = chatMemoryManager.buildPromptMessages(sessionId, SYSTEM_PROMPT, fullPrompt);
            } else {
                promptMessages = List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(SYSTEM_PROMPT),
                        new org.springframework.ai.chat.messages.UserMessage(fullPrompt)
                );
            }

            Prompt prompt = new Prompt(promptMessages);
            StringBuilder answerBuilder = new StringBuilder();

            Flux<String> tokenFlux = chatClient.prompt(prompt).stream().content()
                    .timeout(Duration.ofSeconds(120))
                    .doOnNext(answerBuilder::append)
                    .onErrorResume(e -> {
                        log.error("stream error", e);
                        return Flux.just("\n\n[生成中断：" + e.getMessage() + "]");
                    });

            String refsBlock = "\n\n【引用来源】\n" + String.join("\n", refs);

            return tokenFlux
                    .map(token -> ServerSentEvent.<String>builder().data(token).build())
                    .concatWith(Flux.just(
                            ServerSentEvent.<String>builder().data(refsBlock).event("refs").build(),
                            ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
                    ))
                    .doOnComplete(() -> {
                        if (sessionId != null && !sessionId.isBlank()) {
                            chatMemoryManager.appendTurn(sessionId, message,
                                    answerBuilder.toString().trim() + refsBlock);
                        }
                    });

        } catch (Exception e) {
            log.error("chatStream setup error", e);
            return Flux.just(
                    ServerSentEvent.<String>builder()
                            .data(RAG_REFUSAL_PHRASE + "（服务暂时不可用：" + e.getMessage() + "）").build(),
                    ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
            );
        }
    }

    @PostMapping("/chat/session/new")
    public Map<String, String> newSession() {
        Map<String, String> r = new HashMap<>();
        r.put("sessionId", chatMemoryManager.newSessionId());
        return r;
    }

    @DeleteMapping("/chat/session/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        chatMemoryManager.remove(sessionId);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        r.put("sessionId", sessionId);
        return r;
    }

    @GetMapping("/chat/session/stats")
    public Map<String, Object> sessionStats() {
        Map<String, Object> r = new HashMap<>();
        r.put("activeSessions", chatMemoryManager.activeSessionCount());
        r.put("maxRoundsPerSession", ChatMemoryManager.DEFAULT_MAX_ROUNDS);
        r.put("sessionTtlMs", ChatMemoryManager.DEFAULT_TTL_MS);
        return r;
    }
}