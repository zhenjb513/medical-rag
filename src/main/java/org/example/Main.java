package org.example;

import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class Main {
    public static void main(String[] args) {
        // =====================================================================
        // ★★★ 中文 Windows 下必杀：SpringApplication.run() 之前锁死 4 项 UTF-8 系统属性 ★★★
        //   很多框架/库（Jackson, PgVectorStore 内部 JSON 序列化, PDFBox 文本 I/O）
        //   底层会调 Charset.defaultCharset() / String.getBytes() 无参版本，
        //   中文 Windows 默认 file.encoding = GBK → 就算 JDBC 和 PG 全 UTF-8 中间还是会错。
        //   这里必须在 main 第 1 行（任何 Spring Bean 初始化之前）就设好。
        // =====================================================================
        forceUtf8FileEncoding();
        SpringApplication.run(Main.class, args);
    }

    private static void forceUtf8FileEncoding() {
        String[] keys = { "file.encoding", "file.encoding.PROXY", "sun.jnu.encoding", "stdout.encoding", "stderr.encoding" };
        for (String k : keys) {
            try { System.setProperty(k, "UTF-8"); } catch (Throwable ignore) { }
        }
        // 再尝试反射改掉 JDK 内部 defaultCharset 的缓存（JDK 8/11/17 都能生效）
        try {
            java.lang.reflect.Field f = java.nio.charset.Charset.class.getDeclaredField("defaultCharset");
            f.setAccessible(true);
            f.set(null, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignore) { }
        System.out.println();
        System.out.println("======================================================================");
        System.out.println("[启动前置] 已强制 JVM 全局默认编码 = UTF-8");
        System.out.println("          file.encoding   = " + System.getProperty("file.encoding"));
        System.out.println("          sun.jnu.encoding= " + System.getProperty("sun.jnu.encoding"));
        System.out.println("          defaultCharset  = " + java.nio.charset.Charset.defaultCharset());
        System.out.println("======================================================================");
        System.out.println();
    }
}