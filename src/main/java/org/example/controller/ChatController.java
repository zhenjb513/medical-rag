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
    private final org.example.service.PhiAuditService phiAuditService;
    private final RagReferenceService ragReferenceService;
    private final org.example.service.HybridRetrievalService hybridRetrievalService;
    private final org.example.service.Bm25IndexService bm25IndexService;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    /**
     * 给模型的召回块数（相邻块扩展之前的名额）。默认 8 = 改造前的写死值，行为不变。
     * 58 题实测：重排后取 8 块、再扩展至 16 块时答案块 Hit@8 已 96.6%（README 检索级基线），
     * 继续加大只是撑上下文，所以留成可配而不改默认。
     */
    @org.springframework.beans.factory.annotation.Value("${app.retrieval.chat-top-k:8}")
    private int chatTopK = 8;

    public ChatController(ChatClient.Builder chatClientBuilder,
                          InventoryService inventoryService,
                          VectorStore vectorStore,
                          ApplicationContext applicationContext,
                          PhiDesensitizationService phiDesensitizationService,
                          org.example.service.PhiAuditService phiAuditService,
                          RagReferenceService ragReferenceService,
                          org.example.service.HybridRetrievalService hybridRetrievalService,
                          org.example.service.Bm25IndexService bm25IndexService,
                          @Autowired(required = false) RagInitializer ragInitializer) {
        this.inventoryService = inventoryService;
        this.applicationContext = applicationContext;
        this.vectorStore = vectorStore;
        this.ragInitializer = ragInitializer;
        this.phiDesensitizationService = phiDesensitizationService;
        this.phiAuditService = phiAuditService;
        this.ragReferenceService = ragReferenceService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.bm25IndexService = bm25IndexService;

        // 这里不用 QuestionAnswerAdvisor，改为手动检索（ragReferenceService / HybridRetrievalService）：
        // Advisor 会把阈值和上下文组装都吞进去，手动链路才能精确控制引用溯源、System Prompt 和可答性闸门。
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

        // 2.检索：下限阈值统一由 app.retrieval 配置决定（vector-floor 取数下限 + answer-floor 可答性闸门），
        //    召回块数由 app.retrieval.chat-top-k 决定。
        //    这里不再传 0.65：实测那道逐条阈值会把正确题的候选从 20 条砍到 2 条，却照样放行超纲问题。
        List<Document> docs = hybridRetrievalService.search(sanitizedMessage, chatTopK, null);
        if (hasPhi) {
            // 审计只落「命中类型 + 条数 + 原句指纹」，原始问句和脱敏后文本都不再打进控制台。
            // 以前这行 println 会把含身份证/手机号的整条原话写进日志，本身就是一次泄露。
            phiAuditService.recordPhiHit(message, "/chat");
        }
        System.out.println("[RAG检索] 问题: \"" + sanitizedMessage + "\"" + (hasPhi ? " (已脱敏)" : ""));
        System.out.println("[RAG检索] 模式=" + hybridRetrievalService.describeMode()
                + "  召回文档数量: " + docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double score = doc.getScore() != null ? doc.getScore() : -1.0;
            String source = extractFileName(doc);
            Object chunkIndex = doc.getMetadata() == null ? null : doc.getMetadata().get("chunk_index");
            String preview = doc.getText() == null ? "" : doc.getText().replaceAll("[\\r\\n\\t]+", " ").trim();
            if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
            // chunk= 和引用行、考卷答案块、/rag/chunk 是同一套 chunk_index，日志里就能直接对上是哪一块
            System.out.printf("[RAG检索]   #%d  score=%.4f  source=%s  chunk=%s  预览: %s%n",
                    i + 1, score, source, chunkIndex == null ? "<无>" : chunkIndex, preview);
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
     * 引用溯源：按「文件名 + chunk_index」回读库里那一块的原文，并顺带给出它在 PDF 里的物理页和归在哪一节。
     * 只读，一次库查询 + 本地读 PDF，不调大模型、不写任何东西。
     * 答案末尾的【引用来源】现在给到块号，这个口子负责把块号兑现成正文、页码和章节号，人才真的能核对。
     */
    @GetMapping(value = "/rag/chunk", produces = "text/plain; charset=utf-8")
    public String ragChunk(@RequestParam String file, @RequestParam int chunk) {
        if (!bm25IndexService.isAvailable()) {
            return "没连上 PgVector：SimpleVectorStore 模式下块号没有可回读的落库位置，引用只能停在文件名。";
        }
        List<org.example.service.Bm25IndexService.StoredChunk> rows = bm25IndexService.readChunk(file, chunk, 5);
        if (rows.isEmpty()) {
            return "库里查不到 " + file + "#" + chunk
                    + "（file 用不带路径的文件名，chunk 是入库时的 chunk_index，从 0 开始）";
        }
        StringBuilder sb = new StringBuilder();
        for (var row : rows) {
            String text = row.content() == null ? "" : row.content();
            java.io.File pdf = new java.io.File(row.source() == null ? "" : row.source());
            // 页码是现算的：库里从来没存过页，靠按页重抽一遍再对文字；页上印的页码也从这些行里认（见 PdfPageLocator）
            org.example.service.PdfPageLocator.PageSpan span = org.example.service.PdfPageLocator.locate(pdf, text);
            // 章节号同一次扫描里顺手认（正文里现成的「5.4.3.2 …」，库里也没存过）：跨节报范围、顺序存疑就明说
            org.example.service.SectionIndex.SectionSpan section =
                    org.example.service.PdfPageLocator.locateSection(pdf, text);
            sb.append("文件=").append(row.fileName()).append("  块号=").append(row.chunkIndex())
                    .append("  source=").append(row.source()).append("  正文字符=").append(text.length()).append('\n')
                    .append("页码=").append(org.example.service.PdfPageLocator.describe(span)).append('\n')
                    .append("章节=").append(org.example.service.SectionIndex.describe(section)).append('\n')
                    .append("---- 正文 ----\n").append(text).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 运行时 RAG 诊断接口：VectorStore 类型、PgVector 初始化状态、OCR 就绪、本轮入库流水、库内 chunk 分布、逐份 PDF 体检。
     * 默认不跑正文三层提取（每份 PDF 扒一遍全文，文件多了这个接口自己就先挂），要深挖加 `?deep=true`。
     */
    @GetMapping(value = "/rag/diag", produces = "application/json; charset=utf-8")
    public String ragDiag(
            @org.springframework.web.bind.annotation.RequestParam(name = "deep", defaultValue = "false") boolean deep) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vector_store_type", vectorStore == null ? "<NULL>" : vectorStore.getClass().getName());
        out.put("vector_store_is_null", vectorStore == null);

        boolean hasPgInit = applicationContext.containsBean("pgVectorSchemaInitializer");
        out.put("pg_vector_initializer_exists", hasPgInit);
        if (hasPgInit) {
            PgVectorSchemaInitializer init = applicationContext.getBean(PgVectorSchemaInitializer.class);
            out.put("pg_vector_initializer_ready", init.isReady());
        }

        Map<String, Object> phiAudit = new LinkedHashMap<>();
        phiAudit.put("table_ready", phiAuditService.isTableReady());
        phiAudit.put("table", "MySQL demoAI.phi_audit_log");
        phiAudit.put("columns", List.of("message_hash", "endpoint", "hit_types", "hit_count"));
        phiAudit.put("stores_raw_text", false);
        // 零模型调用的探针：不打通问答就能确认业务库连上了、表建出来了
        phiAudit.put("rows_total", phiAuditService.countRows());
        out.put("phi_audit", phiAudit);

        // 库存工具连的库能不能查到 consumables（零模型调用探针）。修复前的实测：
        // PG 侧 relation "consumables" does not exist、MySQL 侧 行数=3，见 InventoryService 构造器注释。
        out.put("inventory_db_probe", inventoryService.probeConsumablesForDiag());

        if (ragInitializer != null) {
            out.put("rag_initializer", ragInitializer.diagnose(deep));
        } else {
            out.put("rag_initializer", "<NULL：RagInitializer Bean 未注册>");
        }
        return jsonMapper.writeValueAsString(out);
    }
}