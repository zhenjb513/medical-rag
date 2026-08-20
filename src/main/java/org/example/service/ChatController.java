package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.PgVectorSchemaInitializer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final InventoryService inventoryService;
    private final RagInitializer ragInitializer;
    private final ApplicationContext applicationContext;
    private final VectorStore vectorStore;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public ChatController(ChatClient.Builder chatClientBuilder,
                          InventoryService inventoryService,
                          VectorStore vectorStore,
                          ApplicationContext applicationContext,
                          @Autowired(required = false) RagInitializer ragInitializer) {
        this.inventoryService = inventoryService;
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.ragInitializer = ragInitializer;

        boolean vectorStoreReady = true;
        if (applicationContext.containsBean("pgVectorSchemaInitializer")) {
            PgVectorSchemaInitializer init = applicationContext.getBean(PgVectorSchemaInitializer.class);
            vectorStoreReady = init.isReady();
        }

        ChatClient.Builder builder = chatClientBuilder;
        if (Objects.nonNull(vectorStore) && vectorStoreReady) {
            builder = builder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build());
            System.out.println("[ChatController] ✅ RAG 已启用：已挂载 QuestionAnswerAdvisor");
        } else {
            System.out.println("[ChatController] ℹ️  RAG 未挂载（@Tool 查库存仍可用）");
        }
        this.chatClient = builder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .tools(inventoryService)
                .call()
                .content();
    }

    /**
     * 运行时 RAG 诊断接口：打开 http://localhost:8080/rag/diag 复制结果贴出来即可 1 秒定位。
     *
     *   包括：
     *     1) 当前配置的 pdf-path 是否正确、扫到多少 PDF
     *     2) VectorStore 是 PgVectorStore（持久化）还是 SimpleVectorStore（内存）还是 NULL
     *     3) PgVectorSchemaInitializer 是否 ready（扩展/表是否建好）
     *     4) 每份 PDF 的 3 层提取诊断（P1/P2/P3 非空字符数、streams 统计、首个文字流 dump、最终判断结论）
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
            out.put("rag_initializer", "<NULL：RagInitializer Bean 没被 Spring 扫描到>");
        }
        return jsonMapper.writeValueAsString(out);
    }
}