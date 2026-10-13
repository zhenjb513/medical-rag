package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.PgVectorSchemaInitializer;
import org.example.service.InventoryService;
import org.example.service.PhiDesensitizationService;
import org.example.service.RagInitializer;
import org.example.service.RagReferenceService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final InventoryService inventoryService;
    private final RagInitializer ragInitializer;
    private final ApplicationContext applicationContext;
    private final VectorStore vectorStore;
    private final PhiDesensitizationService phiDesensitizationService;
    private final RagReferenceService ragReferenceService;
    private final org.example.service.HybridRetrievalService hybridRetrievalService;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public ChatController(ChatClient.Builder chatClientBuilder,
                          InventoryService inventoryService,
                          VectorStore vectorStore,
                          ApplicationContext applicationContext,
                          PhiDesensitizationService phiDesensitizationService,
                          RagReferenceService ragReferenceService,
                          org.example.service.HybridRetrievalService hybridRetrievalService,
                          @Autowired(required = false) RagInitializer ragInitializer) {
        this.inventoryService = inventoryService;
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.ragInitializer = ragInitializer;
        this.phiDesensitizationService = phiDesensitizationService;
        this.ragReferenceService = ragReferenceService;
        this.hybridRetrievalService = hybridRetrievalService;

        //        boolean vectorStoreReady = true;
        //        if (applicationContext.containsBean("pgVectorSchemaInitializer")) {
        //            PgVectorSchemaInitializer init = applicationContext.getBean(PgVectorSchemaInitializer.class);
        //            vectorStoreReady = init.isReady();
        //        }
        //
        //        ChatClient.Builder builder = chatClientBuilder;
        //        if (Objects.nonNull(vectorStore) && vectorStoreReady) {
        //            builder = builder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build());
        //        }
        //注意:这里不在使用QuestionAnswerAdvisor，改为手动检索(ragReferenceService)
        //因为手动检索可以精确控制引用溯源和System prompt
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatWithTrace(message).answer();
    }

    /**
     * 检索 + 生成一次完整链路，并把「本次真正送进模型的上下文」一起带出来。
     * 评测判分要用它：只看答案里有没有那个数字，分不出是库里的还是模型自带的。
     */
    public ChatTrace chatWithTrace(String message) {
        // 1.PHI脱敏：在进入RAG检索和大模型前，对用户输入进行隐私信息脱敏
        String sanitizedMessage = phiDesensitizationService.desensitize(message);
        boolean hasPhi = phiDesensitizationService.containsPhi(message);

        // 2.检索：下限阈值统一由 app.retrieval 配置决定（vector-floor 取数下限 + answer-floor 可答性闸门）。
        //    这里不再传 0.65：实测那道逐条阈值会把正确题的候选从 20 条砍到 2 条，却照样放行超纲问题。
        List<Document> docs = hybridRetrievalService.search(sanitizedMessage, 8, null);
        if (hasPhi) {
            System.out.println("[PHI脱敏] 监测到敏感信息，原始: \"" + message + "\"  脱敏后: \"" + sanitizedMessage + "\"");
        }
        System.out.println("[RAG检索] 问题: \"" + sanitizedMessage + "\"" + (hasPhi ? " (已脱敏)" : ""));
        System.out.println("[RAG检索] 模式=" + hybridRetrievalService.getMode()
                + "  召回文档数量: " + docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double score = doc.getScore() != null ? doc.getScore() : -1.0;
            String source = extractFileName(doc);
            String preview = doc.getText() == null ? "" : doc.getText().replaceAll("[\\r\\n\\t]+", " ").trim();
            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
            System.out.printf("[RAG检索]   #%d  score=%.4f  source=%s  预览: %s%n", i + 1, score, source, preview);
        }

        //3.组装上下文和带医疗合规约束的System prompt
        String context = ragReferenceService.buildContext(docs);
        String systemPrompt = ragReferenceService.buildSystemPrompt(context);

        //4.提取引用来源(去重后的文件名列表)
        List<String> references = ragReferenceService.extractReferences(docs);
        System.out.println("[RAG检索] 提取到的引用来源: " + references);

        //5.调用大模型
        org.springframework.ai.chat.model.ChatResponse chatResponse = chatClient.prompt()
                .system(systemPrompt)
                .user(sanitizedMessage)
                .tools(inventoryService)
                .call()
                .chatResponse();
        String answer = chatResponse == null || chatResponse.getResult() == null
                ? "" : chatResponse.getResult().getOutput().getText();
        // 用量必须落日志：以前只取 .content()，全项目没有任何 token 记录，付费调用的规模无法核对
        if (chatResponse != null) {
            org.springframework.ai.chat.metadata.Usage usage = chatResponse.getMetadata().getUsage();
            System.out.println("[生成用量] " + (usage == null ? "响应里没有 usage 元数据"
                    : "prompt=" + usage.getPromptTokens() + " completion=" + usage.getCompletionTokens()
                            + " total=" + usage.getTotalTokens())
                    + "  上下文块数=" + docs.size() + "  上下文字符=" + context.length());
        }
        //6.格式化引用来源（添加序号）,附加到答案末尾
        String formattedReferencestext = ragReferenceService.formatReferences(references);

        return new ChatTrace(answer + formattedReferencestext, docs, context);
    }

    /**
     * answer=给用户看的正文（已拼上引用来源），docs=本次送进模型的块（含邻居扩展），
     * context=拼好的上下文字符串，和 docs 是同一批内容。
     */
    public record ChatTrace(String answer, List<Document> docs, String context) {
    }

    private String extractFileName(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String source = null;
        if (meta.containsKey("source")) {
            source = String.valueOf(meta.get("source"));
        } else if (meta.containsKey("file_name")) {
            source = String.valueOf(meta.get("file_name"));
        }
        if (source != null) {
            int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
            if (slash > 0 && slash < source.length() - 1) {
                return source.substring(slash + 1);
            }
        }
        return "<unknown>";
    }

    /**
     * 运行时 RAG 诊断接口，返回 VectorStore 类型、PgVector 初始化状态及各 PDF 提取诊断信息。
     */
    @GetMapping(value = "/rag/diag", produces = "application/json; charset=utf-8")
    public String ragDiag() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector_store_type", vectorStore == null ? "<NULL>" : vectorStore.getClass().getName());
        out.put("vector_store_is_null", vectorStore == null);

        boolean hasPgInit = applicationContext.containsBean("pgVectorSchemaInitializer");
        out.put("pg_vector_initializer_exists", hasPgInit);
        if (hasPgInit) {
            PgVectorSchemaInitializer init = applicationContext.getBean(PgVectorSchemaInitializer.class);
            out.put("pg_vector_initializer_ready", init.isReady());
        }

        if (ragInitializer != null) {
            out.put("rag_initializer", ragInitializer.diagnose());
        } else {
            out.put("rag_initializer", "<NULL：RagInitializer Bean 未注册>");
        }
        return jsonMapper.writeValueAsString(out);
    }
}