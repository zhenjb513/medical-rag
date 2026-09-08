package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.PgVectorSchemaInitializer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final InventoryService inventoryService;
    private final RagInitializer ragInitializer;
    private final ApplicationContext applicationContext;
    private final VectorStore vectorStore;
    private final PhiDesensitizationService phiDesensitizationService;
    private final RagReferenceService ragReferenceService;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public ChatController(ChatClient.Builder chatClientBuilder,
                          InventoryService inventoryService,
                          VectorStore vectorStore,
                          ApplicationContext applicationContext,
                          PhiDesensitizationService phiDesensitizationService,
                          RagReferenceService ragReferenceService,
                          @Autowired(required = false) RagInitializer ragInitializer) {
        this.inventoryService = inventoryService;
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.ragInitializer = ragInitializer;
        this.phiDesensitizationService = phiDesensitizationService;
        this.ragReferenceService = ragReferenceService;


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
        // 1.PHI脱敏：在进入RAG检索和大模型前，对用户输入进行隐私信息脱敏
        String sanitizedMessage = phiDesensitizationService.desensitize(message);
        boolean hasPhi = phiDesensitizationService.containsPhi(message);

        // 2.向量检索（Top 8，加相似度阈值 0.4 过滤低质量 chunk）
        List<Document> docs = ragReferenceService.search(sanitizedMessage, 8, 0.65);
        if (hasPhi) {
            System.out.println("[PHI脱敏] 监测到敏感信息，原始: \"" + message + "\"  脱敏后: \"" + sanitizedMessage + "\"");
        }
        System.out.println("[RAG检索] 问题: \"" + sanitizedMessage + "\"" + (hasPhi ? " (已脱敏)" : ""));
        System.out.println("[RAG检索] 召回文档数量: " + docs.size() + " (Top 8, 相似度阈值 ≥ 0.65)");
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
        String  answer = chatClient.prompt()
                .system(systemPrompt)
                .user(sanitizedMessage)
                .tools(inventoryService)
                .call()
                .content();
        //6.格式化引用来源（添加序号）,附加到答案末尾
        String formattedReferencestext = ragReferenceService.formatReferences(references);

        return answer + formattedReferencestext;
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

    /** 运行时 RAG 诊断接口，返回 VectorStore 类型、PgVector 初始化状态及各 PDF 提取诊断信息。 */
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