package org.ragai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 */
class InventoryToolSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolDefinition definition() {
        return callbacks()[0].getToolDefinition();
    }

    private static ToolCallback[] callbacks() {
        return ToolCallbacks.from(new InventoryService(new FakeJdbc(150)));
    }

    @Test
    @DisplayName("对外只挂一个工具，诊断探针不能混进去")
    void exactlyOneToolIsAdvertised() {
        // probeConsumablesForDiag 没标 @Tool，标了就等于让模型去数全表行数
        assertEquals(1, callbacks().length,
                () -> "实际挂出：" + java.util.Arrays.stream(callbacks())
                        .map(c -> c.getToolDefinition().name()).toList());
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
    void schemaRequiresTheNamedParameter() throws Exception {
        JsonNode schema = JSON.readTree(definition().inputSchema());
        assertEquals("object", schema.path("type").asText(), definition().inputSchema());
        assertTrue(schema.path("properties").has("consumableName"), definition().inputSchema());
        JsonNode required = schema.path("required");
        assertTrue(required.isArray() && containsText(required, "consumableName"),
                "required 里没有 consumableName：" + definition().inputSchema());
        assertEquals("string", schema.path("properties").path("consumableName").path("type").asText());
    }

    @Test
    @DisplayName("参数说明要带一个真实名字的例子")
    void parameterDescriptionCarriesAnExample() throws Exception {
        JsonNode param = JSON.readTree(definition().inputSchema())
                .path("properties").path("consumableName");
        String desc = param.path("description").asText();
        assertFalse(desc.isBlank(), "参数没描述，模型只能瞎猜填什么");
        assertTrue(desc.contains("胃镜活检钳"), desc);
    }

    @Test
    @DisplayName("按 schema 给的 JSON 能真的走到方法并拿到回话")
    void schemaFieldNameMapsToTheMethodArgument() {
        // schema 里的键名和方法形参对不上时，模型照说明填了参数、框架那边收到 null——静默且贵
        String result = callbacks()[0].call("{\"consumableName\":\"胃镜活检钳\"}");
        assertTrue(result.contains("胃镜活检钳") && result.contains("150"),
                "回喂给模型的内容：「" + result + "」");
    }

    @Test
    @DisplayName("returnDirect 必须是 false：工具的话要回喂模型，不能直接当答案吐出去")
    void toolResultGoesBackToTheModel() {
        assertFalse(callbacks()[0].getToolMetadata().returnDirect());
    }

    @Test
    @DisplayName("required 只是写给模型看的：模型少给参数时不许蒙出一个库存数")
    void missingOrMisspelledArgumentCannotProduceAStockNumber() {
        // 实测（scratch/tool_schema_dump.out）：schema 标了 required，框架照样让缺参/错键名进来。
        // 挡住它的只能是方法自己——否则 WHERE name = '' 查不到行，会被转成「库里没登记这个耗材」，
        // 明明是模型没填参数，却说成了数据结论。
        FakeJdbc jdbc = new FakeJdbc(150);
        ToolCallback cb = ToolCallbacks.from(new InventoryService(jdbc))[0];
        for (String payload : java.util.List.of("{}", "{\"name\":\"胃镜活检钳\"}", "{\"consumableName\":\"  \"}")) {
            String result = cb.call(payload);
            assertEquals(0, jdbc.queries, payload + " 不该去打这条 SQL");
            assertFalse(result.contains("件"), payload + " 竟然报出了库存件数：" + result);
            assertTrue(result.contains("缺少耗材名称"), payload + " 应当要求补名称：" + result);
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

    /** 只为把工具跑通而存在的假库：不连任何数据源，按脚本返回一个库存数，并数着被问了几次。 */
    private static class FakeJdbc extends JdbcTemplate {
        int queries = 0;
        private final Integer stock;

        FakeJdbc(Integer stock) {
            this.stock = stock;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args)
                throws DataAccessException {
            queries++;
            return requiredType.cast(stock);
        }
    }
}
