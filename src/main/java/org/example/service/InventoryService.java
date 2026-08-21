package org.example.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final JdbcTemplate jdbcTemplate;

    public InventoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 通过 @Tool 注解暴露给大模型：查询指定医疗耗材的当前库存数量。 */
    @Tool(description = "查询指定医疗耗材的当前库存数量。当用户询问某个耗材还有多少库存时使用此工具。")
    public int queryConsumableStock(
            @ToolParam(description = "耗材的名称，例如：胃镜活检钳") String consumableName) {

        String sql = "SELECT stock FROM consumables WHERE name = ?";
        try {
            Integer stock = jdbcTemplate.queryForObject(sql, Integer.class, consumableName);
            return stock != null ? stock : 0;
        } catch (Exception e) {
            return 0; // 查询不到返回 0，避免大模型调用报错
        }
    }
}
