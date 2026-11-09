package org.ragai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.ragai.config.PgVectorSchemaInitializer;
import org.ragai.service.Bm25IndexService;
import org.ragai.service.InventoryService;
import org.ragai.service.PdfPageLocator;
import org.ragai.service.PhiAuditService;
import org.ragai.service.RagInitializer;
import org.ragai.service.SectionIndex;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用溯源与运行时诊断两个只读口子：一次库查询 + 本地读 PDF，不调任何模型、不写任何东西。
 */
@RestController
public class RagController {

    private final Bm25IndexService bm25IndexService;
    private final PhiAuditService phiAuditService;
    private final InventoryService inventoryService;
    private final RagInitializer ragInitializer;
    private final VectorStore vectorStore;
    private final ApplicationContext applicationContext;
    private final ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public RagController(Bm25IndexService bm25IndexService,
                         PhiAuditService phiAuditService,
                         InventoryService inventoryService,
                         RagInitializer ragInitializer,
                         VectorStore vectorStore,
                         ApplicationContext applicationContext) {
        this.bm25IndexService = bm25IndexService;
        this.phiAuditService = phiAuditService;
        this.inventoryService = inventoryService;
        this.ragInitializer = ragInitializer;
        this.vectorStore = vectorStore;
        this.applicationContext = applicationContext;
    }

    /**
     * 按「文件名 + chunk_index」回读库里那一块的原文，并顺带给出它在 PDF 里的物理页、页上印的页码和归在哪一节。
     * 答案末尾的【引用来源】给到块号，这个口子负责把块号兑现成正文、页码和章节号。
     */
    @GetMapping(value = "/rag/chunk", produces = "text/plain; charset=utf-8")
    public String ragChunk(@RequestParam String file, @RequestParam int chunk) {
        if (!bm25IndexService.isAvailable()) {
            return "没连上 PgVector：SimpleVectorStore 模式下块号没有可回读的落库位置，引用只能停在文件名。";
        }
        List<Bm25IndexService.StoredChunk> rows = bm25IndexService.readChunk(file, chunk, 5);
        if (rows.isEmpty()) {
            return "库里查不到 " + file + "#" + chunk
                    + "（file 用不带路径的文件名，chunk 是入库时的 chunk_index，从 0 开始）";
        }
        StringBuilder sb = new StringBuilder();
        for (Bm25IndexService.StoredChunk row : rows) {
            String text = row.content() == null ? "" : row.content();
            File pdf = new File(row.source() == null ? "" : row.source());
            // 页码和章节号都是现算的：库里从来没存过，靠按页重抽一遍再对文字（见 PdfPageLocator / SectionIndex）
            PdfPageLocator.PageSpan span = PdfPageLocator.locate(pdf, text);
            SectionIndex.SectionSpan section = PdfPageLocator.locateSection(pdf, text);
            sb.append("文件=").append(row.fileName()).append("  块号=").append(row.chunkIndex())
                    .append("  source=").append(row.source()).append("  正文字符=").append(text.length()).append('\n')
                    .append("页码=").append(PdfPageLocator.describe(span)).append('\n')
                    .append("章节=").append(SectionIndex.describe(section)).append('\n')
                    .append("---- 正文 ----\n").append(text).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 运行时体检：VectorStore 类型、PgVector 初始化状态、PHI 审计表、库存库连通、BM25 倒排对账、本轮入库流水。
     * 默认不跑正文三层提取（每份 PDF 扒一遍全文，文件多了这个接口自己就先挂），要深挖加 {@code ?deep=true}。
     */
    @GetMapping(value = "/rag/diag", produces = "application/json; charset=utf-8")
    public String ragDiag(@RequestParam(name = "deep", defaultValue = "false") boolean deep) throws Exception {
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
        phiAudit.put("rows_total", phiAuditService.countRows());
        out.put("phi_audit", phiAudit);

        // 库存工具连的库能不能查到 consumables（零模型调用探针），见 InventoryService 构造器注释
        out.put("inventory_db_probe", inventoryService.probeConsumablesForDiag());

        // BM25 倒排的全库对账计数。按份增量刷新（refreshSource）里刻意不统计这些，总数就靠这个手动口子
        out.put("bm25_index", bm25IndexService.diagnose());

        out.put("rag_initializer", ragInitializer.diagnose(deep));
        return jsonMapper.writeValueAsString(out);
    }
}
