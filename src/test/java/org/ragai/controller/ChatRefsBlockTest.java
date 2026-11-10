package org.ragai.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用页脚的空列表分支守门。
 * <p>
 * 起因是 2026-11-09 那次库存问答：检索闸门判定指南答不了，docs/refs 全空，
 * 代码却无条件拼「【引用来源】」，答案末尾挂了个光杆标题（scratch/t10_answer2.txt）。
 * 这里钉住三件事：① 空列表不拼标题；② 非空时页脚形状逐字不变（前端 splitAnswer 靠 indexOf('【引用来源】') 切）；
 * ③ 流式空列表连 refs 事件都不发——前端收到该事件就画框，发个空的等于换个地方漏。
 * 纯静态方法，不起 Spring 上下文、不连库、不打模型。
 */
class ChatRefsBlockTest {

    @Test
    @DisplayName("引用列表为空：不拼光杆标题")
    void emptyRefsProduceNoFooter() {
        assertEquals("", ChatController.buildRefsBlock(List.of()));
        assertEquals("", ChatController.buildRefsBlock(null));
    }

    @Test
    @DisplayName("答案拼上空页脚后不含「【引用来源】」，前端不会画空框")
    void answerWithEmptyRefsHasNoFooterMarker() {
        String answer = "胃镜活检钳当前库存为150件。";
        String finalText = answer + ChatController.buildRefsBlock(List.of());
        assertEquals(answer, finalText);
        assertFalse(finalText.contains("【引用来源】"));
    }

    @Test
    @DisplayName("有引用时页脚形状逐字不变：前端靠这个标记切正文和引用")
    void nonEmptyRefsKeepExactFooterShape() {
        List<String> refs = List.of("中国血脂管理指南2023年.pdf（块号 40、41）", "02-WHO成人高血压药物治疗指南2021.pdf（块号 7）");
        String block = ChatController.buildRefsBlock(refs);
        assertEquals("\n\n【引用来源】\n" + String.join("\n", refs), block);
        assertTrue(block.startsWith("\n\n【引用来源】\n"));
    }

    @Test
    @DisplayName("流式空页脚：只发 done，不发 refs 事件")
    void streamSkipsRefsEventWhenEmpty() {
        List<ServerSentEvent<String>> events = ChatController.refsAndDone("").collectList().block();
        assertEquals(1, events.size());
        assertEquals("done", events.get(0).event());
        assertEquals("[DONE]", events.get(0).data());
    }

    @Test
    @DisplayName("流式有引用：先 refs 后 done，data 逐字等于页脚")
    void streamEmitsRefsThenDone() {
        String block = ChatController.buildRefsBlock(List.of("中国血脂管理指南2023年.pdf（块号 40）"));
        List<ServerSentEvent<String>> events = ChatController.refsAndDone(block).collectList().block();
        assertEquals(2, events.size());
        assertEquals("refs", events.get(0).event());
        assertEquals(block, events.get(0).data());
        assertEquals("done", events.get(1).event());
        assertEquals("[DONE]", events.get(1).data());
    }
}