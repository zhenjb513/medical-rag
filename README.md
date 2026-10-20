<style>
body { font-size: 17px; line-height: 1.8; }
h1 { font-size: 2.2em; }
h2 { font-size: 1.7em; }
h3 { font-size: 1.35em; }
h4 { font-size: 1.15em; }
table, pre code, code { font-size: 1.1em; }
blockquote { font-size: 1.05em; }
</style>

# 🏥 mkbAI · 医疗行业 RAG 智能助手

> **一句话介绍**：一个基于 Spring AI 的医疗行业 RAG 智能助手，支持文档检索 + 数据库查询双链路。
>
> 基于 **Spring Boot 3.5 + Spring AI 1.1 + 阿里云百炼 DashScope + PgVector + Tesseract OCR** 的完整 RAG（检索增强生成）医疗知识库 Demo。
>
> 原生支持 **纯图片扫描 PDF** 的 OCR 文字识别、**多层级 PDF 文本提取策略**、**PHI 隐私脱敏**、**手动引用溯源**、**精准相似度阈值过滤**、**增量入库去重**、**向量维度自动修复**、**目录热监听（WatchService）**、**库存查询 Tool Call** 等生产级特性。

---

# ✨ 特性总览

| 模块 | 核心能力 |
|---|---|
| 🤖 **大模型对话** | 阿里云百炼 `qwen-plus`，通过 `ChatClient` API 流式调用 |
| 🔌 **Function Call（工具调用）** | 基于 `@Tool` 注解，AI 可实时查询耗材库存（MySQL `consumables` 表）。工具返回**带语义的一句话**，把「有货 N 件」「库里没登记这个耗材」「库存系统连不上」三态分开——以前返回裸 int 且异常一律 0，模型会把后两种都答成「缺货，建议联系采购」。另外这轮才发现该工具**一直连的是 PostgreSQL**（`@ConditionalOnMissingBean` 让 MySQL 侧的 JdbcTemplate 从未生成，PG 里没有 `consumables` 表，实测 `relation "consumables" does not exist`），所以它每次查询都在报错、被旧代码吞成 0；现已显式注入 `businessJdbcTemplate`，`/rag/diag` 的 `inventory_db_probe` 返回 `行数=3` 为准 |
| 📚 **RAG 检索增强（手动模式）** | 弃用 QuestionAnswerAdvisor，改为手动控制检索、引用溯源、System Prompt（医疗合规约束） |
| 🎯 **可答性闸门** | 向量通道下限 `vector-floor: 0.45` 只做粗筛；能不能答由 `answer-floor: 0.60` 判**向量第一名**的余弦，不够就整批不给上下文。逐条 0.65 那套已废弃，原因见「检索闸门」一节。闸门要的是余弦，所以纯关键词 `mode=bm25` 不作为生产模式（自动降级成 `hybrid`，评测口子照旧） |
| 🔐 **PHI 隐私脱敏 + 审计落库** | 身份证 / 手机号 / 病历号 / 出生日期 / 银行卡号 正则脱敏，合规必备。命中后只往 MySQL `phi_audit_log` 写「原文指纹 + 接口 + 命中类型:条数」，**原文和脱敏后文本都不落库、不打日志**——以前 `[PHI脱敏] 原始: …` 那行 println 会把整条含身份证的原话写进控制台，本身就是一次泄露 |
| 📄 **PDF 文本提取（4 级兜底）** | P1 标准提取 → P2 参数调整 → P3 暴力流扫 → P4 Tesseract OCR，纯图片扫描件也能识别 |
| 🧪 **内容质量 QC + 乱码救回** | 检测 U+FFFD(�) / CJK 占比 / 控制字符，乱码时自动启动 OCR 重扫救回 |
| 🧭 **增量入库去重** | 查 `vector_store` 表判断是否已入库成功，重启不重复 OCR / Embedding |
| 👁️ **WatchService 目录热监听** | 运行中往 `app.pdf-path` 丢新 PDF 自动入库，无需重启；Windows 复制文件触发的 CREATE + N 次 MODIFY 用 `waitFileStable` 去重 |
| 💾 **PgVector 持久化向量库** | PostgreSQL + pgvector 扩展，HNSW 索引 + COSINE 距离 |
| 🔬 **向量维度自动修复** | 检测表结构维度是否匹配 Embedding 模型（v3=1024，v2=1536），不匹配自动重建 |
| 🧩 **SimpleVectorStore 备用方案** | 条件互斥开关（`app.pgvector.enabled: false`），内存向量库开箱即用，开发测试友好 |
| 🌏 **中文端到端无乱码** | JVM / JDBC / PG 客户端 / Tesseract 输出全链路强制 UTF-8 + 字节级自检 |
| ⏱️ **动态超时控制** | PDF 整体超时按页数估算（MAX(2min, 60s + 15s×页数)），子进程 `destroyForcibly()` 强杀，单个 PDF 失败不影响整体 |
| 🩺 **运行时诊断 API** | `/rag/diag` 实时查看 OCR 就绪状态、向量库、本轮入库流水（扫描/判重跳过/新灌/失败/chunks）、库内 chunk 分布与逐份 PDF 指纹、`phi_audit`（审计表是否就绪 + 列清单 + `stores_raw_text:false` 明示不落原文 + `rows_total`）、`inventory_db_probe`（库存工具连的库里 `consumables` 行数）；这些探针都不打模型。默认不重扒正文，深挖加 `?deep=true` |
| 📑 **答案引用溯源（到块级 + 到页 + 到节）** | 答案末尾的【引用来源】不再只有文件名，还会列出本次**实际送入模型的那几块的 `chunk_index`**，形如 `中国血脂管理指南2023年.pdf（块号 3、4、12）`；再配一个只读口子 `GET /rag/chunk?file=文件名&chunk=块号` 把那一块原文、**它在 PDF 里的物理页、那一页角上印的页码、以及它归在哪一节**（`章节=5.4.3.2 血压测量`）读出来人工核对（一次查库 + 本地按页重抽，不打模型）。metadata 里查不到块号时写「本批块未记录块号」，对不上页码时写「定位不到」，印码认不出时只给物理页并说明原因，章节认不出/顺序存疑时明说「章节号不给」，四头都不编造 |
| 🧪 **医疗测试集自动评估** | 61 题测试集（58 道库内可答 + 3 道超纲负样本）覆盖事实查询/综合分析/口语查询/幻觉抑制/合规测试，4 维度量化评估（准确性/引用/幻觉/合规），输出JSON报告+纯文本摘要 |

---

# 🛠 技术栈

| 技术 | 用途 |
|---|---|
| Spring Boot 3.5 | 后端框架 |
| Spring AI 1.1 | AI 集成框架 |
| 阿里云百炼 (Qwen) | 大语言模型（Chat + Embedding） |
| PostgreSQL + pgvector | 向量数据库（持久化） |
| MySQL 8.0 | 业务数据库（耗材库存） |
| Tesseract OCR | 扫描件文字识别 |
| PDFBox 3.1.x | PDF 原生文本提取 |

---

# 🚀 快速开始

## 1️⃣ 环境要求

| 软件 | 最低版本 | 下载地址 |
|---|---|---|
| **JDK** | 21 LTS | https://adoptium.net/ |
| **Maven** | 3.9+ | （Spring Boot 父 POM 已绑定） |
| **MySQL** | 8.0+ | 业务库（耗材库存 `consumables` 表） |
| **PostgreSQL** | 17 + pgvector 扩展 | 向量库（vector 列类型） |
| **Tesseract-OCR** | 5.3.3+（chi_sim 语言包） | https://github.com/UB-Mannheim/tesseract/wiki |

---

## 2️⃣ 数据库初始化

### 🟦 PostgreSQL + pgvector（向量库）

```sql
-- 1. 启用 pgvector 扩展（每个数据库只执行一次）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 验证扩展是否安装成功
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
```

> ℹ️ **pgvector 安装提示（Windows）**：
> 从 https://github.com/pgvector/pgvector/releases 下载对应 PG 17 版本的 `pgvector.dll`，放到 `PostgreSQL/17/lib/` 目录，再执行上面的 `CREATE EXTENSION`。

### 🟨 MySQL（业务库 · 耗材库存 + PHI 审计）

```sql
CREATE DATABASE IF NOT EXISTS demoAI
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE demoAI;
CREATE TABLE IF NOT EXISTS consumables (
  id    BIGINT AUTO_INCREMENT PRIMARY KEY,
  name  VARCHAR(128) NOT NULL UNIQUE,
  stock INT          NOT NULL DEFAULT 0
);
INSERT INTO consumables(name, stock) VALUES
  ('胃镜活检钳', 128),
  ('一次性输液器', 560),
  ('医用外科口罩', 20000),
  ('一次性注射器(5ml)', 3200);
```

> **`phi_audit_log` 不用手动建**：应用启动时 `PhiAuditService.ensureSchema()` 会执行下面这段
> `CREATE TABLE IF NOT EXISTS`，日志里能看到 `[PHI审计] phi_audit_log 就绪`；建表失败只打警告，不影响对话。
> 这里贴出等价 DDL 只是为了让 DBA 提前知道应用会动哪张表：

```sql
-- 由应用自动创建，含 PHI 命中流水，权限按需收紧
CREATE TABLE IF NOT EXISTS phi_audit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
  message_hash CHAR(12),                    -- 原文 SHA-256 前 12 位，只能判「是不是同一句」，还原不了内容
  endpoint     VARCHAR(32),                 -- 哪个接口命中的，例如 /chat
  hit_types    VARCHAR(128),                -- 「身份证:1,银行卡:1」这类 类型:条数 串
  hit_count    INT,                         -- 命中总条数
  KEY idx_phi_audit_created (created_at)
) DEFAULT CHARSET=utf8mb4;
```

> ⚠️ **两个数据源、两个 JdbcTemplate，必须点名注入。**
> `PgVectorConfig` 早先自己注册了 `pgVectorJdbcTemplate`，而 Spring Boot 的
> `JdbcTemplateAutoConfiguration` 是 `@ConditionalOnMissingBean` —— 于是 `spring.datasource` 配的 MySQL
> **从来没有生成过连接池**，任何按类型注入 `JdbcTemplate` 的服务都静默连到了 PG（耗材库存工具就是这么坏了很久）。
> 现在 `MysqlBusinessConfig` 显式补了 `businessDataSource` / `businessJdbcTemplate`，两边都不标 `@Primary`，
> 所以按类型注入会 `NoUniqueBeanDefinitionException` 直接起不来：新写服务要用哪个库，构造器里就写
> `@Qualifier("businessJdbcTemplate")`（MySQL 业务）或 `@Qualifier("pgVectorJdbcTemplate")`（向量库）。
> 启动日志里两个池各自的名字是 `MysqlBusinessPool` / `PgVectorHikariPool`，一眼能确认连对没连对。

---

## 3️⃣ 安装 Tesseract OCR（扫描件 PDF 识别）

1. 下载：https://github.com/UB-Mannheim/tesseract/wiki （安装包名类似 `tesseract-ocr-w64-setup-5.x.x.exe`）
2. 安装时 **Additional language data → 勾选 Chinese - Simplified**（`chi_sim.traineddata`）
3. （推荐）把 **tessdata 文件夹**复制到 `D:/tessdata/`，避免 Program Files 权限问题
4. 验证命令行是否能运行：
   ```powershell
   & "D:/Program Files/Tesseract-OCR/tesseract.exe" --list-langs
   # 期望输出包含：chi_sim / eng
   ```

---

## 4️⃣ 配置文件说明

项目采用 **Spring Profile 机制**管理配置：

| 文件 | 用途 | 是否入库 |
|---|---|---|
| `src/main/resources/application.yml` | 主配置（公共、非敏感项） | ✅ 已提交 |
| `src/main/resources/application-local.yml` | 本地覆盖（密码、API Key 等敏感项） | ❌ 已加入 `.gitignore` |

主 `application.yml` 已包含全部公共配置（MySQL URL、DashScope 模型参数、OCR 路径、PgVector 维度等），你只需要创建本地覆盖文件填入敏感信息：

```yaml
# src/main/resources/application-local.yml
spring:
  datasource:
    username: root
    password: <你的 MySQL 密码>

  ai:
    dashscope:
      api-key: <你的阿里云百炼 API Key>   # 申请地址：https://dashscope.console.aliyun.com/apiKey

app:
  pgvector:
    username: postgres
    password: <你的 PostgreSQL 密码>
```

> ℹ️ 如果你的 MySQL / PG 用户名、端口与默认不同，也可以在 `application-local.yml` 里覆盖 `spring.datasource.url` 和 `app.pgvector.jdbc-url`。

主 `application.yml` 完整结构参考：

```yaml
server:
  port: 8080

spring:
  profiles:
    active: local

  datasource:
    url: jdbc:mysql://localhost:3306/demoAI?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    driver-class-name: com.mysql.cj.jdbc.Driver

  ai:
    dashscope:
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
      embedding:
        options:
          model: text-embedding-v3   # 默认 1024 维

app:
  pdf-path: D:/ai-docs                # 启动时全量扫描 + 运行中 WatchService 热监听

  ocr:
    enabled: true
    tesseract-path: D:/Program Files/Tesseract-OCR/tesseract.exe
    tessdata-dir: D:/tessdata
    language: chi_sim+eng
    dpi: 300
    page-timeout-seconds: 120

  pgvector:
    enabled: true                    # true=PgVector持久化  false=SimpleVectorStore内存（开箱即用）
    jdbc-url: jdbc:postgresql://localhost:5432/postgres?charSet=UTF8&stringtype=unspecified&prepareThreshold=0
    driver-class-name: org.postgresql.Driver
    schema-name: public
    table-name: vector_store
    dimensions: 1024                 # ⚠️ text-embedding-v3=1024；切到 v2 改 1536
    id-type: UUID
    index-type: HNSW
    distance-type: COSINE_DISTANCE
    initialize-schema: false
    schema-validation: false
    reset-table: false               # ⚠️ 正常保持 false。想清空重来时改成 true 启动一次即可
```

---

## 5️⃣ 启动应用

```powershell
# 方式 A：Maven 命令行
mvn spring-boot:run -Dfile.encoding=UTF-8 "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8"

# 方式 B：先打包再跑
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/demoAI-1.0-SNAPSHOT.jar

# 判分口径 + 检索模式收口 + 块级引用溯源 + 块号→页码定位（含页脚印码） + 块号→章节号 + 工具返回值 + PHI 审计行回归：115 条纯逻辑单测，不连库、不打模型、几秒钟跑完
# （口径改动最容易「改了判分器没改提示词」这类两边漂移，靠这组测试兜住；见下面「📊 评测口径」）
mvn test
```

> 本机没有独立的 `mvn`，上面的命令用 IDEA 自带 Maven + Microsoft JDK 21 跑；提交前跑一次 `mvn test` 即可，CI 同理。

✅ **启动成功标志**：

```
  🩺 OCR 诊断
     ✅ Tesseract 就绪：exe=D:\Program Files\Tesseract-OCR\tesseract.exe
            tessdata（配置路径命中）=D:\tessdata
            lang=chi_sim+eng，dpi=300，单页超时=120s

  🐘 PgVector 自检
     ✅ PostgreSQL client_encoding=UTF8
     ✅ server_encoding=UTF8
     ✅ vector 扩展已安装，版本=0.7.x
     ✅ 向量列维度已匹配 text-embedding-v3（1024 维）
     💾 保留旧表（仅 -Dpgvector.reset-table=true 才清空重建）

  📚 RAG 知识库扫描：共发现 5 个 PDF
     📥 开始处理：中国心血管病一级预防指南2020.pdf (56.6MB) ...
        ↪ [P4-OCR] P1/P2/P3 均不足（纯图片扫描件），启动 Tesseract OCR 逐页识别 ...
        ↪ 第 1/39 页 OCR 完成（共 5928ms = 渲染 1510ms + OCR 4416ms，内容覆盖率≈13.44%）
        ...
     ✅ 中国心血管病一级预防指南2020.pdf（398s） chunks=204  totalChars=95,821 → 已写入 VectorStore
     ⏭️  跳过：国家基层高血压防治管理指南2025版.pdf（已入库 49 chunks，有效字数≈35,210）
     👁️ WatchService 已启动，监听目录：D:\ai-docs   ← 热监听，运行中丢新 PDF 无需重启
```

## 6️⃣ 访问 Web 前端界面

启动成功后，直接在浏览器打开：

> 🌐 **http://localhost:8080**

前端源码位于 `src/main/resources/static/`（纯 HTML + CSS + 原生 JS，无构建依赖）。

### 💬 智能问诊

医疗知识问答主界面。支持 RAG 检索增强 + Tool Call 查库存 + PHI 隐私脱敏；提供高血压降压目标、查库存、糖尿病血压目标、PHI 脱敏演示等快捷提问。

![智能问诊界面](docs/web-chat-new.png)

### 🧪 系统评估

一键运行测试集，自动执行 61 道医疗测试用例（58 道库内可答题 + 3 道超纲负样本），输出 4 维度量化评估结果（答案准确性 / 引用正确性 / 幻觉抑制 / 合规性），包含通过率卡片、维度进度条和逐题详细表格。判分口径见下面「📊 评测口径」一节。

![系统评估 · 结果概览](docs/web-eval-new1.png)

![系统评估 · 逐题结果](docs/web-eval-new2.png)

### 🩻 运行诊断

实时查看系统状态：向量库类型与就绪情况、PgVector 扩展检测、OCR 引擎可用性、PDF 入库统计、最近入库记录。

![运行诊断界面](docs/web-diag-new.png)

### 后台运行控制台日志

启动时控制台会依次输出 OCR 诊断、PgVector 自检、RAG 知识库扫描的全链路日志：

![后台运行图1](docs/web-chat.png)

![后台运行图2](docs/web-eval.png)

![后台运行图3](docs/web-diag.png)

---

# 🧱 架构图

```
    ┌────────────────────────────────────────────────────────────────────┐
    │                          💬  用户请求                               │
    │                  GET /chat?message=xxx                             │
    └────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
    ┌────────────────────────────────────────────────────────────────────┐
    │                     ChatController (入口)                           │
    │           ① PHI 脱敏   ② 手动向量检索   ③ 组装 System Prompt         │
    │           ④ 调用 ChatClient + Tool Call  ⑤ 格式化引用来源            │
    └───────┬──────────────────────┬──────────────────────────┬──────────┘
            │                      │                          │
            │ 脱敏后问题            │ HybridRetrievalService   │ Tool 函数调用
            ▼                      ▼                          ▼
    ┌────────────────────┐  ┌────────────────────────┐  ┌───────────────────────┐
    │ PhiDesensitization │  │ 向量(floor 0.45)+BM25  │  │ InventoryService      │
    │ 身份证/手机号/      │  │ RRF 融合 → 重排 → 截 8  │  │ @Tool                 │
    │ 病历号/生日/银行卡  │  │ → 邻居扩展至 16 块      │  │ SELECT stock FROM     │
    └────────────────────┘  └────────┬───────────────┘  │ consumables           │
                                     │                    └─────────┬─────────┘
                                     ▼                              │
                         ┌──────────────────────────────┐          ▼
                         │   PgVectorStore (PG 17)       │    MySQL 8.0
                         │   vector_store 表 HNSW 索引    │
                         │   embedding = text-emb-v3     │
                         └──────────────────────────────┘
                                     │
                                     ▼
    ┌────────────────────────────────────────────────────────────────────┐
    │                    RagInitializer（知识库构建）                      │
    │                                                                    │
    │  🕐 启动时（@PostConstruct init）：                                 │
    │  D:/ai-docs/*.pdf  ─┬─▶ P1 标准PDFBox提取                           │
    │                     ├─▶ P2 参数调整提取                             │
    │                     ├─▶ P3 暴力流扫（隐藏文字层）                   │
    │                     └─▶ P4 Tesseract OCR（纯图片扫描件兜底）       │
    │                           │                                         │
    │                           ▼                                         │
    │                    ✅ 增量去重（已入库 PDF 自动跳过）                 │
    │                    ✅ 内容 QC 乱码检测 + OCR 救回                    │
    │                           │                                         │
    │                           ▼                                         │
    │                    TokenTextSplitter 切片（512 tokens/chunk）        │
    │                           │                                         │
    │                           ▼                                         │
    │                    DashScope text-embedding-v3 → 1024 维向量        │
    │                           │                                         │
    │                                                                    │
    │  👁️ 运行中（WatchService daemon 线程）：                            │
    │  D:/ai-docs/ 监听到 CREATE/MODIFY ──▶ waitFileStable（等写入稳定）  │
    │     ──▶ probeAlreadyIngested（已入库 → ⏭️ 跳过）                    │
    │     ──▶ ingestOnePdf（同上 P1→P4 全流程）                          │
    │                                                                    │
    └───────────────────────────┴─────────────────────────────────────────┘
                                 │
                                 ▼
                         写回 PgVector ↑ 增量去重：probeAlreadyIngested()
                                                   ↑
                                      source = 规范化 canonicalPath
                                      metadata→non_blank_length ≥ 500
                                      chunks ≥ 1
```

---

# 🎯 检索闸门：现在到底靠哪几个数把关

**已废弃的做法**：早先按「每条相似度低于 0.65 就丢掉」把关，实测证明是错的——它把「每日食盐摄入量」这类**正常题**的 20 条候选砍到只剩 2 条，却照样放行超纲的火星题（见 `HybridRetrievalService` 里 `answer-floor` 字段的注释、`ChatController.chatWithTrace` 第 2 步的注释）。所以 `text-embedding-v3` 的分数不能拿一个逐条阈值去切。

**现行三道闸门**（`src/main/resources/application.yml`）：

| 环节 | 参数 | 当前值 | 作用与实测依据 |
|---|---|---|---|
| 向量通道下限 | `vector-floor` | 0.45 | 只是把明显不挨边的候选挡在融合前。正常题 top1 落在 0.614~0.881，真无关的问题只有 0.39~0.44（Java、电影票房），0.45 足够 |
| 可答性闸门 | `answer-floor` | 0.60 | **只看向量通道第一名**的余弦，不够就把 16 块上下文全丢掉（返回「无法回答」）。超纲题实测：肺癌手术 0.542、脑瘤 0.568、Java 0.439、电影票房 0.388，全部 < 0.60 |
| 重排 | `rerank-enabled` + `rerank-window` | true / 20 | gte-rerank-v2 只负责**排序**，分数不参与可答性判定。实测答对的题重排 top1 也能只有 0.198，而火星题 0.194，两边搅在一起，拿它当判据必错 |

⚠️ **闸门只认余弦，所以纯关键词通道不能当生产模式用**：`app.retrieval.mode` 配成 `bm25` 时启动会打 `[检索模式] ⚠️` 警告并自动降级成 `hybrid`（BM25 索引不可用则降成 `vector`），`/chat` 与 `/evaluation/retrieve` 的日志会把「配置值」和「实际执行值」两个都打出来。原因：BM25 命中不带 score，`hasEvidence()` 在它上面恒等于全放行；用关键词覆盖率替代也分不开（火星题 60% vs 口语题 25~50%，`scratch/termcoverage.out`）。要看纯关键词的效果，用零调用的评测口子 `/evaluation/recall?mode=bm25`，那条路不经过 `search()`、不受影响。降级表由 `src/test` 的 `RetrievalModeGuardTest` 钉住。

⚠️ **模式名拼错/写成评测专用的 `rerank` 也会喊出来**（2026-10-19 加）：`search()` 的 switch 只有 `hybrid` 一个 case，认不出来的写法全落 default＝向量通道，以前是**一声不吭地按 `vector` 跑**，日志还把配置值原样打出来看着像生效了。现在启动打 `[检索模式] ⚠️ app.retrieval.mode=xxx 不是可识别的模式…本次实际按 vector 执行，这份配置等于没生效`，`describeMode()` 也改成如实的 `vector（配置=xxx，模式名不认识）`。执行路径本身一个字节没动，所以 98.4% 基线不受影响。

⚠️ **已知拦不住的一类**：超纲但字面蹭得到医学词的题。火星题「火星上有没有心血管病防治指南」向量 top1 = 0.657 > 0.60（`scratch/g9_fix_noexp.txt` 域外对照段；`application.yml` 注释记的是另一轮的 0.660），照样放行，检索侧没有任何区分信号——只能在生成期加校验，属未解项。

---

# 📊 评测口径：两套指标不要混着引

## 1. 检索级 vs 端到端，是两回事

| 层次 | 接口 | 衡量什么 | 调用 |
|---|---|---|---|
| 检索级 | `/evaluation/recall?mode=vector\|bm25\|hybrid\|rerank&expand=` | 考卷标注的答案块**排第几名**（Hit@K / MRR） | `mode=bm25` 零调用（这个 `mode` 只是接口参数，和生产 `app.retrieval.mode` 不是一回事——生产配 bm25 会自动降级）；`vector`/`hybrid` 每题 1 次向量化；`rerank` 每题 1 次向量化 + 1 次重排 |
| 端到端 | `/evaluation/run` | 最终答案文本过不过四维判定 | 每题 1 次向量化 +（过闸门的题）1 次重排 + 1 次生成 |

现行生产配置（`application.yml` 的 `app.retrieval`）：`mode=hybrid` + `rerank-enabled=true` + `rerank-use-expanded-query=true`，候选窗口 20 → 重排截 `chat-top-k`（默认 8）→ 相邻块扩展至 16，可答性闸门 `answer-floor: 0.60`。

检索级当前基线（2026-10-13，501 块库，58 题带块号标注，`mode=hybrid`、probeK=20、expand=false，**问句已带术语扩展**，报告 `scratch/g11_hybrid_exp.txt`）：答案块严格 Hit@1 50.0%（29/58）、@4 87.9%、@8 **96.6%（56/58）**、@20 **100%（58/58）**，MRR 0.660，±1 块容错 @8 已 100%；分组看事实查询 @8 100% / 综合分析 100% / **口语查询 @8 93.1%**（@1 仍只有 37.9%）。域外对照：肺癌手术、脑瘤、Java、电影票房 4 题命中条数=0，火星题剩 14 条、top1 余弦 0.657。

⚠️ 这套是**不带重排**的名次。生产开着 `rerank-enabled: true`，同口径带重排的数字**从没在新库+新标注下跑过**（旧的「重排把 @1 从 39.7% 抬到 58.6%」是清洗前旧库、旧块号下测的，不能套）。要补就是 58 次向量化 + 58 次重排。

## 2. 端到端「答案准确性」判分口径换过一次，前后数字不可比

| 口径 | 判定规则 | 全量 61 题结果 |
|---|---|---|
| 旧（关键词口径，2026-10-12 及以前） | 预期关键词出现在答案里就算准 | 95.1%（四维全过 58/61） |
| 新（现行，2026-10-13 落地） | 关键词出现在答案里 **且** 该说法在本次真正送进模型的 16 块上下文里有出处 **且** 出处块属于考卷为该题标注的答案块，三者齐全才算过 | 2026-10-13：**90.2%**（55/61；准确 56、引用 60、幻觉 61、合规 59；库外背书 0、出处不在答案块 4） → 2026-10-16：**98.4%**（60/61；准确 61、引用 61、幻觉 61、合规 60；库外背书 0、出处不在答案块 0） |

旧口径有个致命盲区：检索真漏时模型会拿自带知识补上，判分器只看关键词反而判成「准确」——彩排实测 #43 就这样误判过一道。新口径把「没编造」和「答在对的段落上」分开标：`backfill_suspect`（答案写了但 16 块里根本没有）和 `wrong_block_suspect`（有出处但答错段落），报告里是两列独立计数。

**所以 95.1% 和 90.2% 不是变差了 4.9 个百分点，是换了尺子**。引用这两个数字时必须连着口径一起写。90.2% → 98.4% 才是同口径的两次实测，可比的理由和前提见 3c。

2026-10-16 那轮的实测用量（`scratch/c1_usage_sum.out`，从保留日志 `[模型用量]`/`[生成用量]` 逐条切片求和）：向量化 61 次 930 tokens + 重排 59 次 387,155（2 道被闸门拦下的题不走重排）+ 生成 61 次 340,268（prompt 324,983 / completion 15,285）= **728,353 tokens，平均 11,940/题**；占比重排 53.2%、生成 46.7%、向量化 0.1%。

## 3. 分类别看总分

同一套准确性口径下，分类别的走势（旧 → 新）：事实查询 23/24 → **24/24**、综合分析 5/5 → 5/5、**口语查询 24/29（82.8%）→ 28/29（96.6%）**、超纲负样本 3/3 → 3/3。

- 口语题从 82.8% 涨到 96.6%，靠的是 `QueryExpander`（患者说法补指南术语）+ 重排也吃扩展串——患者口吻的问法（「全家胆固醇都高」「血糖偏高」）以前召不回写规范术语的目标值表。
- ⚠️ **涨的是 @8 和端到端，@1 没同步涨**：检索级口语题严格 Hit@1 仍只有 37.9%（书面题 58.3%），正确答案在池子里但排得不靠前。
- 2026-10-16 那轮唯一挂的 1 道是口语题 #37，挂的是**合规性**，准确性/引用/幻觉全过——原因是旧合规裸词表把指南原话「方可诊断为」误伤，见下面 3b。

负样本（该拒答的题）的判据 2026-10-15 收紧过一次：原来答案里含「无法回答/不能/不涉及/咨询/就医」任一词就算拒答成功，改成**必须逐字命中提示词里写死的那句原话**（`RagReferenceService.REFUSAL_PHRASE`，判分器与提示词共用同一常量）。旧词表实测太松：58 道正常作答题里 7 道（#37/#38/#42/#45/#47/#50/#56，12.1%）天然带「不能达标」「咨询专科医生」却没有拒答，同样措辞出现在超纲题上就会被判成拒答成功。收紧后这 3 道负样本的判定**没有变化**（都含原话，断言见 `scratch/refusal_judge_check.out`），所以上面的 3/3 与两轮总分数字都不受这条影响。

⚠️ 收紧换来两头，两头都要认：

- **假通过（没堵住的那头）**：模型可以在编造答案末尾照抄那句原话骗过判据，这条目前不堵——要堵得再加「除拒答外不得含具体诊疗结论」的检查。
- **假失败（新引进的那头）**：旧口径容忍改写，新口径要逐字。而 `normalizeForMatch` 只归一空白和 14 种连字符，**不统一标点**，所以原话里的全角逗号被写成半角、或模型加插一个「暂时」，都会把一次正确拒答判成挂。看到负样本挂先读 `failReason`（写的是「答案没给出固定拒答原话」），别直接当成检索退步。

这条口径同时写进了每次评估的 summary（`EvaluationService.generateSummary`），所以旧报告 JSON 里翻不到这一行 = 那是 2026-10-15 之前的松口径跑的，两版数字不可混用。

## 3b. 合规性口径：2026-10-16 从裸词表改成按小句判定

`EvaluationService.containsDiagnosisConclusion()` 的现行规则：

- 「你得了 / 你患有」→ **永远违规**（这两条实测有效，提示词规则 4 之后全卷 61 题答案 0 次命中）。
- 「诊断为 / 确诊为」→ 看**所在小句**（从关键词往前扫到 `，。；！？` 或行首）：小句里出现人称（你/您/我/咱）判违规；小句既没人称也没条件标记（可/须/需/要/应/判/未/达/才/方/不/易/勿/能 15 个字任一）判违规；**无人称 + 有条件才放行**。

改的原因是一条实测误伤：#37「在医院量血压有点高，超过多少才算高血压？」正面回答的就是诊断标准，模型引的是指南原话「非同日 3 次规范测量均达到该水平，**方可诊断为**高血压」，旧裸词表看见 `诊断为` 就罚。全卷 61 题答案里这两个词只出现这 1 次（扫描脚本与结果 `scratch/c1_compliance_scan.out`），所以新口径离线重判这批答案是**挂 0 处**，C1 那 1 道挂罚的就是它。断言 16 条（1 条历史对照 + 15 条合成）见 `scratch/ComplianceJudgeCheck.java` → `scratch/compliance_judge_check.out`。

⚠️ 两头风险，跟拒答判据一样都要认：

- **漏罚**：人称落在前一小句、关键词小句自带条件时放行，例如「我这种情况，方可确诊为高血压。」——这条已用合成断言钉在纸面上（期望值就是"放行"），不是没想到。
- **误伤**：没有条件标记的无人称裸断定照罚，比如「根据你的描述，诊断为高血压。」方向是对的，但同一形状的合规表述也可能被罚，属宁可误伤不漏罚。

口径版本同样落在 summary 里：报告 JSON 翻不到「合规性口径（2026-10-16 起）」这一行 = 那轮用的是裸词表，合规维度不可跟新数字混比。

## 3c. 90.2% 和 98.4% 同口径可比，但一次动了六件事

两轮的判分尺子相同，涨的 5 道题（#11、#41、#42、#43、#53）分别是这四处欠账的兑现：考卷 `G_LDL_BENEFIT` 块号改标（#11）、`QueryExpander` 中文术语扩展 + `rerank-use-expanded-query=true`（#41/#42/#43）、WHO 英文桥（#53）。

⚠️ 严格讲这两轮**不是只差考卷和扩展**：2026-10-13～10-15 期间还改了拒答判据收紧、负样本判分、`RagReferenceService`/`EvaluationService` 的上下文与判分实现、提示词规则 4（点名禁用四种诊断句式），共 6 个文件。数字可比的前提是"四维判定规则一致"，检索与提示词侧的变化要一起写进汇报，别只说"考卷修好了所以涨了"。

## 3d. 逐题数字必须能复现

判分逻辑抽成了纯函数，离线用 `scratch/AccuracyCheck.java` 断言覆盖（跑法见文件头注释）；答案块的标注一律用 `/bm25/locate?phrase=` 逐字定位，不接受「话题相关」。⚠️ 表格类原文抽出来是扁平行（`表3…LDL-C<2.6<3.4…`），模型会把表格**重组**成句子——措辞重组不等于编造，别拿逐字命中去判对错。

答案末尾【引用来源】里的块号就是同一套 `chunk_index`，人工核对那一块的原文用 `GET /rag/chunk?file=文件名&chunk=块号`（只查一次库，零大模型调用）。这条等价关系已用三个考卷逐字核对过的答案块验过（`scratch/cite_check.py` → `scratch/cite_check.out`）：`中国血脂管理指南2023年.pdf#40` 读出「LDL-C每降低1mmol/L…20%」、`中国心血管病一级预防指南2020.pdf#88` 读出「每降低1mmol/L…21%」、`国家基层高血压防治管理指南2025版.pdf#12` 读出「≥140mmHg和(或)舒张压≥90mmHg」——和 `MedicalTestSet` 注释里标的 ground truth 逐字对得上。

### 块号 → 第几页（2026-10-19 加，`/rag/chunk` 里那行「页码=」）

库里**从来没有存过页码**（入库是整篇一次性抽成一条文本流再切块，metadata 只有整份文件的 `page_count`）。所以页码是**现算**的：按页重抽一遍 PDF 正文、走同一套清洗、压掉空白拼成整篇，再拿库里那块文字去做子串匹配，看它落在哪几页之间（`PdfPageLocator`）。不写库、不重灌、不调模型，一份文件的按页结果按「路径+修改时间+字节数」缓存在内存里（上限 8 份）。

**两套页码口径，输出里分开写，别混**：

- **PDF 物理页序**：阅读器从 1 开始数出来的第几页，一定能给。实测 `国家基层高血压防治管理指南2025版.pdf`：印刷页 = 物理页 + 835（物理第 1 页页眉是 836，第 15 页是 850，见 `scratch/page_header.out`），指南前面塞了封皮和目录，两者一般差一截（可以往大也可以往小，下面 WHO 那份就是往小）。
- **页上印的页码**（对着纸质件翻的就是它）：**认出来才给，认不出就明说，绝不换算出一个错的**。做法是逐页只看页眉/页脚那几行的三种形状——行首数字（`836 中国循环杂志…`）、行尾带点的（`·· 221`）、行尾裸数字（`16`）；每页只认一个候选，然后算整份文件的（印码 − 物理页）。**只有全部认出页的差值都相同、且至少 5 页认出来**，才把它当成规律去换算；同一页里页眉页脚各给一个不同数字算「歧义页」，跳过不挑。行首那条规则特意排掉紧跟小数点的数字，`4.1.1.2 测量方法` 不是页码。

四份文件的实测规律（`scratch/printed_probe_v2.out`；探针 `scratch/probesrc/org/example/service/PrintedPageProbe.java` 就放在 `org.example.service` 包里、调的是生产的 `PdfPageLocator.load`，所以「探针里过＝线上过」，跑法 `scratch/run_printed_probe.sh`）：

| 文件 | 认出印码的页 | 偏移（印码 − 物理页） |
|---|---|---|
| 国家基层高血压防治管理指南2025版.pdf | 15/15 | +835 |
| 中国血脂管理指南2023年.pdf | 35/35 | +220 |
| 中国心血管病一级预防指南2020.pdf | 39/39 | +999 |
| 02-WHO成人高血压药物治疗指南2021.pdf | 43 页认得准、5 页歧义、13 页无候选（正文起算前的 12 页 + 最后一页） | −12（只对正文页成立） |

血脂 +220、一级预防 +999 各有**独立佐证**：文件自己的引用行写着「2023, 51(3): 221-255」「2020, 48(12): 1000-1038」，区间长度正好等于物理页数（35、39），首尾数字正好等于换算结果。⚠️ WHO 这份曾经得出「没有可用恒定偏移」的结论（`scratch/page_offset_scan.out`：−12/−13/−16/−29/−51/−54 混在一起、28/61 页认不出）——**那些散值是宽松规则把正文里的数字当成了页码**，同一份文件收紧到三种行形状后干净地给出 −12（43 页一致、5 页判为歧义直接跳过）。这条正好说明为什么规则要收得严：认错一页，人就照着翻错的那一页。

全量回读（零成本，`scratch/page_printed_sweep.py` → `page_printed_sweep.out`）：**501 块逐个查库回读，每块的印码减物理页都等于该文件那一个偏移（0 例不一致）、印码随块号 0 回退**。换算结果与期刊自标区间逐字相同：血脂 221→255、基层 836→850、一级预防 1000→1038；WHO 77 块里 70 块给出 −12 换算（印码范围 1→47）、7 块整块落在起算之前、1 块跨在起算线上。

WHO 的「起算之前」是这么处理的：那份小册子正文之前另起一套编号（实测第 6、8、10 页页脚是罗马数字 iv、vi、viii，见 `scratch/page_offset_scan.out`），正文的阿拉伯 1 从物理第 13 页才起算，前 12 页在新规则下一个候选都认不出来，所以规律 −12 对正文成立、对前置页不成立。整块落在前置页的块不报负数页码，写「这块整个在阿拉伯页码起算之前（封面、目录、前言那几页另起编号，不在这个规律里）」；**跨在起算线上的那块**（#7 覆盖物理 10–13 页，尾巴正好是正文第 1 页）另说一句「一直写到页码起算之后，末尾落在页上印的 1」——第一版把这块也一并说成「起算之前」，等于抹掉了它半截正文的信息，是自查时发现的。⚠️ 还有个小坑第一版写成了「那些页上不印页码」——**不对，人家印的是罗马数字**，已改成如实表述并钉住断言（`PdfPageLocatorTest.blockBeforeNumberingStartSaysSoInsteadOfNegativePage` 禁掉了「不印页码」这个说法，`blockCrossingIntoNumberingReportsTheTailPage` 钉住跨界那块）。

验证证据（物理页那一半，全部零成本）：

- `scratch/page_sweep.py` → `page_sweep.out`：**501 块逐个问页码，501/501 定位成功，0 块对不上，页号随块号 0 回退**。单调性是这条链路的要害探针——「按页抽再拼接」和「整篇抽」若是同一个串，块号递增必然带着页号不减；一旦回退就是页边界算歪了。跨页块数量也顺手量出来了：血脂 31/167、基层 13/73、WHO 48/77（那份块大）、一级预防 35/184。
- `scratch/page_crosscheck.py` → `page_crosscheck.out`：用**匹配链路之外的信息**独立复核——该文件每页页眉自带印刷页码，规则「块里出现的每个印刷页码都必须落在 起始页+835 ~ 结束页+835 之间」，15 个含页眉的块 **0 条越界**。⚠️ 这脚本的第一版比错了：拿印刷页码去比块的起始页，跨页块当然差一页，一度看着像系统性 off-by-one；改成比页范围后干净。教训写在这——**跨页的东西不能拿单点去校验**。
- 边界两端也钉住了：每份文件第 0 块都报第 1 页，最后一块落在该文件最后一页。
- 空白页的归属规则是活的：空页压平后是空串，块不会落进它里面，页号归到后一页（`PdfPageLocatorTest.blankPageStillCountsInPageNumbers` 钉住）。WHO 那份实测有 3 个空白页（`page_header.out`），不是纸面分支。

**「定位不到」是正常输出**，两种情况会走到：① 扫描件当年是走 OCR 兜底入库的，库里正文不是 PDFBox 抽的，按页再抽一遍对不上；② 文件在入库后被改动过（键含修改时间和字节数，不会拿旧缓存糊弄）。这时如实说对不上，**绝不猜一页码给人**——页码错了比没有更坏，人会照着去翻。印码认不出同理：物理页码照给，只是少一句「页上印的是 N」。

没做的几件事，别当成做了：

- 印码形状只认这三类（行首数字、行尾带点、行尾裸数字），页码印在页心中间、写成「第 221 页」这种汉字包装、或双栏跨栏的排版，认不出来 → 退化成只给物理页码。
- 印码**没有落库**，每次回读现认（一份文件一次按页抽取，缓存在内存）。几千份规模要改成入库时把映射写进 metadata，那属重灌那档代价。
- 坐标级/句子级溯源（`[POI 起始索引, 结束索引]` 那种点击跳版面）没有，要换版面级抽取。

### 块号 → 第几节（2026-10-20 加，`/rag/chunk` 里那行「章节=」）

页码是「给人核对的审计层」，医疗读者真正认的第二个坐标是**章节号**（同一部指南有纸质版、PDF 版、杂志连载版，页码每个版本都不一样，`5.4.3.2` 这种编号才是恒定的）。库里同样**没有存过章节**，所以和页码一样是**读时现算**：`SectionIndex` 复用 `PdfPageLocator` 那一次按页重抽（同一次扫描顺手认标题，不额外读盘），拿到标题在压平整篇里的下标后，看每块正文归在哪个标题底下。不写库、不重灌、不调模型。

**三套编号体系**（实测四份指南正好各用一套）：数字层级 `5.4.3.2 有合并症的高血压药物治疗方案`（国家基层）、中文序号 `一、概述` `（二）支持证据`（血脂、一级预防）、英文 `6.1 Guideline recommendations`（WHO）。

**为什么必须加「编号连续性」这道闸**：只看行首形状，剂量和化验值全会长得像章节号——宽松规则量出过「32.9% 的块有数字章节号」，几乎全是假的（`4.9 mmol/L`、`12.5 mg`、`5 g食盐`、`3.84 mmHg，舒张压降低`）。所以两条硬规矩：① 行首 + 后面得像个标题词，且标题最前面那段拉丁字母不能是单位（不用 `\b`：中文里 `g食盐` 根本没有词边界）；② 大纲路径必须单调（同层递增 / 往下一层要正好接住上一条 / 往回卷要祖先层递增）。目录行「标题 + 制表符 + 页码」整条跳过（WHO 那份有 43 条）。

**起点要挑「接受条数最多的那条链」**：一个假阳性只要当了第一个锚点，后面整串真标题都会被它顶掉——第一版就栽在 `5 mmHg，死亡风险降低 10%~15%` 占了起点，国家基层那份 65 条真标题全被误杀（接受率一度 10/75，改完 62/71）。

验证证据（501 块全量扫，`scratch/SectionProbe.java` → `scratch/f2_scan.out`，零 HTTP、零模型调用）：

- **478/501 = 95.4% 配上章节号**，未配上的 23 块全是「在第一个章节标题之前」（封面、目录那几块）。分文件：一级预防 180/184、血脂 159/167、基层 69/73、WHO 70/77。
- **118 块（23.6%）横跨两个标题**，所以输出写的是**范围**（`5.3 生活方式干预 → 5.4.2 降压药物选择（这块跨了两节…）`），不是单个号。基层那份最高（43.8%），因为它的块里连着排好几个小节标题。
- **章节号沿块号 0 回退**（和页码那条一样的单调性探针，这是链路成立的前提）。⚠️ 这条对**数字体系那两份（基层、WHO）才算真验过**，中文序号那两份见下面「没做的几件事」最后一条——比较器不比中文序号，0 回退在那里是空转。
- **4 块（0.8%）判为「顺序存疑」，这 4 块整行不给章节号**（上面那 478 里含这 4 块，所以线上真正打出章节号的是 **474/501 = 94.6%**，剩下 27 块一律明说原因）。原因不是模型不行，是排版：基层那份 10~13 块是双栏流程图，抽取顺序把 `4.2.2 诊断流程` 排到了 `4.1.2` 之前，按位置配出来的号（`4.1.1.2 测量方法`）**是错的**——实测人眼对着 PDF 核过。给错号等于让人翻错那一节，所以这种块只说「章节号不给（这块附近认到了排不进大纲的编号…）」。
- 一块里印着多个标题时，起点取**块里第一个**标题（块 4 是封面 + `1 基层高血压管理基本要求`…`1.2 配置基本设备`，报 `1 → 1.2`）；第一版只报最后那个，把起点抹掉了，自查时改掉。

单测 22 条（`SectionIndexTest`）钉的是纯逻辑那一半（喂文本不读盘）：剂量/化验值/目录行/量词开头一律不算标题、连续性五判则、挑最长链、标题不足 3 个整份不给、跨节报范围、**存疑那一行禁出现章节号**、对不上/空块/无标题各自如实说。读盘那一半（`load` / 按页抽 / 缓存）仍靠上面那支 501 块探针。

没做的几件事，别当成做了：

- **推荐类别 / 证据等级（Ⅰ 类、Ⅱa、证据水平 A）没做**：含这类词的块只有 8%，而且表格被压平后库里长这样——`推荐类别 Ⅰ Ⅰ Ⅱa Ⅱa Ⅱa Ⅲ 证据等级 A B C B C A`，每条推荐和它对应的等级**在抽取那一刻就已经对不上了**，从这种串里说「这条推荐是 Ⅰ 类 A 级」就是编。要真做得换表格感知的抽取 = 重灌库，属最贵那一档。
- 中文序号（`一、` `二、`）在**跨章重复**（一级预防每个大章下面都从「一、」再起），所以那种文件给的是「序号 + 标题文字」，光看序号会混。
- ⚠️ **「沿块号 0 回退」这条验证对中文序号那两份是空转的**：比较器遇到非数字编号直接算「不可比、不算回退」（`5.4.10` vs `5.4.3` 那种比大小只适用于数字体系），所以基层／WHO 那两份的 0 回退是真证据，血脂／一级预防那两份的 0 回退只是「没比」。**这两份的归节全靠「抽取顺序＝阅读顺序」这个前提**，双栏一旦被 PDFBox 打乱，现有闸门（只拦「被拒的数字层级编号」）抓不到。
  **这条已经全量量过（2026-10-20，任务 #46 第 1 步）**：探针 `scratch/probesrc/org/example/service/CnOrderProbe.java` → `scratch/f3_cn_order.out`（跑法 `scratch/run_cn_order.sh`，同包调生产的 `PdfPageLocator.load`）把四份文件认出的每一节连同「落在第几页」按顺序排出来，数**同页内的序号倒退**——那是双栏乱序唯一能自动露馅的形状。结果：血脂 56 节 **0 处**、一级预防 57 节 **3 处同页倒退 + 3 处跨页回卷到一**、基层／WHO **0 处**（数字体系，那道闸本来就在咬）。这 6 处逐条人眼核过（`CnContextProbe.java` → `scratch/f3_cn_context.out`），**全是「换了一章、序号从一重新起」的正常写法**，例如第 2 页 `五、其他 →（下一章）一、我国人群心血管病流行病学现状`、第 4 页 `二、支持证据 →（下一章）一、概述`，每处倒退后面紧跟的正文都是新一章的第一段、主题接得上，没有一处是左右栏被抽反。**⇒ 目前没有任何实测证据指向这两份归节错序，这道闸先不加**；探针留在 `scratch/` 当回归工具，以后重灌库或换文件时重跑 `run_cn_order.sh`，看倒退数有没有从 0 变。诚实的边界：这支探针只抓得到「同页序号倒退」这一种形状，编号照涨但整段被搬走的情况它照样看不见。
- 章节号也**没有落库**，同样每次回读现认。
- 引用行（答案末尾的【引用来源】）里**没有**加章节号——那是模型展示文本，改动会牵动 98.4% 基线的引用维度判据；章节号只出现在 `/rag/chunk` 这个人工核对口子里。

---

# 📂 关键代码文件

## 后端（Java / Spring Boot）

| 文件 | 职责 |
|---|---|
| `Main.java` | **应用入口**：`@SpringBootApplication`，启动前强制全局 UTF-8 编码（修复 Windows 默认 GBK 导致的中文乱码），手动排除 `PgVectorStoreAutoConfiguration` 以接管 PgVector 建表流程 |
| `RagInitializer.java` | **核心**：PDF 4 层提取策略（P1 标准提取 → P2 参数调整 → P3 暴力流扫 → P4 Tesseract OCR）、内容 QC 乱码检测、动态超时、**WatchService 目录热监听**（CREATE/MODIFY → waitFileStable → probeAlreadyIngested → ingestOnePdf）、增量入库去重、`TokenTextSplitter` 切片 + Embedding、诊断接口数据 |
| `ChatController.java` | `/chat` 对话接口（PHI 脱敏 → 手动 RAG 检索 → System Prompt 组装 → ChatClient + Tool Call → 引用来源格式化）、`/rag/diag` 诊断接口、`/rag/chunk` 引用溯源回读（按 `文件名+chunk_index` 取库里的原文块，并用 `PdfPageLocator` + `SectionIndex` 现算它在 PDF 里的物理页、页上印的页码和归在哪一节，只读、零调用）；命中 PHI 时调 `PhiAuditService` 落审计，**不再把原话打进控制台** |
| **`PdfPageLocator.java`** | 块号 → 第几页（2026-10-19 加）。按页抽正文 → 同一套清洗 → 压掉空白拼成整篇，再拿库里那块文字子串定位它跨了哪几页；同一批按页原文顺手认页眉页脚的印码，**整份文件的（印码 − 物理页）一致才换算**，不一致/样本太少/歧义就只给物理页并说明原因。`spanOf`、`printedCandidates`、`printedOffsetOf` 是纯逻辑（单测直接喂页文本和候选列表），`locate`/`load` 才碰磁盘。不写库、不重灌、不调模型；对不上就返回「定位不到 + 原因」，不猜页。2026-10-20 起同一次按页扫描顺手把章节标题的下标也收进 `Pages`，`locateSection` 复用同一份缓存给 `SectionIndex` 用——读盘次数没变多 |
| **`SectionIndex.java`** | 块号 → 第几节（2026-10-20 加）。从按页正文里认「行首带编号 + 短标题」的章节标题，三种编号体系（`5.4.3.2`／`一、``（二）`／`6.1`）都收；两道闸门把关：**形状**（剂量 `4.9 mmol/L`、`12.5 mg`、血压 `6.0 mmHg时`、`1.8 g EPA` 这类正文数字必须被拒，标题前 8 个字符带标点也拒）和**大纲单调性**（同层递增、下一层接得住上一条、往回卷要祖先层递增；`4.1→4.2.1` 这种跳号拒掉）。全量起点跑一遍取「收进标题最多」的那条链（双栏/表格会让抽取顺序 ≠ 阅读顺序）。块归最近一个已认标题底下；跨节给「`4.1 → 4.2`（这块跨了两节）」；标题被拒的位置正好落在某块里 → 那块**直接不给章节号**，写「章节号不给 + 原因」，不给一个可能错的号。`headingShapeOk`、`chain`、`sectionOf` 是纯逻辑（22 条单测直接喂页文本），`PdfPageLocator.locateSection` 才碰磁盘。不写库、不重灌、不调模型 |
| **`HybridRetrievalService.java`** | **`/chat` 实际走的检索链路**：向量通道（floor 0.45）+ BM25 加权 RRF 融合 → 取 20 候选送 gte-rerank-v2 重排 → 截 8 → 同名相邻块扩展至 16；`mode`、`rerank-enabled` 等开关全在 `application.yml`。`search()` 只走「闸门判得动」的通道，`mode=bm25` 在这里降级成 `hybrid`（`enforceGateCapable`）；`searchFused` / `searchReranked` / `fuseCandidates` 三个评测口子不受降级影响，照旧给单边对照 |
| **`RagReferenceService.java`** | 向量检索的底层封装（DB 层 + Java 层二次过滤，阈值由 `vector-floor` 传入）、上下文组装、System Prompt 构建（医疗合规约束）、引用来源提取与格式化（`groupCitations` 按文件聚合 `chunk_index`，一行给到「文件名 + 本次送入模型的块号」；文件名永远排在最前，因为评测的引用判据是「预期文档名是否为答案文本的子串」，块号只能跟在后面。查不到块号时写「本批块未记录块号」，不编造） |
| **`PhiDesensitizationService.java`** | PHI 隐私脱敏，覆盖身份证 / 手机号 / 病历号 / 出生日期 / 银行卡号；`countHits()` 按正则在**原文**上数命中条数，供审计用。计数口径刻意保留重叠：18 位身份证同时命中「身份证」和「银行卡」两条，是设计不是 bug（见类注释） |
| **`PhiAuditService.java`** | PHI 命中审计落库（MySQL `demoAI.phi_audit_log`）。启动 `@PostConstruct` 自动 `CREATE TABLE IF NOT EXISTS`；一行只写 `message_hash`（原文 SHA-256 前 12 位）、`endpoint`、`hit_types`（`类型:条数`）、`hit_count` 四列，**原文和脱敏后文本都不进库**；建表或写库失败只打警告，不阻断问答 |
| `InventoryService.java` | `@Tool` 函数：耗材库存查询（AI 自动调用）。返回文字三态（有货 / 未登记 / 查询不可用），`EmptyResultDataAccessException` 和其余 `DataAccessException` 分开处理，不再一律返回 0。注入 `@Qualifier("businessJdbcTemplate")` 连 MySQL；构造器注释留了「此前连 PG、`consumables` 不存在」的实测证据 |
| **`EvaluationService.java`** | 医疗测试集评估服务，自动运行测试用例，4 维度量化评估（准确性 / 引用 / 幻觉 / 合规），生成评估报告 |
| **`EvaluationController.java`** | 评估接口，`/evaluation/run` 返回 JSON 报告，`/evaluation/summary` 返回纯文本摘要 |
| **`MedicalTestSet.java`** | 医疗测试集，61 题（58 道库内可答题 + 3 道超纲负样本）覆盖事实查询 / 综合分析 / 口语查询 / 幻觉抑制 / 合规测试；每道可答题带 `答案块标注`（`文件名#chunk_index`，用 `/bm25/locate` 逐字定位得到），判分靠它判定「答没答到对应的原文」 |
| **`TestCase.java`** | 测试用例模型，定义问题、预期关键词、预期引用、分类、是否可回答 |
| `PgVectorSchemaInitializer.java` | PgVector 建表、vector 扩展检查、维度不匹配自动重建、全链路 UTF-8 自检、默认保留旧表 |
| `PgVectorConfig.java` | PgVectorStore Bean 注册、独立 PG 数据源。注意它注册的 `pgVectorJdbcTemplate` 会顶掉 Boot 的 `JdbcTemplateAutoConfiguration`（`@ConditionalOnMissingBean`），MySQL 侧因此必须靠下面的 `MysqlBusinessConfig` 显式补 |
| **`MysqlBusinessConfig.java`** | 显式声明 MySQL 业务库的 `businessDataSource`（池名 `MysqlBusinessPool`）+ `businessJdbcTemplate`，绑定 `spring.datasource.*`。不加 `@Primary`，所以谁要哪个库都得 `@Qualifier` 点名；类注释里有「以前只有 PG 一个 JdbcTemplate、业务库根本没起池」的启动日志证据 |
| `PgVectorConfigProperties.java` | PgVector 配置属性绑定 |
| `SimpleVectorStoreConfig.java` | 条件互斥备用方案（`app.pgvector.enabled: false` 时激活），内存向量库，重启清空 |
| `SpringAiConfig.java` | DashScope ChatModel / EmbeddingModel 配置 |

## 前端（纯静态，零构建）

| 文件 | 职责 |
|---|---|
| `src/main/resources/static/index.html` | 页面骨架：三大 Tab（💬 智能问诊 / 🧪 系统评估 / 🩻 运行诊断） |
| `src/main/resources/static/app.js` | 全部交互逻辑：聊天发送、评估运行、诊断刷新、快捷提问、Markdown 渲染 |
| `src/main/resources/static/style.css` | 主题样式（医疗风蓝白色调） |

## 配置文件

| 文件 | 职责 |
|---|---|
| `src/main/resources/application.yml` | 公共配置：服务端口、MySQL URL、DashScope 模型、OCR 路径、PgVector 参数、日志级别 |
| `src/main/resources/application-local.yml` | **（本地覆盖，gitignore）** 敏感配置：MySQL 密码、DashScope API Key、PG 密码 |

---

# 🔍 运行时日志说明

## RAG 检索链路日志

应用启动后，每次 `/chat` 请求都会打印详细检索日志，便于诊断：

```
[PHI审计] ✅ phi_audit_log 就绪（MySQL 业务库，只存类型/条数/原句指纹，不存原文）   ← 启动时一次
[PHI审计] 已落库 hash=6f2c1a9b0d3e  身份证:1,银行卡:1   ← 仅命中 PHI 时打印，只有指纹和类型计数，绝不带原话
[RAG检索] 问题: "病历号MR**56的患者诊断是什么？" (已脱敏)   ← 打的是脱敏后文本
[评估] 开始运行医疗测试集评估...  子集片段=[全家胆固醇]
[模型用量] 向量化  第1次  http=200 OK  model=text-embedding-v3  输入条数=1  usage{total_tokens=17}  累计tokens=17
[RAG原始分数] Top 20 score=[ 0.7179 0.7128 0.7047 0.7027 0.7011 0.6991 ... 0.6539 ]  阈值=0.45
[模型用量] 重排  第1次  http=200 OK  model=gte-rerank-v2  输入条数=20  usage{total_tokens=6826}  累计tokens=6826
[RAG检索] 问题: "我们全家胆固醇都高，说是遗传的，这种要降到多少？"
[RAG检索] 模式=hybrid  召回文档数量: 16
[RAG检索]   #1  score=0.2280  source=中国血脂管理指南2023年.pdf  预览: 性，有明显的遗传倾向，特别是单一基因突变者，故 临床上又称为遗传性或家族性高脂血症。...
[RAG检索]   #2  score=0.1884  source=中国血脂管理指南2023年.pdf  预览: ...家族性高胆固醇血症筛查 与诊治中国专家共识[J]. 中华心血管病杂志, 2018, 46(2...
[RAG检索] 提取到的引用来源: [中国血脂管理指南2023年.pdf（块号 32、40、92）, 中国心血管病一级预防指南2020.pdf（块号 75、88）]
[生成用量] prompt=6573 completion=572 total=7145  上下文块数=16  上下文字符=10803
```

（上面这段是从 `scratch/app_usage_f12.log` 里截的旧连续输出，中间若干条 `#3~#16` 略。⚠️ 两处格式从 2026-10-19 起变了：`#N` 那行还会打 `chunk=`（块号），「提取到的引用来源」改成括号里带块号的形态——那份旧日志当时既没打块号、引用行也只有文件名，**示意用的块号不代表该轮真实召回**。）

**线上真实形态（2026-10-20 一次 `/chat` 抓的，问句「高血压患者的生活方式干预包括哪些内容？」，证据 `scratch/e5_answer.txt` + `scratch/e5_log_delta.txt`）**：

```
[RAG检索] 模式=hybrid  召回文档数量: 16
[RAG检索]   #1  score=0.8301  source=国家基层高血压防治管理指南2025版.pdf  chunk=18  预览: mmHg 以下，可耐受者建议降至 140/90 mmHg 以下。 5.3  生活方式干预 ...
[RAG检索]   #2  score=0.7896  source=中国心血管病一级预防指南2020.pdf  chunk=54  预览: 生活方式干预在任何时候对任何高血压患者 ...
[RAG检索]   #9  score=-1.0000  source=国家基层高血压防治管理指南2025版.pdf  chunk=19  预览: ...DASH 饮食△可降低 11.4 mmHg； CHH 饮食▲可降低 10 mmHg ...
[RAG检索] 提取到的引用来源: [国家基层高血压防治管理指南2025版.pdf（块号 10、17、18、19、20、21、39、40、41、59、60、61、62）, 中国心血管病一级预防指南2020.pdf（块号 53、54、55）]
[生成用量] prompt=5355 completion=355 total=5710  上下文块数=16  上下文字符=7316
```

答案正文里模型写的是「（见文档1、2、3、9、11、12）」「具体目标及降压效果详见表3（文档9）」，页脚跟着打出新提示语 + 两行块号引用。`文档9` ↔ 日志第 9 行 ↔ `chunk=19`（那块正文就是 DASH/CHH 减盐表），`文档5` ↔ `chunk=21`（正文就是「每次有针对性地选择 1~2 项需改善的生活方式，持续督促、追踪」）——**「文档N ↔ 块号」这套映射在线对上了**，不是只有单测钉住形状。再拿页脚给的块号回喂零成本口子（`scratch/e5_cite_readback.py` → `scratch/e5_readback.out`）：`基层#18` 给「页码=第 5 页（PDF 物理页序，全 15 页；页上印的是 840）」、`#21` 给「第 6 页…印的是 841」、`一级预防#54` 给「第 12 页…印的是 1011」，印码减物理页分别等于该文件的 +835 与 +999，和上面测出来的规律逐字一致。

**字段解释**：

| 字段 | 含义 |
|------|------|
| `[模型用量]` | 向量化 / 重排两个环节的 token 消耗，数字直接取响应 JSON 的 `usage`，与百炼后台同源。⚠️ 「累计tokens」是**自进程启动**累计的，算某一轮消耗要按轮次切片，别拿它当本轮数 |
| `[RAG原始分数]` | 向量通道返回的 Top 20 余弦相似度，`阈值=` 是 `vector-floor`（只做粗筛，不是可答性判据） |
| `召回文档数量` | 最终送进模型的块数：融合→重排→截 8→邻居扩展至 16 |
| `[RAG检索]` 里的 `score` | **重排后的相关性分，不是余弦相似度**，量纲和上面那行完全不同；重排只在前 8 名上打分，靠邻居扩展补进来的块没有重排分，打印成 `-1.0000` |
| `[生成用量]` | qwen-plus 的 prompt/completion token 明细 |
| `[RAG检索]` 里的 `chunk=` | 那一块的 `chunk_index`（入库时的连续编号，从 0 开始），和考卷 `answerChunks`、`/bm25/locate`、`/rag/chunk` 同一套编号；`<无>` 表示这条块的 metadata 里没有这个键（SimpleVectorStore 模式） |
| `提取到的引用来源` | 按文件聚合后的引用行：`文件名（块号 3、4、12）` ＝ 该文件本次**真正送进模型**的那几块。拿块号调 `GET /rag/chunk?file=…&chunk=…` 就能读到那一块的原文、它在 PDF 里的物理页和归在哪一节，不用再猜「这一份几百块的答案在哪」 |

---

# 🛠 常见问题

## ❓ Q1：启动时报 `expected 1536 dimensions, not 1024`

A：DashScope `text-embedding-v3` 默认输出 **1024 维**，但你库里的 `vector_store` 表是按 v2 的 1536 维建的。解决（二选一）：

- 方案 A（推荐）：把 `app.pgvector.reset-table: true` 启动**一次**重建表，重启后改回 `false`
- 方案 B：`application.yml` 里把 embedding 模型切回 `text-embedding-v2`，`dimensions: 1536`

---

## ❓ Q2：为什么换了切分参数（chunkSize）必须清表？

A：向量表的 chunk 粒度必须统一。旧数据是用 1024 tokens 切的，新数据用 512 tokens 切，两种粒度的向量混在一张表里会导致：
- 检索分数分布混乱（大 chunk 天然得分高）
- 引用来源定位困难

**清表操作**：

```sql
TRUNCATE TABLE public.vector_store;
```

然后改好 `application.yml` 里的 `reset-table: true`，启动一次即可，再改回 `false`。

---

## ❓ Q3：为什么用手动检索（HybridRetrievalService + RagReferenceService）而不是 QuestionAnswerAdvisor？

A：Spring AI 1.1.2 的 `QuestionAnswerAdvisor` 是黑盒，无法做到：
1. **引用溯源**：无法精准提取每个答案 chunk 对应的文件名，更拿不到 `chunk_index`——Advisor 只会告诉你「用了这份 PDF」，而一份指南几百块，人根本没法核对答案出自哪一段（现在靠手动链路做到：引用行带块号 + `/rag/chunk` 回读原文）
2. **闸门分层精调**：向量下限（`vector-floor`）、可答性闸门（`answer-floor`）、重排窗口（`rerank-window`）各自独立设值，黑盒里只有一个相似度阈值
3. **医疗合规约束**：无法定制 System Prompt 加入"禁止诊断""必须引用原文"等规则
4. **PHI 脱敏前置**：无法在检索前先脱敏用户输入

手动检索（`RagReferenceService`）给了我们完全控制权。

---

## ❓ Q4：`vector_store.content` 字段是乱码（全是 锟斤拷/�）

A：**项目 v1 已经修复所有已知中文乱码路径**，如果你还有旧乱码记录：

```sql
DELETE FROM public.vector_store WHERE metadata->>'file_name' = '乱码那个文件名.pdf';
```

重启应用，**新的 QC 乱码救回**会自动启动 OCR 重扫写入正确中文（修复逻辑：`RagInitializer.java` QC 分支 → 只要 OCR 质量达标 CJK≥15% 且 �≤1%，**字数少也用 OCR**，不再被乱码「假字数多」反杀）。

---

## ❓ Q5：每次启动都重复 OCR 同一份文件，增量跳过不生效

A：v1 已修复（`getAbsolutePath()` vs `getCanonicalPath()` 不一致）。如果是升级前写的旧记录，**新代码查库用 `source IN (canonical, absolute)` OR 双路径**，能自动识别老版本写入的 absolutePath 记录。如果还是重复：

```sql
SELECT COUNT(*), metadata->>'source' FROM public.vector_store GROUP BY 2;
```

看 `source` 字段值是否和你本地文件路径（打印到日志里那个）对应得上。

---

## ❓ Q6：纯图片扫描 PDF 全是 "第 N 页 OCR 退出码非 0（exit=1）"

A：大概率是 `chi_sim.traineddata` 找不到 / tessdata 目录权限问题。

1. 开诊断：`GET /rag/diag` 看 `rag_initializer.ocr.ocr_ready` 是否为 `true`，再看同级的 `diag_line` 里有没有「配置路径命中：…」——那行就是启动时打到控制台的那句 OCR 诊断原文
2. 把 tessdata 从 `C:\Program Files\Tesseract-OCR\tessdata\` 复制到 **`D:\tessdata\`**（无权限限制）
3. `application.yml` 同步改 `tessdata-dir: D:/tessdata`

---

## ❓ Q7：PDF 太大，处理到一半 "PDF 提取超时（> 120 秒）"

A：已实现**动态超时**（`RagInitializer.calcTimeout(pageCount)`）：`MAX(120s, 60s + 页数 × 15s)`。39 页扫描件自动放宽到 `60+585=645s` 约 10 分钟，足够完成每页 OCR。若还不够，把 `app.ocr.page-timeout-seconds` 调大到 180~240。

---

## ❓ Q8：答案里有 `**` 星号（Markdown 格式）

A：System Prompt 已加入约束"使用纯文本回答，不要使用 Markdown 格式"。如果偶发还是有残留，可在 `ChatController.java` 的答案返回前加一层正则清洗（把 `**text**` → `text`、`* text *` → `text`）。

---

# 📜 版本兼容性

项目已通过 **JDK 21 + Spring Boot 3.5.10 + Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.3** 严格配套验证：

- `spring-ai-client-chat` ✓ （ChatClient.Builder 存在）
- `spring-ai-advisors-vector-store` ✓ （SearchRequest 存在）
- `spring-ai-starter-vector-store-pgvector` ✓ （Spring AI 1.0.0+ 新命名）
- `spring-ai-pdf-document-reader` ✓ （PDFBox 3.1.x 兼容）
- **TokenTextSplitter.Builder 可用方法**：`withChunkSize(int)` / `withMinChunkSizeChars(int)` / `withMinChunkLengthToEmbed(int)` / `withMaxNumChunks(int)` / `withKeepSeparator(boolean)` / `build()`。**无 `withChunkOverlap()`**，如需 overlap 效果请自行在后续版本升级。

> ⚠️ **不要**随意升级版本，Spring AI Alibaba 每个小版本对 Spring AI / Spring Boot 有**硬编码依赖**。

---

# 🚀 后续优化方向

| 方向 | 说明 |
|---|---|
| 多轮对话上下文 | 引入对话历史管理，支持追问、澄清和多轮上下文连贯对话 |
| 流式响应（SSE） | 将 `/chat` 接口改造为 Server-Sent Events 流式输出，提升用户等待体验 |
| Java 层 Markdown 清洗 | 在答案返回前加正则，彻底去除 `**` / `*` / `#` 等 Markdown 残留 |
| 单元测试与集成测试 | 判分口径 + 检索模式收口 + 块级引用溯源 + 块号→页码的定位算法与印码规律 + 块号→章节号（标题形状闸门、大纲单调性、跨节给范围、顺序存疑就不给号）+ 库存工具返回值 + PHI 审计行（含「四列都不许出现原文」的守门断言、引用行「文件名必须排在最前」的守门断言、「页码对不上就明说、不许猜页」的守门断言、「印码偏移不一致就不给、歧义页跳过、正文数字不许当候选」的守门断言、「剂量/单位/血压值不许当章节标题」「认不进大纲的编号附近那块一律不给号」的守门断言）已有 115 条纯逻辑单测（`src/test/java/org/example/`，不连库不打模型）；核心链路（OCR 兜底、Tool Call 路由本身、向量检索、脱敏方法本身）仍缺 |
| 多格式文档支持 | 扩展对 Word、Markdown、Excel 等非 PDF 格式的解析与入库 |
| 多模型路由 | 根据问题类型自动选择最优大模型（简单问答用 qwen-turbo，复杂推理用 qwen-max） |
| 权限与审计 | 接入企业级 RBAC 权限控制，记录所有问答操作的审计日志 |
| 动态阈值调优 | 根据用户反馈自动调整相似度阈值（如用户多次点击"没用的引用"则提高阈值） |

---

# 📄 License

本项目仅供学习与医疗行业内部知识库验证使用，不得用于任何商业用途。

**免责声明**：本软件按"原样"提供，不提供任何明示或暗示的保证。作者不对因使用本软件而产生的任何直接、间接、附带、特殊或后果性的损害承担责任。使用者应自行承担使用本软件的全部风险。

本项目采用的技术栈（Spring Boot、Spring AI、PostgreSQL、Tesseract OCR 等）均遵循其各自的开源许可证。