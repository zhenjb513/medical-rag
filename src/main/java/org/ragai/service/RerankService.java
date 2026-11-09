package org.ragai.service;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 重排序（gte-rerank-v2）。
 * <p>
 * 向量分和 BM25 分都是「提问和块各自编码完再比」，块里到底有没有回答这个问题，
 * 它们其实看不准；重排是把提问和候选块拼成一对一起送进模型，所以能分辨
 * 「这段讲血压」和「这段给了血压目标值」的区别——这正是首位命中率的瓶颈。
 * <p>
 * 依赖 spring-ai-alibaba 自动配置出来的 RerankModel bean（未配 api-key 时拿不到），
 * 拿不到或调用失败就原样返回候选顺序，重排只是锦上添花，不能让它把检索搞挂。
 */
@Service
public class RerankService {

    private final ObjectProvider<RerankModel> rerankModels;

    /** gte-rerank（不带 v2）已下线，自动配置的默认值还是它，必须显式覆盖 */
    @Value("${app.retrieval.rerank-model:gte-rerank-v2}")
    private String model = "gte-rerank-v2";

    public RerankService(ObjectProvider<RerankModel> rerankModels) {
        this.rerankModels = rerankModels;
    }

    public boolean isAvailable() {
        return rerankModels.getIfAvailable() != null;
    }

    public String getModelName() {
        return model;
    }

    /** 返回按重排分降序的候选；分数写进 Document，打印的和用于排序的是同一个数。 */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        RerankModel rerankModel = rerankModels.getIfAvailable();
        if (rerankModel == null) {
            System.out.println("[重排] 没有可用的 RerankModel（检查 spring.ai.dashscope.api-key），保持原顺序");
            return candidates;
        }
        List<Document> ranked;
        try {
            RerankRequest request = new RerankRequest(query, candidates,
                    DashScopeRerankOptions.builder()
                            .model(model)
                            .topN(candidates.size())
                            .returnDocuments(false)
                            .build());
            RerankResponse response = rerankModel.call(request);
            ranked = toDocuments(response);
        } catch (RuntimeException e) {
            System.out.println("[重排] 调用失败，保持原顺序：" + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            return candidates;
        }
        if (ranked.size() < candidates.size()) {
            // topN 若被服务端截断，没返回的候选补在末尾，不能让重排把召回池变小
            List<Document> returned = new ArrayList<>(ranked);
            for (Document d : candidates) {
                if (!containsId(returned, d.getId())) returned.add(d);
            }
            ranked = returned;
        }
        return ranked;
    }

    private List<Document> toDocuments(RerankResponse response) {
        List<DocumentWithScore> results = response == null ? null : response.getResults();
        List<Document> out = new ArrayList<>();
        if (results == null) return out;
        for (DocumentWithScore r : results) {
            Document d = r.getOutput();
            if (d != null) out.add(d.mutate().score(r.getScore()).build());
        }
        return out;
    }

    private static boolean containsId(List<Document> docs, String id) {
        for (Document d : docs) {
            if (id == null ? d.getId() == null : id.equals(d.getId())) return true;
        }
        return false;
    }
}
