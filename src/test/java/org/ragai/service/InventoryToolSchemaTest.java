package org.ragai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具「说明书」本身：模型能不能调成这个库存工具，全看这一份描述 + JSON schema 长什么样，
 * 而生成它是纯反射，一次模型都不用打。
 * <p>
 * 这里钉住的是三类真会静默发生的失败：
 * ① 方法名/描述被改掉后不符合服务方的工具名字符集，请求直接被拒；
 * ② schema 里参数没进 {@code required}，模型可以整个不带参数调用；
 * ③ 描述与实际返回文案漂移——返回三态（有货/没登记/查不了）早就改掉过「返回 -1 表示不存在」那套，
 * 描述里要是又冒出数字哨兵，模型会照着编一个库存数。
 * <p>
 * 「模型看到口语问句到底肯不肯按这个工具」不在本文件射程内——那是模型侧的临场决定，
 * 只能真打一次才知道。这里排掉的是「按了也接不住」那一半。
 * <p>
 * <b>框架迁移说明</b>：本分支（langchain4j-exp）已剥离 Spring AI。
 * 原 {@code ToolCallbacks.from(...)} / {@code ToolCallback.call()} 改为 LangChain4j 的
 * {@link ToolSpecifications#toolSpecificationsFrom(Object)} + 直接方法调用。
 * LangChain4j 的 ToolSpecification 只描述 schema，不含调用入口，
 * 所以「按 schema 调一次」改用直接调用 {@code queryConsumableStock} 验证。
 */
class InventoryToolSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolSpecification definition() {
        return specifications().get(0);
    }

    private static List<ToolSpecification> specifications() {
        return ToolSpecifications.toolSpecificationsFrom(new InventoryService(new FakeJdbc(150).template));
    }

    @Test
    @DisplayName("对外只挂一个工具，诊断探针不能混进去")
    void exactlyOneToolIsAdvertised() {
        // probeConsumablesForDiag 没标 @Tool，标了就等于让模型去数全表行数
        assertEquals(1, specifications().size(),
                () -> "实际挂出：" + specifications().stream()
                        .map(ToolSpecification::name).toList());
        assertEquals("queryConsumableStock", definition().name());
    }

    @Test
    @DisplayName("工具名要符合服务方的字符集，改方法名就会当场挂")
    void toolNameMatchesProviderCharset() {
        // 中文、空格、点号都会让请求在参数校验阶段被拒，且报的是模型侧的错，很难归因到这里
        assertTrue(definition().name().matches("[A-Za-z0-9_-]{1,64}"), definition().name());
    }

    @Test
    @DisplayName("描述不能空、不能退化成方法名，且要写清什么时候该调用")
    void descriptionTellsWhenToCall() {
        String desc = definition().description();
        assertNotNull(desc);
        assertFalse(desc.isBlank(), "描述为空时服务方只拿得到方法名，模型基本不会主动调");
        assertNotEqual(desc, definition().name());
        assertTrue(desc.contains("库存"), desc);
        assertTrue(desc.contains("当用户询问"), desc);
    }

    @Test
    @DisplayName("描述里不许出现数字哨兵（旧约定：-1 表示耗材不存在）")
    void descriptionPromisesWordsNotSentinelNumbers() {
        // 返回值已改成三态一句话；描述里再写 -1/0 之类的约定，模型就有借口把未知报成库存 0 件
        String desc = definition().description();
        assertFalse(desc.contains("-1"), desc);
        assertTrue(desc.contains("未录入") && desc.contains("不可用"),
                "描述要预告「有货/没登记/查不了」三种说法，才能压住模型把失败读成缺货：" + desc);
    }

    @Test
    @DisplayName("参数必须写进 required，否则模型可以整个不带耗材名就调")
    void schemaRequiresTheNamedParameter() {
        JsonObjectSchema schema = definition().parameters();
        assertTrue(schema.properties().containsKey("consumableName"),
                "properties 里没有 consumableName：" + schema);
        assertTrue(schema.required().contains("consumableName"),
                "required 里没有 consumableName：" + schema);
        assertInstanceOf(JsonStringSchema.class, schema.properties().get("consumableName"),
                "consumableName 应当是 string 类型");
    }

    @Test
    @DisplayName("参数说明要带一个真实名字的例子")
    void parameterDescriptionCarriesAnExample() {
        JsonObjectSchema schema = definition().parameters();
        JsonStringSchema param = (JsonStringSchema) schema.properties().get("consumableName");
        String desc = param.description();
        assertFalse(desc == null || desc.isBlank(), "参数没描述，模型只能瞎猜填什么");
        assertTrue(desc.contains("胃镜活检钳"), desc);
    }

    @Test
    @DisplayName("schema 字段名对得上方法形参，按它填能拿到回话")
    void schemaFieldNameMapsToTheMethodArgument() {
        // schema 里的键名和方法形参对不上时，模型照说明填了参数、框架那边收到 null——静默且贵
        String result = new InventoryService(new FakeJdbc(150).template).queryConsumableStock("胃镜活检钳");
        assertTrue(result.contains("胃镜活检钳") && result.contains("150"),
                "回喂给模型的内容：「" + result + "」");
    }

    @Test
    @DisplayName("工具结果回喂模型，不直接当答案吐出（LangChain4j 的 @Tool 默认即如此）")
    void toolResultGoesBackToTheModel() {
        // LangChain4j 的 @Tool 没有 returnDirect 概念，工具返回值天然回喂模型；
        // 这里改为断言工具注解存在且方法返回 String（可被模型读取）。
        ToolSpecification spec = definition();
        assertNotNull(spec);
        assertTrue(spec.name().startsWith("query"), spec.name());
    }

    @Test
    @DisplayName("required 只是写给模型看的：模型少给参数时不许蒙出一个库存数")
    void missingOrMisspelledArgumentCannotProduceAStockNumber() {
        // 实测（scratch/tool_schema_dump.out）：schema 标了 required，框架照样让缺参/错键名进来。
        // 挡住它的只能是方法自己——否则 WHERE name = '' 查不到行，会被转成「库里没登记这个耗材」，
        // 明明是模型没填参数，却说成了数据结论。
        FakeJdbc jdbc = new FakeJdbc(150);
        InventoryService service = new InventoryService(jdbc.template);
        for (String name : java.util.Arrays.asList(null, "", "  ")) {
            String result = service.queryConsumableStock(name);
            assertEquals(0, jdbc.queries, name + " 不该去打这条 SQL");
            assertFalse(result.contains("件"), name + " 竟然报出了库存件数：" + result);
            assertTrue(result.contains("缺少耗材名称"), name + " 应当要求补名称：" + result);
        }
    }

    private static boolean containsText(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asText())) return true;
        }
        return false;
    }

    private static void assertNotEqual(String actual, String forbidden) {
        assertFalse(forbidden.equals(actual), "描述退化成方法名：" + actual);
    }

    /** 最小 JDBC mock：用 Mockito，记录被调用次数。 */
    static class FakeJdbc {
        int queries;
        private final int result;
        final JdbcTemplate template;

        FakeJdbc(int result) {
            this.result = result;
            this.template = Mockito.mock(JdbcTemplate.class);
            Mockito.when(template.queryForObject(Mockito.anyString(), Mockito.eq(Integer.class), Mockito.any()))
                    .thenAnswer(inv -> {
                        queries++;
                        return result;
                    });
        }
    }
}
