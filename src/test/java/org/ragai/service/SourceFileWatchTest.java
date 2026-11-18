package org.ragai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热监听删档分发的守门。2026-11-18 小验证实测抓到：watchLoop 原来对所有事件共用
 * {@code isSupportedFile}，而它要求 {@code isFile()} 为真——ENTRY_DELETE 到达时文件已不存在，
 * 于是每一条删档事件都被静默丢掉，{@code handleSourceFileDelete} 从来没真跑过。
 * 修复后 DELETE 分支只认文件名后缀（{@code isSupportedFileName}），这里钉住两条判据的分工。
 * 分发接线本身由那次小验证的运行时日志钉住（删档 → 清理 vector_store 块=2 bm25_docs 行=2）。
 */
class SourceFileWatchTest {

    @Test
    @DisplayName("后缀白名单只认名字：pdf/md/markdown/docx/xlsx/xls 收，txt/无后缀/null 拒，大小写不敏感")
    void supportedFileNamePredicate() {
        for (String ok : new String[]{"指南.pdf", "手册.md", "a.markdown", "b.docx", "c.xlsx", "d.xls", "E.PDF", "F.MD"}) {
            assertTrue(RagInitializer.isSupportedFileName(ok), ok + " 应当被支持");
        }
        for (String no : new String[]{"a.txt", "noext", "", null}) {
            assertFalse(RagInitializer.isSupportedFileName(no), no + " 不该被支持");
        }
    }

    @Test
    @DisplayName("删档判据与在盘判据必须分开：文件不存在时 isSupportedFile 拒、isSupportedFileName 仍收")
    void deletePredicateWorksWhenFileIsGone() throws Exception {
        Path gone = Path.of("target", "definitely-absent-" + System.nanoTime(), "高血压防治手册2024.md");
        File f = gone.toFile();
        assertFalse(f.exists(), "构造前提：这个路径必须不存在");
        assertFalse(isSupportedFile(f), "在盘判据对已删除文件必须为 false（CREATE/MODIFY 用它挡空路径）");
        assertTrue(RagInitializer.isSupportedFileName(f.getName()),
                "删档判据必须只看名字——否则 ENTRY_DELETE 全被丢掉，删档清理永远不跑");
    }

    private static boolean isSupportedFile(File f) throws Exception {
        var m = RagInitializer.class.getDeclaredMethod("isSupportedFile", File.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, f);
    }
}