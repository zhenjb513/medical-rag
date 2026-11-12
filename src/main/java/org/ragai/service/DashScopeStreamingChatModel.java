package org.ragai.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 DashScope 流式接口的 {@link StreamingChatLanguageModel} 实现。
 *
 * <p><b>为什么需要自己写</b>：LangChain4j 官方 DashScope 社区模块（1.0.0-beta3）
 * 只提供了同步的 {@code QwenChatModel}，没有实现 {@code StreamingChatLanguageModel}。
 * 但 LangChain4j 本身有完整的流式抽象（{@link StreamingChatLanguageModel} +
 * {@link StreamingChatResponseHandler}），DashScope SDK 也原生支持
 * {@code Generation.streamCall()} 返回 RxJava {@code Flowable}。
 * 这里就是把两者接起来，让本分支的 /chat/stream 端点保持真流式。
 *
 * 工具调用（tool calls）：DashScope 的流式响应里，工具调用信息在
 * {@code output.choices[0].message.toolCalls} 中增量下发。当前实现聚焦文本流，
 * 工具调用走同步的 {@code ChatLanguageModel} 路径，避免流式工具调用的复杂状态机。
 */
@Component
public class DashScopeStreamingChatModel implements StreamingChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeStreamingChatModel.class);

    // LangChain4j 社区 DashScope 模块的配置键，与 application-local.yml 一致
    @Value("${langchain4j.community.dashscope.chat-model.api-key:}")
    private String apiKey;

    @Value("${app.model.chat:qwen-plus}")
    private String model;

    @Value("${app.llm.max-tokens:2048}")
    private int maxTokens;

    @Value("${app.llm.temperature:0.7}")
    private double temperature;

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        if (apiKey == null || apiKey.isBlank()) {
            handler.onError(new IllegalStateException("未配置 DASHSCOPE_API_KEY，无法调用 DashScope 流式接口"));
            return;
        }

        try {
            Generation gen = new Generation();
            GenerationParam param = buildParam(chatRequest);

            Flowable<GenerationResult> flowable = gen.streamCall(param);
            if (flowable == null) {
                handler.onError(new IllegalStateException("DashScope streamCall 返回 null"));
                return;
            }

            AtomicReference<TokenUsage> usageRef = new AtomicReference<>();

            flowable.subscribe(
                    result -> emitPartial(result, handler, usageRef),
                    error -> {
                        log.warn("[DashScope 流式] 中途出错：{}", error.getMessage());
                        handler.onError(error instanceof Exception e ? e : new RuntimeException(error));
                    },
                    () -> {
                        TokenUsage usage = usageRef.get() != null ? usageRef.get() : new TokenUsage(0, 0);
                        ChatResponse response = ChatResponse.builder()
                                .aiMessage(AiMessage.from(""))
                                .tokenUsage(usage)
                                .build();
                        handler.onCompleteResponse(response);
                    }
            );
        } catch (Exception e) {
            log.warn("[DashScope 流式] 调用失败：{}", e.getMessage());
            handler.onError(e);
        }
    }

    /** 把 LangChain4j 的 ChatMessage 列表转成 DashScope 的 Message 列表。 */
    private GenerationParam buildParam(ChatRequest chatRequest) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage cm : chatRequest.messages()) {
            if (cm instanceof UserMessage um) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(um.singleText())
                        .build());
            } else if (cm instanceof dev.langchain4j.data.message.SystemMessage sm) {
                messages.add(Message.builder()
                        .role(Role.SYSTEM.getValue())
                        .content(sm.text())
                        .build());
            } else if (cm instanceof AiMessage am && am.text() != null && !am.text().isBlank()) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(am.text())
                        .build());
            }
        }

        return GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .messages(messages)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .maxTokens(Integer.valueOf(maxTokens))
                .temperature(Float.parseFloat(String.valueOf(temperature)))
                .incrementalOutput(true)
                .build();
    }

    /** 从增量结果里取出文本片段回调给 handler，同时累计 token 用量。 */
    private void emitPartial(GenerationResult result, StreamingChatResponseHandler handler,
                             AtomicReference<TokenUsage> usageRef) {
        if (result.getOutput() == null) {
            return;
        }
        List<GenerationOutput.Choice> choices = result.getOutput().getChoices();
        if (choices != null && !choices.isEmpty()) {
            String text = choices.get(0).getMessage() == null
                    ? null : choices.get(0).getMessage().getContent();
            if (text != null && !text.isEmpty()) {
                handler.onPartialResponse(text);
            }
        }
        if (result.getUsage() != null) {
            Integer input = result.getUsage().getInputTokens();
            Integer output = result.getUsage().getOutputTokens();
            usageRef.set(new TokenUsage(input == null ? 0 : input, output == null ? 0 : output));
        }
    }
}
