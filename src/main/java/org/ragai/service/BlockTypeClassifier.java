package org.ragai.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 块类型打标：text / table / figure。纯函数，只看块正文的行形状，不调模型、不读库。
 * <p>
 * 背景：PDF 抽取把表格和流程图压平成文字行（如「推荐\n类别\nⅠ\nⅠ\nⅡa」或竖排的
 * 「建 \n议下转基 \n层」），这种块里的行↔列对应关系在抽取那一刻就已经丢了
 * （见「推荐类别/证据等级不可做」裁定）。打标不改块正文、不改检索排序，
 * 只是把「这块是压平表格/图，逐行语义不可靠」这个事实写进 metadata，供引用展示与后续
 * 检索侧策略使用。
 * <p>
 * 纪律与章节号溯源同口径：<b>认不准就标 text</b>。判据全部是行形状统计：
 * <ul>
 *   <li>figure：≥6 行且 ≥60% 的行是 ≤6 个非空白字符的短行（竖排流程图压平的形状）</li>
 *   <li>table：独立推荐类别单元格（Ⅰ/Ⅱa/Ⅲ…）≥3 个；或短行占比 ≥40% 且句末标点稀疏；
 *       或纯数字单元格占一半以上；或 ≥6 行、整块零句末标点且平均行长 ≤20
 *       （正文块平均 40~80 字一个句号，512 字符的正文块几乎不可能一个都没有）</li>
 *   <li>其余一律 text（包括表格脚注这种混排块——它主体是完整句子，标 table 反而是误伤）</li>
 * </ul>
 * 句末标点：中文 。！？； 全算；英文 .!? 只认「后面是空白或行尾且前面不是数字」的，
 * 避免把 3.01 / 1.2 g 里的小数点当句号（与 SentenceFirstSplitter 同一取舍）。
 * 阈值由离线全量打标核对校准（scratch/blocktype_rehearsal.out）。
 */
public final class BlockTypeClassifier {

    public static final String TEXT = "text";
    public static final String TABLE = "table";
    public static final String FIGURE = "figure";

    private BlockTypeClassifier() {
    }

    public static String classify(String text) {
        if (text == null || text.isBlank()) return TEXT;

        List<Integer> lineLens = new ArrayList<>();
        int romanCells = 0;
        int numericCells = 0;
        int nonBlank = 0;
        int sentenceEnds = 0;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int nb = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (!Character.isWhitespace(ch)) nb++;
                if (ch == '。' || ch == '！' || ch == '？' || ch == '；') {
                    sentenceEnds++;
                } else if ((ch == '.' || ch == '!' || ch == '?')
                        && (i + 1 >= line.length() || Character.isWhitespace(line.charAt(i + 1)))
                        && (i == 0 || !Character.isDigit(line.charAt(i - 1)))) {
                    sentenceEnds++;
                }
            }
            lineLens.add(nb);
            nonBlank += nb;
            if (line.matches("[ⅠⅡⅢⅣⅤⅰⅱⅲⅳⅴ]+[abAB]?")) romanCells++;
            else if (line.matches("[0-9.,%±<>≥≤~/()（）\\-–— ]+")) numericCells++;
        }

        int lines = lineLens.size();
        // 行太少时形状统计没有意义，宁可不标
        if (lines < 4 || nonBlank == 0) return TEXT;

        int shortLines = 0;
        for (int n : lineLens) {
            if (n <= 6) shortLines++;
        }
        double shortRatio = (double) shortLines / lines;
        double avgLen = (double) nonBlank / lines;
        double endsPer100 = sentenceEnds * 100.0 / nonBlank;

        // 推荐类别/证据等级被压成独立单元格（必须先于 figure 判：这种表也全是短行）
        if (romanCells >= 3) return TABLE;

        // 数字单元格占一半以上（检验值表、剂量表；同样先于 figure 判）
        if (lines >= 5 && numericCells * 2 >= lines && endsPer100 < 1.0) return TABLE;

        // 竖排流程图：几乎全是短行，且不是上面两种单元格表
        if (lines >= 6 && shortRatio >= 0.6) return FIGURE;

        // 表格行：短行多且几乎没有句末标点
        if (lines >= 5 && shortRatio >= 0.4 && endsPer100 < 1.0) return TABLE;

        // 联合用药表这类：行不短但整块零句末、行长均匀偏短
        if (lines >= 6 && sentenceEnds == 0 && avgLen <= 20) return TABLE;

        return TEXT;
    }
}