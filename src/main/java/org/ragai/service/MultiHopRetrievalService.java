package org.ragai.service;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MultiHopRetrievalService {

    private final HybridRetrievalService hybridRetrievalService;

    @Value("${app.retrieval.multi-hop.enabled:true}")
    private boolean multiHopEnabled = true;

    @Value("${app.retrieval.multi-hop.min-sources-to-trigger:2}")
    private int minSourcesToTrigger = 2;

    @Value("${app.retrieval.multi-hop.hop-decay:0.7}")
    private double hopDecay = 0.7;

    @Value("${app.retrieval.multi-hop.max-hops:2}")
    private int maxHops = 2;

    private static final Pattern MULTI_ENTITY_AND = Pattern.compile(
            "(和|与|及|同时|既要|既要.*?又要|分别|各.*?多少|既.*?又.*?还有|both|and)",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> MEDICAL_ENTITIES = List.of(
            "血压", "收缩压", "舒张压", "血脂", "LDL", "HDL", "胆固醇", "甘油三酯",
            "血糖", "HbA1c", "糖化血红蛋白", "BMI", "体重指数", "腰围", "心率",
            "他汀", "降压", "降糖", "抗凝", "抗血小板", "双联抗血小板",
            "高血压", "糖尿病", "高血脂", "冠心病", "心梗", "卒中", "房颤",
            "一级预防", "二级预防", "治疗目标", "控制目标", "诊断标准",
            "饮食", "运动", "生活方式", "食盐", "脂肪", "酒精", "吸烟"
    );

    public MultiHopRetrievalService(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    public List<Document> search(String query, int topK, Double threshold) {
        if (!multiHopEnabled) {
            return hybridRetrievalService.search(query, topK, threshold);
        }

        System.out.println("[多跳检索] 原始 query: " + query);

        int poolK = Math.max(topK * 3, 12);
        List<Document> primary = hybridRetrievalService.search(query, poolK, threshold);

        if (primary.isEmpty()) {
            System.out.println("[多跳检索] 主检索为空，直接返回");
            return primary;
        }

        Set<String> primarySources = distinctSources(primary);
        System.out.println("[多跳检索] 主检索 topK 来源分布: " + sourceDistribution(primary));

        List<String> subQueries = null;
        boolean userHasAnd = MULTI_ENTITY_AND.matcher(query).find();
        boolean crossSource = primarySources.size() >= minSourcesToTrigger;

        if (userHasAnd || crossSource) {
            subQueries = generateSubQueries(query, primary, userHasAnd);
            System.out.println("[多跳检索] 触发多跳 (userAnd=" + userHasAnd
                    + ", distinctSources=" + primarySources.size() + ") → 子查询: " + subQueries);
        } else {
            System.out.println("[多跳检索] 单文档即可回答，跳过多跳");
        }

        if (subQueries == null || subQueries.isEmpty()) {
            return take(primary, topK);
        }

        record ScoredDoc(Document doc, double effectiveScore) {}
        List<ScoredDoc> mergedWithScore = new ArrayList<>();
        Set<String> seenHashes = contentHashesOf(primary);
        for (Document d : primary) {
            mergedWithScore.add(new ScoredDoc(d, d.getScore() != null ? d.getScore() : 0.0));
        }

        int hopCount = 0;
        for (String sub : subQueries) {
            if (hopCount >= maxHops) break;
            if (sub.equals(query)) continue;

            List<Document> hopResult = hybridRetrievalService.search(sub, topK, threshold);
            System.out.println("[多跳检索] 第 " + (hopCount + 1) + " 跳 query=[" + sub + "] 命中 " + hopResult.size() + " 条");

            double decay = Math.pow(hopDecay, hopCount + 1);
            for (Document d : hopResult) {
                String hash = contentHashOf(d);
                if (!seenHashes.contains(hash)) {
                    double rawScore = d.getScore() != null ? d.getScore() : 0.0;
                    mergedWithScore.add(new ScoredDoc(d, rawScore * decay));
                    seenHashes.add(hash);
                }
            }
            hopCount++;
        }

        mergedWithScore.sort((a, b) -> Double.compare(b.effectiveScore(), a.effectiveScore()));

        List<Document> finalDocs = mergedWithScore.stream()
                .map(ScoredDoc::doc)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("[多跳检索] 合并去重后 " + finalDocs.size() + " 条，取 top " + topK);
        return take(finalDocs, topK);
    }

    private List<String> generateSubQueries(String originalQuery, List<Document> primary, boolean userHasAnd) {
        List<String> subQueries = new ArrayList<>();

        if (userHasAnd) {
            List<String> parts = splitEntityClauses(originalQuery);
            if (parts.size() >= 2) {
                subQueries.addAll(parts);
            }
        }

        Set<String> sourceThemes = sourceThemesOf(primary);
        for (String theme : sourceThemes) {
            String sq = theme + " " + extractCommonContext(originalQuery);
            if (!sq.trim().equals(originalQuery)) {
                subQueries.add(sq.trim());
            }
        }

        Set<String> deduped = new LinkedHashSet<>();
        for (String sq : subQueries) {
            if (sq.length() >= 4 && sq.length() <= originalQuery.length() * 3) {
                deduped.add(sq);
            }
        }
        return new ArrayList<>(deduped);
    }

    private List<String> splitEntityClauses(String query) {
        List<String> result = new ArrayList<>();
        Matcher m = MULTI_ENTITY_AND.matcher(query);
        if (!m.find()) {
            result.add(query);
            return result;
        }

        String[] segments = query.split("(和|与|及|同时|既要.*?又要|分别|各.*?多少|既.*?又.*?还有|both|and)");
        String context = extractCommonContext(query);
        for (String seg : segments) {
            String trimmed = seg.trim();
            if (trimmed.length() >= 2) {
                if (!context.isEmpty() && !trimmed.contains(context)) {
                    result.add(context + " " + trimmed);
                } else {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private String extractCommonContext(String query) {
        String context = query;
        Matcher m = MULTI_ENTITY_AND.matcher(query);
        while (m.find()) {
            String before = context.substring(0, m.start()).trim();
            String after = context.substring(m.end()).trim();
            if (!before.isEmpty() && !after.isEmpty()) {
                context = before + " " + after;
            }
        }
        for (String sep : new String[]{"各要控制在多少", "各是多少", "分别是多少", "分别多少"}) {
            context = context.replace(sep, "");
        }
        for (String sep : new String[]{"的", "了", "呢", "吗", "？", "?"}) {
            context = context.replace(sep, " ");
        }
        String[] parts = context.trim().split("\\s+");
        List<String> meaningful = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2 && MEDICAL_ENTITIES.stream().noneMatch(p::equals)) {
                meaningful.add(p);
            }
        }
        return String.join(" ", meaningful).trim();
    }

    private Set<String> sourceThemesOf(List<Document> docs) {
        Set<String> themes = new LinkedHashSet<>();
        for (Document d : docs) {
            Object source = d.getMetadata() == null ? null : d.getMetadata().get("source");
            Object fileName = d.getMetadata() == null ? null : d.getMetadata().get("file_name");
            if (source != null) themes.add(source.toString());
            else if (fileName != null) themes.add(fileName.toString());
        }
        return themes;
    }

    private Set<String> distinctSources(List<Document> docs) {
        Set<String> sources = new LinkedHashSet<>();
        for (Document d : docs) {
            Object s = d.getMetadata() == null ? null : d.getMetadata().get("source");
            if (s != null) sources.add(s.toString());
        }
        return sources;
    }

    private String sourceDistribution(List<Document> docs) {
        Map<String, Long> counts = docs.stream()
                .collect(Collectors.groupingBy(
                        d -> {
                            Object s = d.getMetadata() == null ? null : d.getMetadata().get("source");
                            return s != null ? s.toString() : "?";
                        },
                        Collectors.counting()
                ));
        return counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private Set<String> contentHashesOf(List<Document> docs) {
        Set<String> hashes = new HashSet<>();
        for (Document d : docs) hashes.add(contentHashOf(d));
        return hashes;
    }

    private String contentHashOf(Document d) {
        if (d.getMetadata() != null && d.getMetadata().get("content_hash") != null) {
            return d.getMetadata().get("content_hash").toString();
        }
        return d.getId() != null ? d.getId() : d.getText();
    }

    private List<Document> take(List<Document> docs, int k) {
        if (docs.size() <= k) return docs;
        return new ArrayList<>(docs.subList(0, k));
    }
}