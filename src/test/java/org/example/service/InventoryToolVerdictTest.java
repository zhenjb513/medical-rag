package org.example.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存工具返回值的三态（有货 / 没登记 / 查不了）必须互相分得开。
 * <p>
 * 原来这个方法返回 int 且 {@code catch (Exception) return 0}，「没登记」和「数据库挂了」都会
 * 被模型读成「库存 0 件 → 缺货，建议联系采购」。这里钉住：**只有真有库存数才允许出现「件」**。
 */
class InventoryToolVerdictTest {

    private static final String NAME = "胃镜活检钳";

    @Test
    @DisplayName("查到数量：正常报数，0 也算一个真实库存值")
    void realStockIsReported() {
        assertEquals("「胃镜活检钳」当前库存 12 件", InventoryService.stockFound(NAME, 12));
        assertEquals("「胃镜活检钳」当前库存 0 件", InventoryService.stockFound(NAME, 0));
    }

    @Test
    @DisplayName("行存在但 stock 列是 NULL = 数据缺失，不等于库存 0")
    void nullColumnIsNotZeroStock() {
        assertEquals(InventoryService.notRegistered(NAME), InventoryService.stockFound(NAME, null));
        assertFalse(InventoryService.stockFound(NAME, null).contains("件"),
                "没填库存却报「N 件」是把未知当已知");
    }

    @Test
    @DisplayName("没登记这个耗材：明说未录入，并且不许被读成缺货")
    void notRegisteredSaysSo() {
        String v = InventoryService.notRegistered(NAME);
        assertTrue(v.contains("没有登记") && v.contains(NAME), v);
        assertTrue(v.contains("不是缺货"), v);
        assertFalse(v.contains("件"), v);
    }

    @Test
    @DisplayName("查询失败：说系统不可用，并且不许被读成缺货")
    void failureSaysUnavailable() {
        String v = InventoryService.unavailable(NAME);
        assertTrue(v.contains("不可用") && v.contains(NAME), v);
        assertTrue(v.contains("不要说成缺货"), v);
        assertFalse(v.contains("件"), v);
    }

    @Test
    @DisplayName("三态两两不同文，模型不可能把两种未知混成一种")
    void threeStatesAreDistinct() {
        String has = InventoryService.stockFound(NAME, 5);
        String none = InventoryService.notRegistered(NAME);
        String down = InventoryService.unavailable(NAME);
        assertFalse(has.equals(none) || has.equals(down) || none.equals(down),
                () -> "三态撞车了：\n  " + has + "\n  " + none + "\n  " + down);
    }
}
