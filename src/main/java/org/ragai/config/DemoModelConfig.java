package org.ragai.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.ragai.service.RagReferenceService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * demo 档（{@code --spring.profiles.active=demo}）：不配 API key、不联网也能把整条链路点开看。
 * <p>
 * 只替掉两处外部调用——向量化和生成。检索、可答性闸门、RRF 融合、邻居扩展、引用溯源（块号 /
 * 两套页码 / 章节号）、PHI 脱敏与审计、判分器全部还是真实代码，所以这一档能演示的是**链路形状**，
 * 不是答案质量。
 * <p>
 * ⚠️ 分数不可和 98.4% 基线混看：假向量是字符 2-gram 哈希，余弦量的是字面重叠，和 text-embedding-v3
 * 的语义余弦不在一个尺度上，所以 {@code application-demo.yml} 里的 vector-floor / answer-floor 是
 * 按假向量的实测分布另定的两个数，跟生产那两条 0.45 / 0.60 没有可比性。
 */
@Profile("demo")
@Configuration
public class DemoModelConfig {

    public DemoModelConfig() {
        System.out.println("[demo 档] 向量化=本地字符 2-gram 哈希  生成=上下文抽取  全程不打百炼；"
                + "这一档的检索分数与 98.4% 基线不可比（阈值是按假向量另定的）");
    }

    @Bean
    @Primary
    public EmbeddingModel demoEmbeddingModel(@Value("${app.pgvector.dimensions:1024}") int dimensions) {
        return new HashingEmbeddingModel(dimensions);
    }

    @Bean
    @Primary
    public ChatModel demoChatModel() {
        return new ExtractiveChatModel();
    }

    /**
     * 字符 2-gram 哈希向量。选 2-gram 而不是分词：不依赖词典、中英文数字都能落，
     * 而且和 BM25 那条腿（HanLP 分词）互相独立，两路不会因为同一个分词器一起瞎。
     * String.hashCode 的算法是 JLS 定死的，所以同一个串在任何 JVM 上算出同一个向量，
     * 假档下重跑入库不会让已有向量作废。
     */
    static final class HashingEmbeddingModel implements EmbeddingModel {

        private final int dim;

        HashingEmbeddingModel(int dim) {
            this.dim = dim;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            List<Embedding> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new Embedding(vectorize(texts.get(i), dim), i));
            }
            return new EmbeddingResponse(vectors);
        }

        @Override
        public float[] embed(Document document) {
            return vectorize(document.getText(), dim);
        }

        @Override
        public float[] embed(String text) {
            return vectorize(text, dim);
        }

        @Override
        public int dimensions() {
            return dim;
        }
    }

    /**
     * 抽取式假生成：把提示词里那批【文档N】块切开，逐句和提问算字面重叠，挑最像的三句按原文顺序拼回去。
     * 上下文为空（闸门没过）或一句都对不上时，逐字返回 {@link RagReferenceService#REFUSAL_PHRASE}，
     * 好让「超纲题该拒答」这条在 demo 档也看得见。
     * <p>
     * 排序只看字面，所以仍会挑进章节标题行和表头：24 题探针实测 15 道库内题里 10 道答到点上、
     * 45 句挑出的答案里 10 句是标题/表头/示例页脚这类噪声（跑法见 scratch/demo_e2e.py）。
     * 这是这一档的已知粗糙处，不是链路坏了：它的用途是让人看到
     * 「上下文 → 答案 → 引用行」怎么对上，而不是给人读的答案质量。
     */
    static final class ExtractiveChatModel implements ChatModel {

        private static final String CONTEXT_HEAD = "【知识库上下文】";
        private static final String RULES_HEAD = "【回答规则】";
        private static final int MAX_SENTENCES = 3;

        @Override
        public ChatResponse call(Prompt prompt) {
            String system = textOf(prompt.getSystemMessage());
            String question = textOf(prompt.getUserMessage());
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage(answer(question, system)))));
        }

        String answer(String question, String systemPrompt) {
            String context = between(systemPrompt, CONTEXT_HEAD, RULES_HEAD);
            Set<String> qGrams = grams(question);
            if (context.isBlank() || qGrams.isEmpty()) return RagReferenceService.REFUSAL_PHRASE;

            List<String> sentences = splitSentences(context);
            // gram 在本次候选句里的文档频率：出现在一半以上句子中的 gram 权重算 0（BM25 的 idf 式子，
            // 只是语料换成「这次送进模型的这批句子」）。压的就是「血压」「目标」这种满篇都有的字面。
            Map<String, Integer> df = new HashMap<>();
            List<Set<String>> all = new ArrayList<>(sentences.size());
            for (String s : sentences) {
                Set<String> gs = grams(s);
                all.add(gs);
                for (String g : gs) df.merge(g, 1, Integer::sum);
            }
            int n = sentences.size();
            record Scored(int order, String sentence, double score) { }
            List<Scored> ranked = new ArrayList<>();
            for (int order = 0; order < sentences.size(); order++) {
                Set<String> grams = all.get(order);
                int hit = 0;
                double score = 0;
                for (String g : grams) {
                    if (qGrams.contains(g)) {
                        hit++;
                        score += Math.max(0, Math.log((n - df.get(g) + 0.5) / (df.get(g) + 0.5)));
                    }
                }
                // 2026-10-22 四档对照（同一 jar 换 -D 重启，24 题探针，判据见 scratch/demo_e2e.py）：
                // 命中题数 / 挑进答案的噪声句数 —— idf 总量 10/15、10/45 最好；idf÷√句长 和 idf²÷句长
                // 各 9/15、15 句；原先的「命中数²÷句长」8/15、18 句。三档在 scratch/variants/*.out，
                // 原公式那档的答案在 scratch/_c_answers.out，那几个数是用 scratch/rescore_answers.py
                // 按现行判据从存档答案重算的（同一把尺子，不调服务）。
                // 分母一带句长，短句「二、降压目标值」就总排在带数值的长句前面；按总量排，
                // 「65~79 岁老年人 低于 140/90 mmHg…」这种长句才排得进去。
                if (hit > 0) {
                    ranked.add(new Scored(order, sentences.get(order), score));
                }
            }
            if (ranked.isEmpty()) return RagReferenceService.REFUSAL_PHRASE;

            List<Scored> picked = new ArrayList<>(ranked.stream()
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .limit(MAX_SENTENCES).toList());
            picked.sort(Comparator.comparingInt(Scored::order));
            StringBuilder sb = new StringBuilder("根据知识库内容：");
            for (Scored s : picked) sb.append(s.sentence()).append("。");
            return sb + "\n\n（demo 档：上面这几句是从本次送入模型的上下文里按字面重叠挑出来的原文，没有调用大模型）";
        }

        private static String textOf(Message message) {
            return message == null || message.getText() == null ? "" : message.getText();
        }
    }

    static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        if (a < 0) return "";
        a += from.length();
        int b = text.indexOf(to, a);
        return (b < 0 ? text.substring(a) : text.substring(a, b)).trim();
    }

    /** 去掉【文档N】标记，再按中文句读切；短于 6 字的片段（页码、表头之类）不当答案候选。 */
    static List<String> splitSentences(String context) {
        String body = context.replaceAll("【文档\\d+】", " ");
        List<String> out = new ArrayList<>();
        for (String part : body.split("[。！？；\\n\\r]")) {
            String s = part.replaceAll("\\s+", " ").trim();
            if (s.length() >= 6) out.add(s);
        }
        return out;
    }

    /** 归一化后取字符 2-gram（末尾单字补一个一元组，免得短句整体被吞掉）。 */
    static Set<String> grams(String text) {
        Set<String> out = new HashSet<>();
        String norm = normalize(text);
        for (int i = 0; i < norm.length(); i++) {
            out.add(i + 1 < norm.length() ? norm.substring(i, i + 2) : norm.substring(i, i + 1));
        }
        return out;
    }

    static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    static float[] vectorize(String text, int dim) {
        float[] v = new float[dim];
        String norm = normalize(text);
        for (int i = 0; i < norm.length(); i++) {
            String gram = i + 1 < norm.length() ? norm.substring(i, i + 2) : norm.substring(i, i + 1);
            v[Math.floorMod(gram.hashCode(), dim)] += 1f;
        }
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm2 = Math.sqrt(sum);
        if (norm2 > 0) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm2);
        }
        return v;
    }
}
