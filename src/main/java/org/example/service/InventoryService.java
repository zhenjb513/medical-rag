package org.example.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private static final String SQL = "SELECT stock FROM consumables WHERE name = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 连的是 **MySQL 业务库**（{@code businessJdbcTemplate}）。
     * <p>
     * 这行限定符是修 bug 修出来的，不是历史选择。改之前它按类型注入，而容器里当时只有
     * {@code PgVectorConfig} 注册的那一个 JdbcTemplate（Boot 的 {@code JdbcTemplateAutoConfiguration}
     * 是 {@code @ConditionalOnMissingBean}，被它顶掉退避了），于是「查 consumables」实际打到 PostgreSQL。
     * 实测证据（当时临时挂过两个对照探针，现已收成一个 {@code /rag/diag} → {@code inventory_db_probe}）：
     * PG 侧 {@code ERROR: relation "consumables" does not exist}，MySQL 侧 {@code 行数=3}。
     * 也就是说这个工具从来没查到过库存；旧代码还把异常吞成 {@code return 0}，
     * 模型因此把每一次问库存都答成「缺货，建议联系采购」。
     * <p>
     * 现在容器里有两个 JdbcTemplate 且都没标 @Primary，按类型注入会 {@code NoUniqueBeanDefinitionException}
     * （应用直接起不来），所以两个注入点都必须写死限定符。
     */
    public InventoryService(@Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 通过 @Tool 注解暴露给大模型：查询指定医疗耗材的当前库存。
     * <p>
     * 返回的是**带语义的一句话**，不是裸数字。原来返回 int 且任何异常都 {@code return 0}，
     * 结果「耗材没登记」「数据库挂了」「库存真的是 0」三件事在模型眼里长得一样，
     * 模型据此答「这个耗材没货了，建议联系采购」。更要命的是 {@code LangChain4jChatController}
     * 的提示词教模型「返回 -1 表示耗材不存在」，而服务里根本没有 -1 这条路（两处已一起改成文字三态）。
     */
    @Tool(description = "查询指定医疗耗材的当前库存数量。当用户询问某个耗材还有多少库存时使用此工具。"
            + "返回值是一句话：有库存会写数量，没登记会说明未录入，查询失败会说明系统不可用。")
    public String queryConsumableStock(
            @ToolParam(description = "耗材的名称，例如：胃镜活检钳") String consumableName) {

        String name = consumableName == null ? "" : consumableName.trim();
        try {
            return stockFound(name, jdbcTemplate.queryForObject(SQL, Integer.class, name));
        } catch (EmptyResultDataAccessException e) {
            return notRegistered(name);
        } catch (DataAccessException e) {
            System.out.println("[库存工具] 查询失败，按「系统不可用」回答而不是库存 0："
                    + e.getClass().getSimpleName() + ": " + rootMessage(e));
            return unavailable(name);
        }
    }

    /** 查到了行：库存值本身可能是 NULL（表里没填），那也算「数据缺失」而不是 0 件。 */
    static String stockFound(String name, Integer stock) {
        if (stock == null) return notRegistered(name);
        return "「" + name + "」当前库存 " + stock + " 件";
    }

    static String notRegistered(String name) {
        return "耗材库中没有登记「" + name + "」，这不是缺货，不要建议联系采购补货";
    }

    static String unavailable(String name) {
        return "库存系统暂时不可用，查不到「" + name + "」的库存。请回答稍后再试，不要说成缺货或库存为 0";
    }

    /**
     * 给 {@code /rag/diag} 用：数一下**这个服务实际连的库**里 {@code consumables} 有多少行，
     * 零模型调用就能确认工具链路是否真的接在业务库上（正常应返回 {@code 行数=3}）。
     * 出错时返回文本而不是抛异常：诊断接口不能因为一次查询失败就 500。
     */
    public String probeConsumablesForDiag() {
        try {
            Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM consumables", Integer.class);
            return "行数=" + n;
        } catch (DataAccessException e) {
            // 带上 root cause：Spring 的 getMessage() 只到 "bad SQL grammar"，
            // 到底是 MySQL 的 Table doesn't exist 还是 PG 的 relation does not exist，全靠这句驱动原文分辨
            return "查询失败: " + e.getClass().getSimpleName() + ": " + rootMessage(e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage();
    }
}
