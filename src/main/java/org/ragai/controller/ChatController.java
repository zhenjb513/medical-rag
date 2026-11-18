package org.ragai.controller;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.ragai.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@Validated
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public static final String RAG_REFUSAL_PHRASE =
            "根据现有知识库资料，无法回答该问题。请换一种问法或补充更多背景信息。";

    /**
     * 匹配模型在答案末尾自列的引用来源标题行。模型可能输出的变体：
     * 【引用来源】、[引用来源]、（引用来源）、引用来源：、参考文献、参考资料、资料来源……
     * 行首允许空白和左括号，行尾允许右括号和冒号。匹配到此行即截断，引用来源统一由后端提供。
     */
    private static final Pattern MODEL_REFS_PATTERN =
            Pattern.compile("(?m)^[\\s（(【\\[]*(引用来源|参考文献|参考资料|资料来源)[\\s）)】\\]:：]*$");

    private static final String SYSTEM_PROMPT = """
            你是三甲医院心内科主任医师，只能依据提供的临床指南回答。
            必须遵守：
            1. 不得编造指南外的医学事实；不确定时明确说明。
            2. 不得给出个体化诊断或用药方案，仅提供循证医学依据。
            3. 每一条结论/依据后面用括号标出处，只写「文件名」和「块号」两项：（摘自《文件名.pdf》块N）。
               这两个值从上下文里每个块开头那一行取，那一行长这样：【文档1】中国心血管病一级预防指南2020.pdf#块11
               —— # 号左边是文件名，右边是块号。
               只许取这两项：不得自拟或凭记忆换成别的指南名，也不要把「【文档1】」「上下文」「《文件名.pdf》块N」这些模板占位字本身写进答案。
               取不到这两项，就说明该结论没有出处，应当删掉它或明确说明不确定。
            4. 患者隐私（姓名/身份证/病历号）不得在回复中出现。
            5. 回复结构：先给结论，再列带标注的依据。
            （引用来源由系统在答案末尾自动附上，不要自行罗列清单。）
            """;

    private static final String SYSTEM_PROMPT_NO_ANNOT = """
            你是三甲医院心内科主任医师，只能依据提供的临床指南回答。
            必须遵守：
            1. 不得编造指南外的医学事实；不确定时明确说明。
            2. 不得给出个体化诊断或用药方案，仅提供循证医学依据。
            3. 所有结论必须标注对应的指南名称与年份。
            4. 患者隐私（姓名/身份证/病历号）不得在回复中出现。
            5. 回复结构：先给结论，再列依据。
            （引用来源由系统在答案末尾自动附上，不要自行罗列清单。）
            """;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final HybridRetrievalService hybridRetrievalService;
    private final MultiHopRetrievalService multiHopRetrievalService;
    private final RagReferenceService ragReferenceService;
    private final ChatMemoryManager chatMemoryManager;
    private final InventoryService inventoryService;
    private final MeterRegistry meterRegistry;

    private final Counter streamSuccessCounter;
    private final Counter streamCancelCounter;
    private final Counter streamErrorCounter;
    private final Timer   streamTtftTimer;
    private final Timer   streamTotalTimer;

    @Value("${app.retrieval.chat-top-k:8}")
    private int chatTopK;

    public ChatController(ChatModel chatModel,
                          StreamingChatModel streamingChatModel,
                          HybridRetrievalService hybridRetrievalService,
                          MultiHopRetrievalService multiHopRetrievalService,
                          RagReferenceService ragReferenceService,
                          ChatMemoryManager chatMemoryManager,
                          InventoryService inventoryService,
                          MeterRegistry meterRegistry) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.hybridRetrievalService = hybridRetrievalService;
        this.multiHopRetrievalService = multiHopRetrievalService;
        this.ragReferenceService = ragReferenceService;
        this.chatMemoryManager = chatMemoryManager;
        this.inventoryService = inventoryService;
        this.meterRegistry = meterRegistry;

        this.streamSuccessCounter = Counter.builder("rag.stream.requests")
                .tag("result", "completed").register(meterRegistry);
        this.streamCancelCounter = Counter.builder("rag.stream.requests")
                .tag("result", "cancelled").register(meterRegistry);
        this.streamErrorCounter = Counter.builder("rag.stream.requests")
                .tag("result", "error").register(meterRegistry);
        this.streamTtftTimer = Timer.builder("rag.stream.ttft")
                .description("Time To First Token")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(200),
                        Duration.ofMillis(500),
                        Duration.ofMillis(1000),
                        Duration.ofMillis(2000),
                        Duration.ofMillis(5000)
                )
                .register(meterRegistry);
        this.streamTotalTimer = Timer.builder("rag.stream.duration")
                .description("Total stream duration")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(30)
                )
                .register(meterRegistry);
    }

    public record ChatTrace(String answer, List<Document> docs, List<String> refs) {
    }

    /**
     * 引用列表为空就不拼页脚。检索闸门判定「这问题指南答不了」时 docs/refs 都是空的，
     * 原来无条件拼标题，答案末尾会挂一个光杆「【引用来源】」（2026-11-09 库存问答实测，scratch/t10_answer2.txt）。
     */
    static String buildRefsBlock(List<String> refs) {
        return refs == null || refs.isEmpty() ? "" : "\n\n【引用来源】\n" + String.join("\n", refs);
    }

    /** 前端一收到 refs 事件就画引用框（static/app.js 的 eventName === 'refs' 分支），所以空列表连事件都不发。 */
    static Flux<ServerSentEvent<String>> refsAndDone(String refsBlock) {
        ServerSentEvent<String> done = ServerSentEvent.<String>builder().data("[DONE]").event("done").build();
        return refsBlock.isEmpty()
                ? Flux.just(done)
                : Flux.just(ServerSentEvent.<String>builder().data(refsBlock).event("refs").build(), done);
    }

    /**
     * 答案是「引用到知识库来源才给引用来源框」。判断依据：答案正文里出现了按规则 3 生成的内联
     * 引用标注「（摘自《…》…）」。火星上有没有指南这种问题，模型没有引到任何文档，answers 里
     * 没有摘自标注，就不下发 refs 事件，前端自然不画引用框。
     */
    static boolean hasInlineCitation(String answer) {
        return answer != null && answer.contains("摘自《");
    }

    /**
     * 模型有时不听提示词，会在答案末尾自列「【引用来源】」清单，和后端 {@link #buildRefsBlock}
     * 拼的精确来源重复且不一致。这里把模型自输的引用来源标题行及之后的内容全部裁掉，
     * 引用来源统一由后端提供。
     */
    static String stripModelRefs(String answer) {
        if (answer == null) {
            return "";
        }
        Matcher m = MODEL_REFS_PATTERN.matcher(answer);
        return m.find() ? answer.substring(0, m.start()).trim() : answer.trim();
    }

    /**
     * 给评估链路用：跑完整 RAG 流程，但同时返回送进模型的 docs。
     * 这里刻意不挂工具——61 题考卷全是指南问答，挂上会让每题多走一次工具选择，历史基线就不再是同一口径。
     */
    public ChatTrace chatWithTrace(String message) {
        String currentQuery = QueryExpander.expand(message);
        List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
        String ragContext = ragReferenceService.buildContext(docs);
        List<String> refs = ragReferenceService.extractReferences(docs);
        String fullPrompt = SYSTEM_PROMPT + "\n\n【知识库上下文】\n" + ragContext + "\n\n【用户问题】\n" + message;

        List<ChatMessage> promptMessages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(fullPrompt)
        );
        String answer;
        try {
            ChatResponse response = chatModel.chat(promptMessages);
            answer = response.aiMessage().text();
        } catch (Exception e) {
            answer = RAG_REFUSAL_PHRASE + "（服务暂时不可用：" + e.getMessage() + "）";
        }
        if (answer == null || answer.isBlank()) answer = RAG_REFUSAL_PHRASE;
        return new ChatTrace(answer.trim(), docs, refs);
    }

    /**
     * {@code versions=} / {@code docTypes=} 是可选白名单（逗号/全角逗号/顿号分隔，最多 8 个值），
     * 与 /evaluation/retrieve 同一套解析与后过滤语义：先按 chatTopK 召回再剔掉不在白名单里的块，
     * 过滤后上下文条数可能少于 chatTopK。留空 = 不过滤，行为与加参数前完全一致。
     * ⚠️ doc_type 是 2026-11-18 才加的入库 metadata，重灌前的旧块没有这个键，docTypes= 会把它们全剔掉。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "chatRateLimiter", fallbackMethod = "chatStreamRateLimitFallback")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "chatStreamCircuitFallback")
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam("message") @NotBlank(message = "问题不能为空") String message,
            @RequestParam(value = "sessionId", required = false) @Size(max = 64) String sessionId,
            @RequestParam(value = "versions", required = false) String versions,
            @RequestParam(value = "docTypes", required = false) String docTypes) {

        try {
            String currentQuery = QueryExpander.expand(message);
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null,
                    EvaluationController.parseVersions(versions), EvaluationController.parseVersions(docTypes));
            String ragContext = ragReferenceService.buildContext(docs);
            List<String> refs = ragReferenceService.extractReferences(docs);
            String fullPrompt = SYSTEM_PROMPT + "\n\n【知识库上下文】\n" + ragContext + "\n\n【用户问题】\n" + message;

            List<ChatMessage> promptMessages;
            if (sessionId != null && !sessionId.isBlank()) {
                promptMessages = chatMemoryManager.buildPromptMessages(sessionId, SYSTEM_PROMPT, fullPrompt);
            } else {
                promptMessages = List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(fullPrompt)
                );
            }

            StringBuilder answerBuilder = new StringBuilder();

            long startNanos = System.nanoTime();
            AtomicBoolean firstTokenObserved = new AtomicBoolean(false);
            AtomicLong firstTokenNanos = new AtomicLong(0);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            AtomicBoolean refsSuppressed = new AtomicBoolean(false);
            StringBuilder tailBuf = new StringBuilder();

            Flux<String> tokenFlux = Flux.<String>create(sink -> {
                try {
                    streamingChatModel.chat(
                            dev.langchain4j.model.chat.request.ChatRequest.builder()
                                    .messages(promptMessages)
                                    .build(),
                            new StreamingChatResponseHandler() {
                                @Override
                                public void onPartialResponse(String partial) {
                                    if (partial != null && !partial.isEmpty()) {
                                        sink.next(partial);
                                    }
                                }

                                @Override
                                public void onCompleteResponse(ChatResponse response) {
                                    sink.complete();
                                }

                                @Override
                                public void onError(Throwable error) {
                                    sink.error(error);
                                }
                            });
                } catch (Exception e) {
                    sink.error(e);
                }
            })
                    .timeout(Duration.ofSeconds(120))
                    .map(token -> {
                        if (refsSuppressed.get()) {
                            return "";
                        }
                        tailBuf.append(token);
                        String combined = tailBuf.toString();
                        Matcher m = MODEL_REFS_PATTERN.matcher(combined);
                        if (m.find()) {
                            refsSuppressed.set(true);
                            String kept = combined.substring(0, m.start());
                            tailBuf.setLength(0);
                            return kept;
                        }
                        int lastNewline = combined.lastIndexOf('\n');
                        if (lastNewline >= 0) {
                            String flush = combined.substring(0, lastNewline + 1);
                            tailBuf.setLength(0);
                            tailBuf.append(combined.substring(lastNewline + 1));
                            return flush;
                        }
                        return "";
                    })
                    .filter(s -> !s.isEmpty())
                    .doOnNext(token -> {
                        if (firstTokenObserved.compareAndSet(false, true)) {
                            long ttft = System.nanoTime() - startNanos;
                            firstTokenNanos.set(ttft);
                            streamTtftTimer.record(Duration.ofNanos(ttft));
                            log.debug("[Stream] TTFT = {} ms", ttft / 1_000_000);
                        }
                        answerBuilder.append(token);
                    })
                    .onErrorResume(e -> {
                        log.error("stream error", e);
                        streamErrorCounter.increment();
                        return Flux.just("\n\n[生成中断：" + e.getMessage() + "]");
                    });

            // refs 决定必须等到答案 token 流结束（answerBuilder 就绪）后再做，
            // 所以用 defer 惰性求值；hasInlineCitation 判断模型是否真的引用了来源。
            String refsBlock = buildRefsBlock(refs);

            return tokenFlux
                    .map(token -> ServerSentEvent.<String>builder().data(token).build())
                    .concatWith(Flux.defer(() -> {
                        String answer = answerBuilder.toString().trim();
                        // 模型面对不相关/空检索常直接吐空答案（实测「骨科缝合包还有多少」answerLen=0），
                        // 前端只拿到一条空流。空答案时改走带库存工具的同步双轮：让模型决定是否调库存工具，
                        // 仍答不了则回退拒绝语——保证前端一定有内容可显示。
                        if (answer.isEmpty()) {
                            String toolAnswer = chatWithTools(promptMessages);
                            if (toolAnswer == null || toolAnswer.isBlank()) {
                                toolAnswer = RAG_REFUSAL_PHRASE;
                            }
                            String finalAnswer = stripModelRefs(toolAnswer);
                            answerBuilder.setLength(0);
                            answerBuilder.append(finalAnswer);
                            log.info("[Stream] 模型空答案，走工具兜底 answerLen={}", finalAnswer.length());
                            return Flux.just(
                                    ServerSentEvent.<String>builder().data(finalAnswer).build(),
                                    ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
                            );
                        }
                        boolean cited = hasInlineCitation(answer);
                        return refsAndDone(cited ? refsBlock : "");
                    }))
                    .doOnCancel(() -> {
                        cancelled.set(true);
                        streamCancelCounter.increment();
                        log.warn("[Stream] cancelled by client, sessionId={}, answerLen={}",
                                sessionId, answerBuilder.length());
                    })
                    .doFinally(signalType -> {
                        long totalNanos = System.nanoTime() - startNanos;
                        streamTotalTimer.record(Duration.ofNanos(totalNanos));

                        if (cancelled.get()) {
                            log.info("[Stream] finalize={} (cancelled), total={}ms, answerLen={}",
                                    signalType, totalNanos / 1_000_000, answerBuilder.length());
                        } else if (signalType == reactor.core.publisher.SignalType.ON_COMPLETE) {
                            streamSuccessCounter.increment();
                            if (sessionId != null && !sessionId.isBlank()) {
                                boolean cited = hasInlineCitation(answerBuilder.toString());
                                chatMemoryManager.appendTurn(sessionId, message,
                                        answerBuilder.toString().trim() + (cited ? refsBlock : ""));
                            }
                            log.info("[Stream] completed, ttft={}ms, total={}ms, answerLen={}",
                                    firstTokenNanos.get() / 1_000_000,
                                    totalNanos / 1_000_000, answerBuilder.length());
                        } else {
                            log.info("[Stream] finalize={}, total={}ms", signalType, totalNanos / 1_000_000);
                        }
                    });

        } catch (Exception e) {
            log.error("chatStream setup error", e);
            streamErrorCounter.increment();
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

    /**
     * 带工具调用的对话。
     * <p>
     * LangChain4j 的工具调用分两轮：先让模型决定是否调工具，拿到 {@code toolExecutionRequests}
     * 后本地执行（本分支的工具只有库存查询），再把结果作为 ToolExecutionResultMessage 回喂，
     * 让模型基于结果生成最终回答。Spring AI 侧的 {@code .tools(inventoryService)} 把这步藏起来了，
     * 迁移后需要显式走两轮。
     */
    private String chatWithTools(List<ChatMessage> promptMessages) {
        try {
            ChatResponse first = chatModel.chat(
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(promptMessages)
                            .toolSpecifications(dev.langchain4j.agent.tool.ToolSpecifications
                                    .toolSpecificationsFrom(inventoryService))
                            .build());

            AiMessage aiMessage = first.aiMessage();
            if (aiMessage.hasToolExecutionRequests()) {
                List<ChatMessage> messages = new java.util.ArrayList<>(promptMessages);
                messages.add(aiMessage);
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String result = executeTool(req);
                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, result));
                }
                ChatResponse second = chatModel.chat(
                        dev.langchain4j.model.chat.request.ChatRequest.builder()
                                .messages(messages)
                                .toolSpecifications(dev.langchain4j.agent.tool.ToolSpecifications
                                        .toolSpecificationsFrom(inventoryService))
                                .build());
                return second.aiMessage().text();
            }
            return aiMessage.text();
        } catch (Exception e) {
            log.warn("[工具调用] 失败，降级为无工具直答：{}", e.getMessage());
            try {
                return chatModel.chat(promptMessages).aiMessage().text();
            } catch (Exception e2) {
                return RAG_REFUSAL_PHRASE + "（服务暂时不可用：" + e2.getMessage() + "）";
            }
        }
    }

    /** 执行库存工具：从请求参数里取耗材名，调用 InventoryService。 */
    private String executeTool(dev.langchain4j.agent.tool.ToolExecutionRequest req) {
        try {
            String args = req.arguments();
            if (args == null || args.isBlank()) {
                return InventoryService.missingName();
            }
            com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(args);
            String name = node.has("consumableName") ? node.get("consumableName").asText() : null;
            return inventoryService.queryConsumableStock(name);
        } catch (Exception e) {
            log.warn("[工具执行] 解析参数失败：{}", e.getMessage());
            return InventoryService.missingName();
        }
    }

    // ========== Resilience4j Fallback 方法 ==========
    // 规则：参数和原方法一致，最后多一个 Throwable；返回值和原方法一致

    public Flux<ServerSentEvent<String>> chatStreamRateLimitFallback(
            String message, String sessionId, String versions, String docTypes, Throwable t) {
        log.warn("[RateLimit] 拒绝流式请求 message={}", message);
        return Flux.just(
                ServerSentEvent.<String>builder().data("请求过于频繁（限流 20 QPS），请稍后再试。").build(),
                ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
        );
    }

    public Flux<ServerSentEvent<String>> chatStreamCircuitFallback(
            String message, String sessionId, String versions, String docTypes, Throwable t) {
        log.warn("[CircuitBreaker] LLM 熔断降级（流式），返回纯检索结果", t);
        String currentQuery = QueryExpander.expand(message);
        try {
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null,
                    EvaluationController.parseVersions(versions), EvaluationController.parseVersions(docTypes));
            String ragContext = ragReferenceService.buildContext(docs);
            List<String> refs = ragReferenceService.extractReferences(docs);
            String fallbackText = "【服务降级】LLM 暂时不可用，以下为检索到的相关内容：\n\n" + ragContext;
            return Flux.just(ServerSentEvent.<String>builder().data(fallbackText).build())
                    .concatWith(refsAndDone(buildRefsBlock(refs)));
        } catch (Exception retryFail) {
            log.error("[CircuitBreaker] 流式降级也失败了", retryFail);
            return Flux.just(
                    ServerSentEvent.<String>builder().data(RAG_REFUSAL_PHRASE + "（检索也暂时不可用）").build(),
                    ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
            );
        }
    }
}