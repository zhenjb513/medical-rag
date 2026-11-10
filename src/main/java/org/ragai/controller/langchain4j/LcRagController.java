package org.ragai.controller.langchain4j;

import org.ragai.service.langchain4j.LcDocumentIngestService;
import org.ragai.service.langchain4j.LcRagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangChain4j 侧的 RAG 接口（/lc/rag/*）。
 *
 * <p>与 Spring AI 侧的 /chat/* 路径区分，便于同题对照。
 * 这是本分支（langchain4j-exp）用 LangChain4j 重写 RAG 的对外入口。
 */
@RestController
@RequestMapping("/lc/rag")
public class LcRagController {

    private final LcRagService ragService;
    private final LcDocumentIngestService ingestService;

    public LcRagController(LcRagService ragService, LcDocumentIngestService ingestService) {
        this.ragService = ragService;
        this.ingestService = ingestService;
    }

    /**
     * RAG 问答。
     */
    @GetMapping(value = "/ask", produces = "application/json; charset=utf-8")
    public Map<String, Object> ask(@RequestParam String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            LcRagService.RagAnswer answer = ragService.ask(q);
            result.put("answer", answer.answer());
            result.put("references", answer.references());
            result.put("selectedChunks", answer.selectedCount());
            result.put("elapsedMs", answer.elapsedMs());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 扫描配置的 PDF 目录并全部入库（LangChain4j 链路）。
     */
    @PostMapping(value = "/ingest", produces = "application/json; charset=utf-8")
    public Map<String, Object> ingest() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int count = ingestService.ingestDirectory();
            result.put("ingested", count);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 健康检查：确认 LangChain4j 检索链路已就绪。
     */
    @GetMapping(value = "/health", produces = "application/json; charset=utf-8")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework", "langchain4j");
        result.put("status", "ok");
        return result;
    }
}
