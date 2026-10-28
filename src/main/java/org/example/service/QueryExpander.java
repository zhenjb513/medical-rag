package org.example.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class QueryExpander {

    public record Rule(String trigger, List<String> terms) {}

    private static volatile List<Rule> RULES = List.of(
            new Rule("坏胆固醇", List.of("低密度脂蛋白胆固醇", "LDL-C")),
            new Rule("血糖", List.of("糖尿病")),
            new Rule("全家", List.of("家族性高胆固醇血症", "FH")),
            new Rule("遗传", List.of("家族性高胆固醇血症", "FH")),
            new Rule("WHO", List.of("hypertension", "blood pressure", "target")),
            new Rule("世界卫生组织", List.of("hypertension", "blood pressure", "target")));

    private QueryExpander() {}

    public static synchronized void configure(List<Map<String, Object>> config) {
        if (config == null || config.isEmpty()) return;
        List<Rule> parsed = new ArrayList<>();
        for (Map<String, Object> entry : config) {
            Object trigger = entry.get("trigger");
            Object terms = entry.get("terms");
            if (trigger == null || terms == null) continue;
            List<String> termList = new ArrayList<>();
            if (terms instanceof List<?> l) {
                for (Object t : l) if (t != null) termList.add(String.valueOf(t));
            } else if (terms instanceof String s) {
                termList.add(s);
            }
            if (!termList.isEmpty()) {
                parsed.add(new Rule(String.valueOf(trigger), termList));
            }
        }
        if (!parsed.isEmpty()) {
            RULES = List.copyOf(parsed);
            System.out.println("[QueryExpander] 已加载 " + parsed.size() + " 条可配置规则");
        }
    }

    public static List<Rule> currentRules() {
        return RULES;
    }

    public static List<String> addedTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase(Locale.ROOT);
        Set<String> extra = new LinkedHashSet<>();
        for (Rule r : RULES) {
            if (lower.contains(r.trigger().toLowerCase(Locale.ROOT))) extra.addAll(r.terms());
        }
        extra.removeIf(term -> lower.contains(term.toLowerCase(Locale.ROOT)));
        return new ArrayList<>(extra);
    }

    public static String expand(String query) {
        List<String> extra = addedTerms(query);
        return extra.isEmpty() ? query : query + " " + String.join(" ", extra);
    }
}