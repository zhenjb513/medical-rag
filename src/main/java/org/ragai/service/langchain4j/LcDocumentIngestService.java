package org.ragai.service.langchain4j;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j 侧的文档入库服务：PDF 解析 → 分块 → 向量化 → 写入 PgVector。
 *
 * <p>这是本分支（langchain4j-exp）用 LangChain4j 重写 RAG 的第一步，
 * 对应 Spring AI 侧 {@code RagInitializer} 的职责，但 API 完全不同：
 * <ul>
 *   <li>解析：{@link ApachePdfBoxDocumentParser}（Spring AI 侧用 PagePdfDocumentReader）</li>
 *   <li>分块：{@link DocumentSplitters#recursive(int, int)}（Spring AI 侧用 TokenTextSplitter）</li>
 *   <li>入库：{@link EmbeddingStoreIngestor}（Spring AI 侧直接写 PgVectorStore）</li>
 * </ul>
 */
@Service
public class LcDocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(LcDocumentIngestService.class);

    private final PgVectorEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${app.pdf-path:./data/pdfs}")
    private String pdfPath;

    @Value("${app.rag.chunk-size:800}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:120}")
    private int chunkOverlap;

    public LcDocumentIngestService(LcEmbeddingStoreService storeService, EmbeddingModel embeddingModel) {
        this.embeddingStore = storeService.store();
        this.embeddingModel = embeddingModel;
    }

    /**
     * 扫描配置的 PDF 目录并全部入库。
     *
     * @return 入库的文档数
     */
    public int ingestDirectory() {
        File dir = new File(pdfPath);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("PDF 目录不存在或不是目录，跳过入库：{}", pdfPath);
            return 0;
        }
        File[] pdfs = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) {
            log.warn("PDF 目录为空，跳过入库：{}", pdfPath);
            return 0;
        }
        int count = 0;
        for (File pdf : pdfs) {
            try {
                if (ingestFile(pdf)) {
                    count++;
                }
            } catch (Exception e) {
                log.error("入库失败：{}", pdf.getName(), e);
            }
        }
        log.info("LangChain4j 入库完成：{}/{} 个文件", count, pdfs.length);
        return count;
    }

    /**
     * 入库单个 PDF 文件。
     *
     * @return 是否成功
     */
    public boolean ingestFile(File pdf) {
        if (pdf == null || !pdf.exists()) {
            log.warn("文件不存在，跳过：{}", pdf);
            return false;
        }

        DocumentParser parser = new ApachePdfBoxDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(pdf.getAbsolutePath(), parser);

        // 补文件名元数据，便于后续按文件过滤和溯源
        Metadata metadata = document.metadata();
        document = Document.from(document.text(), metadata);

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(chunkSize, chunkOverlap))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(document);
        log.info("已入库：{}（{} 字符）", pdf.getName(), document.text().length());
        return true;
    }

    /**
     * 把一段文本切成块并向量化入库（用于测试或增量写入）。
     */
    public List<String> ingestText(String text, String sourceName) {
        Document document = Document.from(text, Metadata.from("file_name", sourceName));
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(chunkSize, chunkOverlap))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
        ingestor.ingest(document);

        // 重新切一遍拿到块数（ingest 内部已切，这里只为返回 id 数量）
        List<TextSegment> segments = DocumentSplitters.recursive(chunkSize, chunkOverlap).split(document);
        List<String> ids = new ArrayList<>();
        Response<List<dev.langchain4j.data.embedding.Embedding>> response = embeddingModel.embedAll(segments);
        for (String id : embeddingStore.addAll(response.content(), segments)) {
            ids.add(id);
        }
        log.info("文本入库：source={}, chunks={}", sourceName, ids.size());
        return ids;
    }
}
