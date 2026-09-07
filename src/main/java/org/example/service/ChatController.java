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
import org.springframework.beans.factory.annotation.Autowired;

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
    private final PhiDesensitizationService phiDesensitizationService;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public ChatController(ChatClient.Builder chatClientBuilder,
                          InventoryService inventoryService,
                          VectorStore vectorStore,
                          ApplicationContext applicationContext,
                          PhiDesensitizationService phiDesensitizationService,
                          @Autowired(required = false) RagInitializer ragInitializer) {
        this.inventoryService = inventoryService;
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.ragInitializer = ragInitializer;
        this.phiDesensitizationService = phiDesensitizationService;

        boolean vectorStoreReady = true;
        if (applicationContext.containsBean("pgVectorSchemaInitializer")) {
            PgVectorSchemaInitializer init = applicationContext.getBean(PgVectorSchemaInitializer.class);
            vectorStoreReady = init.isReady();
        }

        ChatClient.Builder builder = chatClientBuilder;
        if (Objects.nonNull(vectorStore) && vectorStoreReady) {
            builder = builder.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build());
        }
        this.chatClient = builder.build();
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        // PHI脱敏：在进入RAG检索和大模型前，对用户输入进行隐私信息脱敏
        String sanitizedMessage = phiDesensitizationService.desensitize(message);
        //记录脱敏日志[审计用]
        if(phiDesensitizationService.containsPhi(message)){
            System.out.println("[PHI脱敏]监测到敏感信息，已脱敏处理。原始消息：" + message + "，脱敏后消息：" + sanitizedMessage);
        }
        return chatClient.prompt()
                .user(sanitizedMessage)
                .tools(inventoryService)
                .call()
                .content();
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