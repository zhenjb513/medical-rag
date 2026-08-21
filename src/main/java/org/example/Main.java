package org.example;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class Main {
    public static void main(String[] args) {
        // 中文 Windows 默认 file.encoding = GBK，须在 Spring 启动前强制全局 UTF-8，
        // 否则 Jackson、PgVectorStore、PDFBox 等底层按 GBK 转码导致中文乱码。
        forceUtf8FileEncoding();
        SpringApplication.run(Main.class, args);
    }

    /** 强制 JVM 全局默认编码为 UTF-8，并刷新 JDK 内部 defaultCharset 缓存。 */
    private static void forceUtf8FileEncoding() {
        String[] keys = { "file.encoding", "file.encoding.PROXY", "sun.jnu.encoding", "stdout.encoding", "stderr.encoding" };
        for (String k : keys) {
            try { System.setProperty(k, "UTF-8"); } catch (Throwable ignore) { }
        }
        try {
            java.lang.reflect.Field f = java.nio.charset.Charset.class.getDeclaredField("defaultCharset");
            f.setAccessible(true);
            f.set(null, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignore) { }
    }
}
