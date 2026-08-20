package org.example.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private final JdbcTemplate jdbcTemplate;

    // 注入 Spring 自带的 JdbcTemplate，方便查库
    public InventoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 核心：使用 @Tool 注解暴露给大模型
    @Tool(description = "查询指定医疗耗材的当前库存数量。当用户询问某个耗材还有多少库存时使用此工具。")
    public int queryConsumableStock(
            @ToolParam(description = "耗材的名称，例如：胃镜活检钳") String consumableName) {

        // 编写安全的查询逻辑
        String sql = "SELECT stock FROM consumables WHERE name = ?";
        try {
            Integer stock = jdbcTemplate.queryForObject(sql, Integer.class, consumableName);
            return stock != null ? stock : 0;
        } catch (Exception e) {
            return 0; // 查不到就返回0，防止大模型报错
        }
    }
}