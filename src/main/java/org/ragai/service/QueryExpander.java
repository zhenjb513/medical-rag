package org.ragai.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class QueryExpander {

    public record Rule(String trigger, List<String> terms) {}

    private static final Set<String> STOPWORDS = Set.of(
            "我", "想", "问问", "请问", "一下", "医生说", "医生", "大夫",
            "怎么办", "咋办", "啥", "什么", "怎么", "为什么", "啊", "呀", "呢", "吧", "吗",
            "那个", "这个", "有点", "稍微", "好像", "可能", "是不是", "有没有",
            "我妈", "我爸", "我爹", "我娘", "我家", "家里人", "家里",
            "查出来", "检查出", "体检出", "体检查",
            "平时", "平常", "日常", "最近", "昨天", "今天", "前天"
    );

    private static volatile List<Rule> RULES = builtInRules();

    private QueryExpander() {}

    private static List<Rule> builtInRules() {
        List<Rule> r = new ArrayList<>();

        r.add(new Rule("坏胆固醇", List.of("低密度脂蛋白胆固醇", "LDL-C", "LDL")));
        r.add(new Rule("好胆固醇", List.of("高密度脂蛋白胆固醇", "HDL-C", "HDL")));
        r.add(new Rule("血脂稠", List.of("高脂血症", "血脂异常", "高胆固醇血症")));
        r.add(new Rule("血脂高", List.of("高脂血症", "高胆固醇血症", "高甘油三酯血症")));
        r.add(new Rule("胆固醇高", List.of("高胆固醇血症", "高脂血症")));
        r.add(new Rule("胆固醇偏高", List.of("高胆固醇血症", "高脂血症")));
        r.add(new Rule("甘油三酯高", List.of("高甘油三酯血症", "高脂血症")));
        r.add(new Rule("甘油三酯偏高", List.of("高甘油三酯血症", "高脂血症")));
        r.add(new Rule("高密度脂蛋白低", List.of("低HDL血症", "HDL-C降低", "血脂异常")));
        r.add(new Rule("低密度脂蛋白高", List.of("高LDL血症", "LDL-C升高", "高脂血症")));
        r.add(new Rule("总胆固醇高", List.of("总胆固醇升高", "TC升高", "高脂血症")));
        r.add(new Rule("载脂蛋白", List.of("载脂蛋白A", "载脂蛋白B", "ApoA", "ApoB")));
        r.add(new Rule("小而密", List.of("小而密低密度脂蛋白", "sdLDL")));
        r.add(new Rule("脂蛋白a", List.of("脂蛋白(a)", "Lp(a)")));

        r.add(new Rule("高血压", List.of("高血压病", "原发性高血压", "血压升高")));
        r.add(new Rule("血压高", List.of("高血压", "高血压病", "血压升高")));
        r.add(new Rule("血压偏高", List.of("高血压", "血压升高")));
        r.add(new Rule("血压低", List.of("低血压", "血压降低")));
        r.add(new Rule("低血压", List.of("低血压症", "血压降低")));
        r.add(new Rule("收缩压", List.of("收缩压", "SBP", "高压")));
        r.add(new Rule("舒张压", List.of("舒张压", "DBP", "低压")));
        r.add(new Rule("高压高", List.of("收缩压升高", "高血压")));
        r.add(new Rule("低压高", List.of("舒张压升高", "高血压")));
        r.add(new Rule("脉压", List.of("脉压差", "脉搏压")));
        r.add(new Rule("白大衣", List.of("白大衣高血压", "诊室高血压")));
        r.add(new Rule("隐匿性高血压", List.of("隐蔽性高血压", " masked hypertension")));
        r.add(new Rule("H型高血压", List.of("H型高血压", "同型半胱氨酸")));
        r.add(new Rule("顽固性高血压", List.of("难治性高血压", "继发性高血压")));

        r.add(new Rule("糖尿病", List.of("糖尿病 mellitus", "DM")));
        r.add(new Rule("血糖高", List.of("高血糖", "糖尿病", "糖耐量异常")));
        r.add(new Rule("血糖偏高", List.of("高血糖", "糖耐量异常")));
        r.add(new Rule("低血糖", List.of("低血糖症", "血糖过低")));
        r.add(new Rule("空腹血糖", List.of("空腹血浆葡萄糖", "FPG")));
        r.add(new Rule("餐后血糖", List.of("餐后血糖", "PPG")));
        r.add(new Rule("糖化血红蛋白", List.of("糖化血红蛋白", "HbA1c", "A1C")));
        r.add(new Rule("胰岛素", List.of("胰岛素", "INS")));
        r.add(new Rule("C肽", List.of("C肽", "C-Peptide")));
        r.add(new Rule("1型糖尿病", List.of("1型糖尿病", "T1DM")));
        r.add(new Rule("2型糖尿病", List.of("2型糖尿病", "T2DM")));
        r.add(new Rule("妊娠糖尿病", List.of("妊娠期糖尿病", "GDM")));
        r.add(new Rule("糖耐量异常", List.of("糖耐量减低", "IGT")));
        r.add(new Rule("空腹血糖受损", List.of("空腹血糖受损", "IFG")));
        r.add(new Rule("糖尿病肾病", List.of("糖尿病肾病", "DN", "糖尿病性肾病")));
        r.add(new Rule("糖尿病视网膜", List.of("糖尿病视网膜病变", "DR")));
        r.add(new Rule("糖尿病周围神经", List.of("糖尿病周围神经病变", "DPN")));
        r.add(new Rule("糖尿病足", List.of("糖尿病足病", "DF")));

        r.add(new Rule("并发症", List.of("慢性并发症", "急性并发症", "微血管并发症", "大血管并发症")));
        r.add(new Rule("心血管", List.of("心血管疾病", "CVD", "动脉粥样硬化")));
        r.add(new Rule("脑血管", List.of("脑血管疾病", "脑卒中", "中风")));
        r.add(new Rule("冠心病", List.of("冠状动脉粥样硬化性心脏病", "CHD")));
        r.add(new Rule("心梗", List.of("急性心肌梗死", "AMI", "心肌梗死")));
        r.add(new Rule("脑梗", List.of("脑梗死", "缺血性脑卒中")));
        r.add(new Rule("中风", List.of("脑卒中", "脑血管意外")));
        r.add(new Rule("房颤", List.of("心房颤动", "AF")));
        r.add(new Rule("心衰", List.of("心力衰竭", "HF")));
        r.add(new Rule("心律失常", List.of("心律失常", "心律不齐")));
        r.add(new Rule("动脉粥样硬化", List.of("动脉粥样硬化", "AS")));
        r.add(new Rule("外周动脉", List.of("外周动脉疾病", "PAD", "下肢动脉")));
        r.add(new Rule("颈动脉", List.of("颈动脉粥样硬化", "颈动脉狭窄")));
        r.add(new Rule("肾动脉", List.of("肾动脉狭窄", "肾血管性高血压")));

        r.add(new Rule("减肥", List.of("减重", "体重管理", "肥胖")));
        r.add(new Rule("肥胖", List.of("肥胖症", "BMI", "体重指数")));
        r.add(new Rule("BMI", List.of("体重指数", "身体质量指数")));
        r.add(new Rule("腰围", List.of("腰围", "中心性肥胖")));
        r.add(new Rule("代谢综合征", List.of("代谢综合征", "MetS", "胰岛素抵抗综合征")));
        r.add(new Rule("胰岛素抵抗", List.of("胰岛素抵抗", "IR")));
        r.add(new Rule("甲状腺", List.of("甲状腺功能", "甲功")));
        r.add(new Rule("甲亢", List.of("甲状腺功能亢进", "甲亢")));
        r.add(new Rule("甲减", List.of("甲状腺功能减退", "甲减")));
        r.add(new Rule("尿酸", List.of("尿酸", "血尿酸")));
        r.add(new Rule("痛风", List.of("痛风性关节炎", "高尿酸血症")));
        r.add(new Rule("脂肪肝", List.of("非酒精性脂肪性肝病", "NAFLD", "脂肪肝")));
        r.add(new Rule("肝功", List.of("肝功能", "ALT", "AST", "转氨酶")));
        r.add(new Rule("肾功", List.of("肾功能", "肌酐", "尿素氮", "eGFR")));
        r.add(new Rule("肌酐", List.of("血肌酐", "Scr", "GFR")));
        r.add(new Rule("蛋白尿", List.of("尿蛋白", "蛋白尿", "UACR")));
        r.add(new Rule("微量白蛋白", List.of("尿微量白蛋白", "UMA", "UACR")));

        r.add(new Rule("他汀", List.of("他汀类药物", "HMG-CoA还原酶抑制剂")));
        r.add(new Rule("阿托伐他汀", List.of("阿托伐他汀", "立普妥")));
        r.add(new Rule("瑞舒伐他汀", List.of("瑞舒伐他汀", "可定")));
        r.add(new Rule("辛伐他汀", List.of("辛伐他汀", "舒降之")));
        r.add(new Rule("普伐他汀", List.of("普伐他汀", "普拉固")));
        r.add(new Rule("氟伐他汀", List.of("氟伐他汀", "来适可")));
        r.add(new Rule("匹伐他汀", List.of("匹伐他汀", "力清之")));
        r.add(new Rule("依折麦布", List.of("依折麦布", "Ezetimibe", "益适纯")));
        r.add(new Rule("PCSK9", List.of("PCSK9抑制剂", "阿利库单抗", "依洛尤单抗")));
        r.add(new Rule("贝特类", List.of("贝特类药物", "苯氧芳酸类")));
        r.add(new Rule("非诺贝特", List.of("非诺贝特", "力平之")));
        r.add(new Rule("烟酸", List.of("烟酸", "维生素B3", "烟酰胺")));
        r.add(new Rule("胆汁酸螯合剂", List.of("胆汁酸螯合剂", "考来烯胺")));

        r.add(new Rule("钙通道阻滞剂", List.of("钙通道阻滞剂", "CCB")));
        r.add(new Rule("地平", List.of("二氢吡啶类", "CCB", "钙通道阻滞剂")));
        r.add(new Rule("氨氯地平", List.of("氨氯地平", "络活喜")));
        r.add(new Rule("硝苯地平", List.of("硝苯地平", "拜新同", "控释片")));
        r.add(new Rule("缬沙坦", List.of("缬沙坦", "代文", "ARB")));
        r.add(new Rule("氯沙坦", List.of("氯沙坦", "科素亚", "ARB")));
        r.add(new Rule("厄贝沙坦", List.of("厄贝沙坦", "安博维", "ARB")));
        r.add(new Rule("替米沙坦", List.of("替米沙坦", "美嘉素", "ARB")));
        r.add(new Rule("坎地沙坦", List.of("坎地沙坦", "必洛斯", "ARB")));
        r.add(new Rule("ACEI", List.of("血管紧张素转换酶抑制剂", "ACEI")));
        r.add(new Rule("普利", List.of("ACEI", "血管紧张素转换酶抑制剂")));
        r.add(new Rule("贝那普利", List.of("贝那普利", "洛汀新", "ACEI")));
        r.add(new Rule("依那普利", List.of("依那普利", "悦宁定", "ACEI")));
        r.add(new Rule("培哚普利", List.of("培哚普利", "雅施达", "ACEI")));
        r.add(new Rule("β受体阻滞剂", List.of("β受体阻滞剂", "BB")));
        r.add(new Rule("美托洛尔", List.of("美托洛尔", "倍他乐克", "β受体阻滞剂")));
        r.add(new Rule("比索洛尔", List.of("比索洛尔", "康忻", "β受体阻滞剂")));
        r.add(new Rule("卡维地洛", List.of("卡维地洛", "达利全", "β受体阻滞剂")));
        r.add(new Rule("利尿剂", List.of("利尿剂", "利尿药")));
        r.add(new Rule("氢氯噻嗪", List.of("氢氯噻嗪", "双氢克尿噻", "利尿剂")));
        r.add(new Rule("缬沙坦氨氯地平", List.of("缬沙坦氨氯地平", "倍博特", "单片复方制剂")));
        r.add(new Rule("缬沙坦氢氯噻嗪", List.of("缬沙坦氢氯噻嗪", "复代文", "单片复方制剂")));
        r.add(new Rule("培哚普利氨氯地平", List.of("培哚普利氨氯地平", "开素达", "单片复方制剂")));

        r.add(new Rule("二甲双胍", List.of("二甲双胍", "格华止", "双胍类")));
        r.add(new Rule("磺脲类", List.of("磺脲类药物", "SU")));
        r.add(new Rule("格列美脲", List.of("格列美脲", "亚莫利", "磺脲类")));
        r.add(new Rule("格列齐特", List.of("格列齐特", "达美康", "磺脲类")));
        r.add(new Rule("格列喹酮", List.of("格列喹酮", "糖适平", "磺脲类")));
        r.add(new Rule("瑞格列奈", List.of("瑞格列奈", "诺和龙", "格列奈类")));
        r.add(new Rule("那格列奈", List.of("那格列奈", "唐力", "格列奈类")));
        r.add(new Rule("α糖苷酶抑制剂", List.of("α-糖苷酶抑制剂")));
        r.add(new Rule("阿卡波糖", List.of("阿卡波糖", "拜唐苹", "α-糖苷酶抑制剂")));
        r.add(new Rule("伏格列波糖", List.of("伏格列波糖", "倍欣", "α-糖苷酶抑制剂")));
        r.add(new Rule("噻唑烷二酮", List.of("噻唑烷二酮类", "TZD", "格列酮类")));
        r.add(new Rule("吡格列酮", List.of("吡格列酮", "艾可拓", "TZD")));
        r.add(new Rule("罗格列酮", List.of("罗格列酮", "文迪雅", "TZD")));
        r.add(new Rule("DPP-4", List.of("DPP-4抑制剂", "二肽基肽酶4抑制剂")));
        r.add(new Rule("西格列汀", List.of("西格列汀", "捷诺维", "DPP-4抑制剂")));
        r.add(new Rule("沙格列汀", List.of("沙格列汀", "安立泽", "DPP-4抑制剂")));
        r.add(new Rule("维格列汀", List.of("维格列汀", "佳维乐", "DPP-4抑制剂")));
        r.add(new Rule("利格列汀", List.of("利格列汀", "欧唐宁", "DPP-4抑制剂")));
        r.add(new Rule("GIP", List.of("GIP受体激动剂", "葡萄糖依赖性促胰岛素多肽")));
        r.add(new Rule("GLP", List.of("GLP-1受体激动剂", "胰高血糖素样肽1")));
        r.add(new Rule("利拉鲁肽", List.of("利拉鲁肽", "诺和力", "GLP-1受体激动剂")));
        r.add(new Rule("艾塞那肽", List.of("艾塞那肽", "百泌达", "GLP-1受体激动剂")));
        r.add(new Rule("度拉糖肽", List.of("度拉糖肽", "Trulicity", "GLP-1受体激动剂")));
        r.add(new Rule("司美格鲁肽", List.of("司美格鲁肽", "Ozempic", "GLP-1受体激动剂")));
        r.add(new Rule("SGLT2", List.of("SGLT2抑制剂", "钠-葡萄糖协同转运蛋白2抑制剂")));
        r.add(new Rule("达格列净", List.of("达格列净", "Forxiga", "SGLT2抑制剂")));
        r.add(new Rule("恩格列净", List.of("恩格列净", "Jardiance", "SGLT2抑制剂")));
        r.add(new Rule("卡格列净", List.of("卡格列净", "Invokana", "SGLT2抑制剂")));

        r.add(new Rule("抗血小板", List.of("抗血小板治疗", "抗血小板药物")));
        r.add(new Rule("阿司匹林", List.of("阿司匹林", "乙酰水杨酸", "抗血小板")));
        r.add(new Rule("氯吡格雷", List.of("氯吡格雷", "波立维", "抗血小板")));
        r.add(new Rule("替格瑞洛", List.of("替格瑞洛", "倍林达", "抗血小板")));
        r.add(new Rule("抗凝", List.of("抗凝治疗", "抗凝药物")));
        r.add(new Rule("华法林", List.of("华法林", "香豆素类", "抗凝")));
        r.add(new Rule("达比加群", List.of("达比加群", "泰毕全", "直接凝血酶抑制剂")));
        r.add(new Rule("利伐沙班", List.of("利伐沙班", "拜瑞妥", "Xa因子抑制剂")));
        r.add(new Rule("阿哌沙班", List.of("阿哌沙班", "艾乐妥", "Xa因子抑制剂")));

        r.add(new Rule("血压目标", List.of("血压控制目标", "降压目标")));
        r.add(new Rule("血脂目标", List.of("血脂控制目标", "调脂目标")));
        r.add(new Rule("血糖目标", List.of("血糖控制目标", "降糖目标")));
        r.add(new Rule("综合干预", List.of("综合治疗", "多重危险因素", "生活方式干预")));
        r.add(new Rule("一级预防", List.of("一级预防", "初始预防")));
        r.add(new Rule("二级预防", List.of("二级预防", "复发预防")));
        r.add(new Rule("三级预防", List.of("三级预防", "康复治疗")));

        r.add(new Rule("他汀不耐受", List.of("他汀不耐受", "他汀治疗失败", "肌痛", "横纹肌溶解")));
        r.add(new Rule("肝酶升高", List.of("肝酶升高", "ALT升高", "转氨酶升高", "他汀肝损害")));
        r.add(new Rule("肌痛", List.of("他汀肌痛", "肌肉疼痛", "横纹肌溶解症")));

        r.add(new Rule("监测", List.of("监测频率", "随访监测", "定期复查")));
        r.add(new Rule("复查", List.of("定期复查", "随访检查", "监测")));
        r.add(new Rule("随访", List.of("随访管理", "复诊计划")));

        r.add(new Rule("WHO", List.of("hypertension", "blood pressure", "target")));
        r.add(new Rule("世界卫生组织", List.of("hypertension", "blood pressure", "target")));
        r.add(new Rule("指南", List.of("临床指南", "专家共识", "防治指南")));
        r.add(new Rule("共识", List.of("专家共识", "诊疗共识")));

        r.add(new Rule("全家", List.of("家族性高胆固醇血症", "FH", "家族史")));
        r.add(new Rule("遗传", List.of("家族性高胆固醇血症", "FH", "遗传因素")));
        r.add(new Rule("家族史", List.of("家族史", "遗传性", "FH")));
        r.add(new Rule("父母", List.of("家族史", "早发心血管疾病", "家族性高胆固醇血症")));

        r.add(new Rule("饮食", List.of("饮食管理", "膳食指南", "饮食控制")));
        r.add(new Rule("运动", List.of("运动干预", "体力活动", "有氧运动")));
        r.add(new Rule("戒烟", List.of("吸烟", "戒烟", "烟草依赖")));
        r.add(new Rule("限酒", List.of("饮酒", "限酒", "戒酒")));
        r.add(new Rule("睡眠", List.of("睡眠障碍", "睡眠质量", "失眠")));
        r.add(new Rule("压力", List.of("心理压力", "精神压力", "情绪管理")));
        r.add(new Rule("心理", List.of("心理健康", "心理干预", "焦虑抑郁")));

        return List.copyOf(r);
    }

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
        }
    }

    public static List<Rule> currentRules() {
        return RULES;
    }

    public static Set<String> stopwords() {
        return STOPWORDS;
    }

    public static String stripStopwords(String query) {
        if (query == null || query.isBlank()) return query;
        String lower = query.toLowerCase(Locale.ROOT);
        String result = query;
        for (String sw : STOPWORDS) {
            if (lower.contains(sw.toLowerCase(Locale.ROOT))) {
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(sw), " ");
            }
        }
        return result.replaceAll("\\s+", " ").trim();
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
        if (extra.isEmpty()) return query;
        return query + " " + String.join(" ", extra);
    }

    public static String expandForBm25(String query) {
        String stripped = stripStopwords(query);
        List<String> extra = addedTerms(stripped);
        if (extra.isEmpty()) return stripped;
        return stripped + " " + String.join(" ", extra);
    }
}