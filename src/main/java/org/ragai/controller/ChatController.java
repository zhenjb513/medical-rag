package org.ragai.controller;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
            3. 每一条结论/依据后面用括号标出处，只写「文件名」和「块号」两项：（摘自《文件名.pdf》块N）。
               这两个值从上下文里每个块开头那一行取，那一行长这样：【文档1】中国心血管病一级预防指南2020.pdf#块11
               —— # 号左边是文件名，右边是块号。
               只许取这两项：不得自拟或凭记忆换成别的指南名，也不要把「【文档1】」「上下文」「《文件名.pdf》块N」这些模板占位字本身写进答案。
               取不到这两项，就说明该结论没有出处，应当删掉它或明确说明不确定。
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
    private final InventoryService inventoryService;
    private final MeterRegistry meterRegistry;

    private final Counter streamSuccessCounter;
    private final Counter streamCancelCounter;
    private final Counter streamErrorCounter;
    private final Timer   streamTtftTimer;
    private final Timer   streamTotalTimer;

    @Value("${app.retrieval.chat-top-k:8}")
    private int chatTopK;

    public ChatController(ChatClient chatClient,
                          HybridRetrievalService hybridRetrievalService,
                          MultiHopRetrievalService multiHopRetrievalService,
                          RagReferenceService ragReferenceService,
                          ChatMemoryManager chatMemoryManager,
                          InventoryService inventoryService,
                          MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
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
     * 给评估链路用：跑完整 RAG 流程，但同时返回送进模型的 docs。
     * 这里刻意不挂工具——61 题考卷全是指南问答，挂上会让每题多走一次工具选择，历史基线就不再是同一口径。
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
    @RateLimiter(name = "chatRateLimiter", fallbackMethod = "chatRateLimitFallback")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "chatCircuitFallback")
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

            String answer = chatClient.prompt(new Prompt(promptMessages)).tools(inventoryService).call().content();
            String finalAnswer = (answer == null || answer.isBlank())
                    ? RAG_REFUSAL_PHRASE : answer.trim();
            String finalText = finalAnswer + buildRefsBlock(refs);

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
    @RateLimiter(name = "chatRateLimiter", fallbackMethod = "chatStreamRateLimitFallback")
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "chatStreamCircuitFallback")
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

            long startNanos = System.nanoTime();
            AtomicBoolean firstTokenObserved = new AtomicBoolean(false);
            AtomicLong firstTokenNanos = new AtomicLong(0);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            Flux<String> tokenFlux = chatClient.prompt(prompt).tools(inventoryService).stream().content()
                    .timeout(Duration.ofSeconds(120))
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

            String refsBlock = buildRefsBlock(refs);

            return tokenFlux
                    .map(token -> ServerSentEvent.<String>builder().data(token).build())
                    .concatWith(refsAndDone(refsBlock))
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
                                chatMemoryManager.appendTurn(sessionId, message,
                                        answerBuilder.toString().trim() + refsBlock);
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

    // ========== Resilience4j Fallback 方法 ==========
    // 规则：参数和原方法一致，最后多一个 Throwable；返回值和原方法一致

    public String chatRateLimitFallback(String message, String sessionId, Throwable t) {
        log.warn("[RateLimit] 拒绝请求 message={}", message);
        return "请求过于频繁（限流 20 QPS），请稍后再试。";
    }

    public String chatCircuitFallback(String message, String sessionId, Throwable t) {
        log.warn("[CircuitBreaker] LLM 熔断降级，返回纯检索结果", t);
        String currentQuery = QueryExpander.expand(message);
        try {
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
            String ragContext = ragReferenceService.buildContext(docs);
            List<String> refs = ragReferenceService.extractReferences(docs);
            return "【服务降级】LLM 暂时不可用，以下为检索到的相关内容：\n\n"
                    + ragContext + buildRefsBlock(refs);
        } catch (Exception retryFail) {
            log.error("[CircuitBreaker] 降级也失败了", retryFail);
            return RAG_REFUSAL_PHRASE + "（检索也暂时不可用）";
        }
    }

    public Flux<ServerSentEvent<String>> chatStreamRateLimitFallback(
            String message, String sessionId, Throwable t) {
        log.warn("[RateLimit] 拒绝流式请求 message={}", message);
        return Flux.just(
                ServerSentEvent.<String>builder().data("请求过于频繁（限流 20 QPS），请稍后再试。").build(),
                ServerSentEvent.<String>builder().data("[DONE]").event("done").build()
        );
    }

    public Flux<ServerSentEvent<String>> chatStreamCircuitFallback(
            String message, String sessionId, Throwable t) {
        log.warn("[CircuitBreaker] LLM 熔断降级（流式），返回纯检索结果", t);
        String currentQuery = QueryExpander.expand(message);
        try {
            List<Document> docs = multiHopRetrievalService.search(currentQuery, chatTopK, null);
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