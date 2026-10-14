package org.example.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 口语措辞 → 指南术语的查询扩展，只作用于召回通道的查询串，不改写送给重排/生成的原问句。
 * <p>
 * 为什么需要：患者口吻的词在正文里压根不存在，词汇通道没有可匹配的词，语义通道也没把它们顶进前 8。
 * /bm25/locate 逐字证（scratch/b1_loc.out，501 块新库）：
 * 「坏胆固醇」命中块数=0、「血糖偏高」=0、「全家」=0，而「家族性高胆固醇血症」命中 13 块
 * （其中 中国血脂管理指南2023年.pdf#107 逐字含「成人FH患者LDL-C目标值：不伴ASCVD应<2.6…亚临床<1.8…临床ASCVD应<1.4」，
 * 正是考卷给 FH 题标的答案块）。
 * <p>
 * 术语本身也要选对：「糖代谢异常」全库 0 命中，补进去等于没补，所以血糖那条只补「糖尿病」。
 */
public final class QueryExpander {

    /** 触发词命中就问句里补术语；术语已经在问句里出现则不重复补。 */
    private record Rule(String trigger, List<String> terms) {}

    private static final List<Rule> RULES = List.of(
            new Rule("坏胆固醇", List.of("低密度脂蛋白胆固醇", "LDL-C")),
            new Rule("血糖", List.of("糖尿病")),
            new Rule("全家", List.of("家族性高胆固醇血症", "FH")),
            new Rule("遗传", List.of("家族性高胆固醇血症", "FH")));

    private QueryExpander() {}

    public static List<String> addedTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> extra = new LinkedHashSet<>();
        for (Rule r : RULES) {
            if (query.contains(r.trigger())) extra.addAll(r.terms());
        }
        extra.removeIf(term -> query.contains(term) || query.contains(term.toLowerCase()));
        return new ArrayList<>(extra);
    }

    /** 没有术语可补时原样返回，避免给查询串平白加一个空格。 */
    public static String expand(String query) {
        List<String> extra = addedTerms(query);
        return extra.isEmpty() ? query : query + " " + String.join(" ", extra);
    }
}
