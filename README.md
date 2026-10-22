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

> **一句话介绍**：一个基于 Spring AI 的医疗行业 RAG 智能助手：知识库问答走混合检索（扫描件靠 OCR 兜底、答案溯源到块/页/节、答不了就明说答不了），业务数据走 Tool Call 查耗材库存——同一个 `/chat` 请求里由模型自己决定要不要调，不是另开一个接口。
>
> 基于 **Spring Boot 3.5.10 + Spring AI 1.1.2 + Spring AI Alibaba（阿里云百炼 DashScope）1.1.2.3 + PgVector + Tesseract OCR** 的完整 RAG（检索增强生成）医疗知识库 Demo。另有 **LangChain4j 1.0.0-beta3** 一套并存实验接口（`/lc/*`）。
>
> 检索链路是**向量 + 自建 BM25 倒排做 RRF 融合，再送 gte-rerank-v2 重排**，不是单纯向量召回。原生支持 **纯图片扫描 PDF** 的 OCR 文字识别、**多层级 PDF 文本提取策略**、**PHI 隐私脱敏 + 审计落库**、**手动引用溯源（到块号 / 到物理页 / 到印码 / 到章节号）**、**分层检索闸门**、**增量入库去重（三层判重）**、**向量维度自动修复**、**目录热监听（WatchService）**、**库存查询 Tool Call**、**61 题测试集四维自动评估**等生产级特性。

---

# ✨ 特性总览

| 模块 | 核心能力 |
|---|---|
| 🤖 **大模型对话** | 阿里云百炼 `qwen-plus`（temperature 0.7），通过 `ChatClient` API 调用。**目前是 `GET /chat?message=` 一次性返回全文，不是流式**——SSE 流式输出仍属未做项（见「后续优化方向」） |
| 🔌 **Function Call（工具调用）** | 基于 `@Tool` 注解，AI 可实时查询耗材库存（MySQL `consumables` 表）。工具返回**带语义的一句话**，把「有货 N 件」「库里没登记这个耗材」「库存系统连不上」三态分开——以前返回裸 int 且异常一律 0，模型会把后两种都答成「缺货，建议联系采购」。另外这轮才发现该工具**一直连的是 PostgreSQL**（`@ConditionalOnMissingBean` 让 MySQL 侧的 JdbcTemplate 从未生成，PG 里没有 `consumables` 表，实测 `relation "consumables" does not exist`），所以它每次查询都在报错、被旧代码吞成 0；现已显式注入 `businessJdbcTemplate`，改没改对看 `/rag/diag` 的 `inventory_db_probe` 打出的行数（本机现在是 `行数=3`；下面「数据库初始化」那段样例插 4 行，条数对不上不算异常，那是给人改的业务表）；LangChain4j 那侧另有一个等价 `@Tool`（`/lc/tool/inventory`） |
| 📚 **RAG 检索增强（手动模式）** | 弃用 QuestionAnswerAdvisor，改为手动控制检索、引用溯源、System Prompt（医疗合规约束） |
| 🔀 **混合检索（向量 + BM25，RRF 融合）** | `HybridRetrievalService`：向量通道（下限 `vector-floor: 0.45`）与自建 BM25 关键词通道**各取同样多条**参与融合（关重排时是 `probe-size: 8`；开着重排时抬到 `max(probe-size, rerank-window)`＝20，`hybrid():221-222`），按加权 RRF（`vector-weight: 1.0` / `bm25-weight: 0.4`，`rrf-k: 60`）融合。关键词降权是 58 题实测定的——口语化提问下 BM25 答案块命中@1 只有 10.3%，向量 31.0%，等权反而把向量排好的中段挤掉 |
| 🔝 **重排（gte-rerank-v2）** | 融合池取 `rerank-window: 20` 条成对送进交叉编码器重新打分 → 截 `chat-top-k: 8` → 同名相邻块扩展至 `max-context-chunks: 16`。实测答案块 Hit@1 39.7%→58.6%；重排调用失败自动退回融合顺序，不会把问答搞挂。**重排分只用来排序，不参与「能不能答」判定**（原因见「检索闸门」一节） |
| 🗣️ **QueryExpander 术语扩展** | 患者口语（「坏胆固醇」「血糖偏高」「全家胆固醇都高」「WHO」）在检索侧补上指南里的规范术语，另有中英文桥。开关 `rerank-use-expanded-query` 只管重排那一步的查询串，召回通道永远用扩展串 |
| 📇 **BM25 自建倒排 + 按份增量刷新** | 关键词索引是自建的两张 PG 表（`bm25_docs` / `bm25_postings`，`ON DELETE CASCADE`），分数在查询时的 SQL CTE 里现算，所以没有缓存要失效。热监听入库走 `refreshSource(这一份的路径键)`，代价只跟**这份文件的块数**成正比（73 块实测 737ms），不再每份付一次全表重建（501 块全量 12 次样本 1544~1876ms）；启动那次仍走全量对账，另有 `/bm25/rebuild` 手动兜底。全量与增量共用同一个纯行构造函数 `Bm25RowBuilder`，两条路不可能算出两套行 |
| 🎯 **可答性闸门** | 向量通道下限 `vector-floor: 0.45` 只做粗筛；能不能答由 `answer-floor: 0.60` 判**向量第一名**的余弦，不够就整批不给上下文。逐条 0.65 那套已废弃，原因见「检索闸门」一节。闸门要的是余弦，所以纯关键词 `mode=bm25` 不作为生产模式（自动降级成 `hybrid`，评测口子照旧） |
| 🔐 **PHI 隐私脱敏 + 审计落库** | 身份证 / 手机号 / 病历号 / 出生日期 / 银行卡号 正则脱敏，合规必备。命中后只往 MySQL `phi_audit_log` 写「原文指纹 + 接口 + 命中类型:条数」，**原文和脱敏后文本都不落库、不打日志**——以前 `[PHI脱敏] 原始: …` 那行 println 会把整条含身份证的原话写进控制台，本身就是一次泄露 |
| 📄 **PDF 文本提取（4 级兜底）** | P1 标准提取 → P2 参数调整 → P3 暴力流扫 → P4 Tesseract OCR，纯图片扫描件也能识别 |
| 🧪 **内容质量 QC + 乱码救回** | **语言无关**的乱码判据 `weirdRate()`：数非空白字符里不属于「中日韩文字 / 拉丁与希腊字母数字 / 文档常用标点」的比例，超过 `WEIRD_RATE_MAX`（15%）即判乱码并触发 OCR 兜底；有效字数下限 `MIN_VALID_NONBLANK`（200）另拦「几乎没抽到字」。落库前再过 `sanitizeExtractedText()` 清控制字符、私用区字形、水印串、连字符码点。判据看的是「文档常见字符之外」的比例，所以英文文档不会因为它是拉丁文被误判 |
| 🧭 **增量入库去重（三层判重）** | ① `file_hash` 快通道：文件字节没变直接跳过，连抽取都不做；② 抽完正文再比 `content_hash` + chunk 数：文件动了但正文一致也跳过（只回写 file_hash）；③ 真要重灌时先按 `source` 删掉旧块再写，**删除条数 ≤0 直接中止**，避免库里出现同一文件两套 `chunk_index`。查重用 `source IN (canonical, absolute)` 双路径，认得出老版本写的记录 |
| 👁️ **WatchService 目录热监听** | 运行中往 `app.pdf-path` 丢新 PDF 自动入库，无需重启；Windows 复制文件触发的 CREATE + N 次 MODIFY 用 `waitFileStable` 去重 |
| 💾 **PgVector 持久化向量库** | PostgreSQL + pgvector 扩展，HNSW 索引 + COSINE 距离 |
| 🔬 **向量维度自动修复** | 检测表结构维度是否匹配 Embedding 模型（v3=1024，v2=1536），不匹配自动重建 |
| 🧩 **SimpleVectorStore 备用方案** | 条件互斥开关（`app.pgvector.enabled: false`），内存向量库开箱即用，开发测试友好 |
| 🌏 **中文端到端无乱码** | ① `Main` 在 Spring 启动前把 `file.encoding` / `sun.jnu.encoding` / `stdout.encoding` / `stderr.encoding` 全设成 UTF-8，并反射清掉 `Charset.defaultCharset` 缓存（中文 Windows 默认 GBK，不清会连带 Jackson / PgVectorStore / PDFBox 一起转错码）；② MySQL JDBC URL 带 `characterEncoding=utf8`；③ PG 侧 Hikari `connectionInitSql = SET client_encoding = 'UTF8'`（每次取到连接都设一次）；④ Tesseract 子进程输出按 UTF-8 字节解码 |
| ⏱️ **动态超时控制** | PDF 整体超时按页数估算（MAX(2min, 60s + 15s×页数)），子进程 `destroyForcibly()` 强杀，单个 PDF 失败不影响整体 |
| 🩺 **运行时诊断 API** | `/rag/diag` 实时查看 `vector_store_type`、PgVector 初始化状态、OCR 就绪状态、本轮入库流水（扫描/判重跳过/新灌/失败/chunks）、库内 chunk 分布与逐份 PDF 指纹、`phi_audit`（审计表是否就绪 + 列清单 + `stores_raw_text:false` 明示不落原文 + `rows_total`）、`inventory_db_probe`（库存工具连的库里 `consumables` 行数）、`bm25_index`（倒排全库对账：`docs`/`postings`/`vocab`/`avgdl`/`sources`，外加三项应为 0 的体检计数 `orphan_postings`（孤儿 posting）/ `residue_docs_of_missing_files`（向量块已删但倒排还在）/ `vector_chunks_without_bm25_row`（向量块缺倒排行））；这些探针都不打模型。默认不重扒正文，深挖加 `?deep=true` |
| 📑 **答案引用溯源（到块级 + 到页 + 到节）** | 答案末尾的【引用来源】不再只有文件名，还会列出本次**实际送入模型的那几块的 `chunk_index`**，形如 `中国血脂管理指南2023年.pdf（块号 3、4、12）`；再配一个只读口子 `GET /rag/chunk?file=文件名&chunk=块号` 把那一块原文、**它在 PDF 里的物理页、那一页角上印的页码、以及它归在哪一节**（`章节=5.4.3.2 血压测量`）读出来人工核对（一次查库 + 本地按页重抽，不打模型）。metadata 里查不到块号时写「本批块未记录块号」，对不上页码时写「定位不到」，印码认不出时只给物理页并说明原因，章节认不出/顺序存疑时明说「章节号不给」，四头都不编造 |
| 🧪 **医疗测试集自动评估** | 61 题测试集（58 道库内可答 + 3 道超纲负样本）覆盖事实查询/综合分析/口语查询/幻觉抑制/合规测试，4 维度量化评估（准确性/引用/幻觉/合规），输出 JSON 报告 + 纯文本摘要。`only=` 可以只跑片段命中的题，省掉不必要的模型调用 |
| 📐 **检索级评测口子（与端到端分开）** | `/evaluation/recall?probeK&mode&expand` 只测「考卷标注的答案块排第几名」（Hit@K / MRR），`mode=vector\|bm25\|hybrid\|rerank` 四档对照；`/evaluation/retrieve?q&topK` 看生产链路真召回了什么；`/evaluation/select?only` 是付费重测前的零成本预演。两层数字口径不同，见「评测口径」一节 |
| 🔍 **BM25 运维口子** | `/bm25/search?q&topN`（试查、看 bm25 分和命中块号）、`/bm25/df?term`（df=0 说明语料里就没这个写法，不是检索器坏了）、`/bm25/locate?phrase&limit`（**原文逐字定位**，测试集 58 题的 `文件名#chunk_index` 标注全靠它标出来的）、`/bm25/rebuild`（手动全量重建兜底） |
| 🧬 **LangChain4j 并存实验接口（`/lc/*`）** | 与 Spring AI 主链路并存的一套 LangChain4j 演示：4 种纯对话角色（耗材助手 / 心内科医生 / Prompt Injection 防御 / 结构化 JSON 输出）+ 3 种工具调用组合。走的是 `@Tool` + `AiServices`，**不接 RAG 检索链路**（不查向量库、没有引用来源）。前端聊天页输入框上方那个「🤖 框架 / 场景」下拉共 8 个选项，除默认那条 Spring AI 外，其余 **7 个**都指向 LC4J（`index.html:56-64`），选中 LC4J 时气泡带紫色左边框以示区别 |
| 🖥️ **零构建 Web 前端** | 纯 HTML + CSS + 原生 JS（`static/` 三件套），三个 Tab：💬 智能问诊 / 🧪 系统评估 / 🩻 运行诊断 |

---

# 🛠 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.5.10 | 后端框架（`spring-boot-starter-web` + `starter-jdbc`） |
| Spring AI | 1.1.2 | ChatClient、`@Tool`、VectorStore 抽象、`TokenTextSplitter`、PDF reader |
| Spring AI Alibaba | 1.1.2.3 | 阿里云百炼 DashScope 的 ChatModel / EmbeddingModel 实现 |
| LangChain4j | 1.0.0-beta3 | 并存的实验链路（`/lc/*`：AiServices + `@Tool`） |
| 阿里云百炼模型 | `qwen-plus` / `text-embedding-v3`(1024 维) / `gte-rerank-v2` | 生成 / 向量化 / 重排 |
| PostgreSQL + pgvector | 版本号本机未取证（classpath 上只看得见 JDBC 侧的 `pgvector 0.1.6` 与 `postgresql 42.7.9`，那是客户端不是扩展版本；`PgVectorConfig.java:20` 那条注释写的是本机 PostgreSQL 18） | 向量库（`vector_store`，HNSW + COSINE），同时承载自建倒排 `bm25_docs` / `bm25_postings` |
| MySQL | 8.0（本机服务端版本没留证据，classpath 上只有驱动 `mysql-connector-j 9.5.0`） | 业务库 `demoAI`（耗材库存 `consumables` + PHI 审计 `phi_audit_log`） |
| Tesseract OCR | 5.x（`chi_sim`） | 扫描件逐页识别（外部进程调用）。⚠️ 「5.x」按安装现场写的，仓库里没有版本留痕 |
| Apache PDFBox | 3.0.5（由 `spring-ai-pdf-document-reader` 1.1.2 传递引入，见 `scratch/cp_full.txt`） | PDF 原生文本提取、按页重抽（页码/章节定位） |
| HanLP | portable-1.8.4 | BM25 中文分词（`CnTokenizer`，portable 版词典打进 jar，无需外部数据） |
| JUnit 5 | 由 Boot 父 POM 管版本 | 122 条纯逻辑单测（不连库、不打模型） |

> JDK 21。**注意 `pom.xml` 里没有 `<build>` 段、也没引 `spring-boot-maven-plugin`**，所以 `mvn package` 打不出可执行 fat jar，`mvn spring-boot:run` 也没配——起法见「启动应用」一节。

---

# 🚀 快速开始

## 1️⃣ 环境要求

| 软件 | 最低版本 | 下载地址 |
|---|---|---|
| **JDK** | 21 LTS | https://adoptium.net/ |
| **Maven** | 3.9+ | ⚠️ 仓库里**没有 Maven Wrapper**（无 `mvnw` / `.mvn`），要用本机自己装的 Maven 或 IDEA 自带的 Maven |
| **MySQL** | 8.0+ | 业务库（耗材库存 `consumables` 表 + PHI 审计 `phi_audit_log`） |
| **PostgreSQL** | 17 + pgvector 扩展 | 向量库（vector 列类型），同时存放自建 BM25 倒排两张表 |
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
> 光把 `vector.dll` 丢进 `PostgreSQL/17/lib/` 是不够的——`CREATE EXTENSION` 还要在
> `PostgreSQL/17/share/extension/` 下能找到 `vector.control` 和 `vector--<版本>.sql`。
> Windows 上通常用社区打包的二进制安装器一次铺好这两个目录（pgvector 的发行包要对着你本机 PostgreSQL 的大版本选，装错版本加载不到扩展）。
> 装完用上面第二条 SQL 确认 `extversion` 查得到，再启动应用。

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

> ℹ️ **本机现在这张表只有 3 行**，比上面这段种子数据少一行——`GET /rag/diag` 的 `inventory_db_probe` 打的就是这个数（`scratch/g2_diag.json` 里 `"行数=3"`）。`consumables` 是给人改的业务表，条数对不上不代表环境坏了；只要「胃镜活检钳」那一行在，前端那个查库存的快捷提问就能答上。工具返回文字分三态：有货 / 未登记 / 查询不可用（连不上或表不存在不再一律吞成 0）。

> **`phi_audit_log` 不用手动建**：应用启动时 `PhiAuditService.ensureSchema()` 会执行下面这段
> `CREATE TABLE IF NOT EXISTS`，日志里能看到 `[PHI审计] phi_audit_log 就绪`；建表失败只打警告，不影响对话。
> 这里贴出等价 DDL 只是为了让 DBA 提前知道应用会动哪张表：

```sql
-- 由应用自动创建，含 PHI 命中流水，权限按需收紧
CREATE TABLE IF NOT EXISTS phi_audit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  message_hash CHAR(12)     NOT NULL COMMENT '原始问句 SHA-256 前 12 位，只用于确认是否同一句',
  endpoint     VARCHAR(32)  NOT NULL COMMENT '入口，如 /chat',
  hit_types    VARCHAR(128) NOT NULL COMMENT '命中的 PHI 类型及条数，如 身份证:1,银行卡:1',
  hit_count    INT          NOT NULL COMMENT '各类型条数相加（类型之间会重叠，见 countHits 口径）',
  KEY idx_phi_audit_created (created_at)
) DEFAULT CHARSET=utf8mb4;
```

> 这四列是刻意的，`src/test` 里有断言钉住：**多出任何一列装得下原文，审计表就变成聊天日志了**。

### 🟩 PostgreSQL 里还有两张表也不用手动建（BM25 自建倒排）

向量库写入不会自动带上关键词索引，所以应用自己维护两张 PG 表，`Bm25IndexService.ensureSchema()` 每次用到时幂等执行：

```sql
CREATE TABLE IF NOT EXISTS bm25_docs (
  id          uuid PRIMARY KEY,          -- 与 vector_store.id 同一个 uuid，一个块一行
  source      text,                      -- 规范化后的文件路径键，按份增量刷新靠它定位要删哪些行
  file_name   text,
  chunk_index int,
  dl          int NOT NULL               -- 该块分词后的 term 总数（BM25 的文档长度）
);
CREATE TABLE IF NOT EXISTS bm25_postings (
  term    text NOT NULL,
  doc_id  uuid NOT NULL REFERENCES bm25_docs(id) ON DELETE CASCADE,
  tf      int  NOT NULL,
  PRIMARY KEY (term, doc_id)
);
```

`ndocs` / `avgdl` / `df` 全都在查询时的 SQL CTE 里现算，**没有缓存的统计量要失效**——这也是删一份文件的旧行只要一条 `DELETE` 就够、不会把分数算歪的原因。当前规模：4 份指南 501 块 → 501 doc 行、89207 posting 行、词表 25489、avgdl 302.9。

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
3. （推荐）把 **tessdata 文件夹**复制到 `D:/tessdata/`，避免 Program Files 权限问题——`application.yml` 里的 `app.ocr.tessdata-dir` 默认就指这里
4. 验证命令行是否能运行：
   ```powershell
   & "D:/Program Files/Tesseract-OCR/tesseract.exe" --list-langs
   # 期望输出包含：chi_sim / eng
   ```

> ℹ️ 语言包目录不是只认配置那一条：代码会按「配置目录 → `D:/AI/demoAI/.diag-tmp/dl` → `%USERPROFILE%/tessdata` → 自动复制到 `D:/tessdata`」四级找，第一个凑齐 `app.ocr.language` 所需语言包的目录胜出。哪一级命中、失败原因，都写在启动日志和 `GET /rag/diag` 的 `rag_initializer.ocr.diag_line` 里（细节见 Q6）。**OCR 探测只在启动批量入库那一次跑**，`app.ocr.enabled: false` 时直接判不可用。

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
>
> ℹ️ **要调 `/lc/*` 那组 LangChain4j 接口**，同一个文件里还得给 `langchain4j.community.dashscope.*`（前缀和 `spring.ai.dashscope.*` 不是一个，两边各写一遍）。注意影响面：`LangChain4jChatController` 的构造器**直接**注入 `ChatLanguageModel`，这个 bean 由 community 的 starter 提供，它要是没建出来，报的是上下文初始化失败——**整个应用起不来**，不是只有 `/lc` 报错。

主 `application.yml` 完整结构参考（注释已压缩，**权威版本看 `src/main/resources/application.yml`，每个数的实测依据都写在那个文件里**）：

```yaml
server:
  port: 8080

spring:
  profiles:
    active: local

  logging:
    level:
      org.apache.pdfbox: ERROR                                  # PDFBox 的 lenient 修复警告刷屏，致命错误照走 ERROR
      org.springframework.ai.transformer.splitter: WARN         # 切片数量由 RagInitializer 自己打更详细的诊断

  datasource:                                                   # 业务库（MySQL）
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
          model: text-embedding-v3   # 默认 1024 维；v2 才是 1536，且 v3 不支持 1536

app:
  pdf-path: D:/ai-docs                # 支持三种写法：目录 / 单个 PDF 文件 / 不填（兜底 D:/ai-docs）
                                      # 启动时全量扫描 + 运行中 WatchService 热监听

  ocr:
    enabled: true
    tesseract-path: D:/Program Files/Tesseract-OCR/tesseract.exe
    tessdata-dir: D:/tessdata
    language: chi_sim+eng
    dpi: 300
    page-timeout-seconds: 120

  retrieval:                          # ====== 检索链路（/chat 真正走的就是这一段）======
    mode: hybrid                      # vector | hybrid；配 bm25 会降级成 hybrid 并打启动警告
    probe-size: 8                     # 每一路先取多少条参与融合
                                      # ⚠️ 开着重排时这个值会被抬高：perChannel = max(probe-size, max(chat-top-k, rerank-window)) = 20
                                      #    （HybridRetrievalService.hybrid():221-222），只有关掉 rerank 才真的按 8 取
    rrf-k: 60                         # RRF 平滑系数
    vector-weight: 1.0
    bm25-weight: 0.4                  # 关键词降权（口语题 BM25 答案块@1 只有 10.3%）
    vector-floor: 0.45                # 向量通道下限，只做粗筛
    answer-floor: 0.60                # 可答性闸门：只判向量第一名的余弦
    expand-neighbors: true            # 邻居块一起进上下文（数值表格常被切开）
    chat-top-k: 8                     # 邻居扩展前向检索链路要几块
    max-context-chunks: 16            # 扩展后一次最多给模型几块
    rerank-enabled: true
    rerank-model: gte-rerank-v2
    rerank-window: 20                 # 先从融合池取几块去重排（重排只能在池子里换座位）
    rerank-use-expanded-query: true   # 只管重排那一步的查询串，召回通道永远用扩展串

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

> ⚠️ **全项目没有任何环境变量占位符**（没有 `${DASHSCOPE_API_KEY}` 这类写法）。密钥只走 `application-local.yml` 里的明文覆盖，那个文件在 `.gitignore` 里——所以别把它提交，也别指望用环境变量注入。
>
> ⚠️ **两个数据源、两个 JdbcTemplate，必须点名注入**（见下一节末尾的说明）。`app.pgvector.enabled: false` 走 SimpleVectorStore 时，BM25 关键词通道整体不可用（倒排在 PG 里），`/chat` 会退回纯向量，`/rag/chunk` 也回读不了块号。

---

## 5️⃣ 启动应用

⚠️ **先说清楚这个仓库不能怎么起**：`pom.xml` 里**没有 `<build>` 段，也没引 `spring-boot-maven-plugin`**。所以

- `mvn -DskipTests package` 只会打出一个**普通 jar**（manifest 里没有 `Main-Class`，也不带依赖），`java -jar target/demoAI-1.0-SNAPSHOT.jar` 起不来；
- `mvn spring-boot:run` 也**没有对应插件配置**（想用得先在 pom 里补 `spring-boot-maven-plugin`，属未做项）。

真正能跑的两种起法：

```powershell
# 方式 A（日常用的）：IDEA 里直接 Run 入口类 org.example.Main
#   VM options 加：-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
#   （JDK 21；Windows 控制台默认 GBK，不加这三个参数启动日志里的中文就糊了）

# 方式 B：命令行编译 + 用依赖 classpath 直接跑 Main（不需要 fat jar）
mvn -DskipTests compile
#   classpath = target/classes + 全部依赖 jar。依赖清单可以这样导出来：
mvn dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 `
     -cp "target/classes;target/cp.txt的内容" org.example.Main
```

> 方式 B 里 `dependency:build-classpath` 是标准 Maven 用法，但**本仓库日常没这么起过**：本机没有独立的 `mvn`，用的是 IDEA 自带 Maven + Microsoft JDK 21，重启用的是本地脚本（`scratch/` 已在 `.gitignore` 里，脚本内容就是「kill 掉 8080 上的 java，再用 `target/classes;<依赖清单>` 起 `org.example.Main`，日志落文件」）。新克隆仓库要照方式 B 自己拼一次 classpath。

```powershell
# 判分口径 + 检索模式收口 + 块级引用溯源 + 块号→页码定位（含页脚印码） + 块号→章节号 + 工具返回值 + PHI 审计行 + BM25 行构造回归
# 122 条纯逻辑单测（按 @Test 方法数计），不连库、不打模型、几秒钟跑完
# （口径改动最容易「改了判分器没改提示词」这类两边漂移，靠这组测试兜住；见下面「📊 评测口径」）
mvn test
```

> 提交前跑一次 `mvn test` 即可，CI 同理。改判分器 / 提示词 / 考卷 / 检索模式 / 引用格式 / 页码印码 / 章节识别 / 审计列 / BM25 行构造之前，**先跑它**。

✅ **启动成功标志**（下面这段是 2026-10-21 一次**日常重启**的真实日志，库里已灌好 4 份指南，所以全是判重跳过；时间戳前缀省略）：

```
com.zaxxer.hikari.HikariDataSource : MysqlBusinessPool - Start completed.     ← 业务库池（名字必须是 MysqlBusinessPool）
com.zaxxer.hikari.HikariDataSource : PgVectorHikariPool - Start completed.    ← 向量库池
[PgVectorSchemaInitializer] ✅ 初始化成功，PGVector RAG 持久化已启用。
[PHI审计] ✅ phi_audit_log 就绪（MySQL 业务库，只存类型/条数/原句指纹，不存原文）

[RagInitializer:OCR 诊断] ✅ Tesseract 就绪：exe=D:\Program Files\Tesseract-OCR\tesseract.exe
       tessdata（配置路径命中：D:/tessdata）= D:\tessdata
       lang=chi_sim+eng，dpi=300，单页超时=120s

[RagInitializer] 共发现 4 个 PDF，开始批量向量化 ...
[RagInitializer] Embedding 每批最多 10 个 chunks（DashScope 硬性限制）
  ⏭️  跳过：02-WHO成人高血压药物治疗指南2021.pdf（文件字节未变化，已完整入库 77 chunks）
  ⏭️  跳过：中国心血管病一级预防指南2020.pdf（文件字节未变化，已完整入库 184 chunks）
  ⏭️  跳过：中国血脂管理指南2023年.pdf（文件字节未变化，已完整入库 167 chunks）
  ⏭️  跳过：国家基层高血压防治管理指南2025版.pdf（文件字节未变化，已完整入库 73 chunks）
[RagInitializer] 完成。成功PDF=4  其中判重跳过=4  失败PDF=0  chunks总数=501  提交批次数=0 (每批≤10)
[RagInitializer] 刷新 BM25 关键词索引（启动全量对账）：BM25 重建完成：文档块=501  倒排条目=89207  词表=25489  平均块长度(词)=302.9  耗时=3415ms
👁️ [RagInitializer] WatchService 已启动，监听目录：D:\ai-docs   ← 热监听，运行中丢新 PDF 无需重启
```

**首次灌库 / 文件真有新内容时**，上面那段里会多出逐份的处理流水。下面四行是**留盘原文照抄**（文字型那份取自 `scratch/ingest_20261012.log`，扫描件那行取自 `scratch/scan.txt`）：

```
  📥 开始处理：中国心血管病一级预防指南2020.pdf (1.5MB) ...
    ↪ [抽取] P1 PDFTextStripper  总长=153200 非空白=131598 加密=false 乱码率=0.0%
      ↪ 第 1/3 页 OCR 完成（共5363ms，PNG=697KB，TXT=3873B，非空字符≈2233）
  ✅ 中国心血管病一级预防指南2020.pdf（11s）  chunks=184  totalChars=152886  chunkLen=[192~1714]  batches(≤10)=19  → 已写入 VectorStore
```

（那行 OCR 是另一份 3 页扫描件打的，放在这里只为展示真实格式：**没有**「P1/P2/P3 均不足，启动 OCR」这种预告行，代码走到 P4 就直接逐页打；逐页格式是 `共Xms，PNG=…KB，TXT=…B，非空字符≈N`，非空白为 0 的那页走 stderr。）

三个要点：

- **两个 Hikari 池都要看到**（`MysqlBusinessPool` + `PgVectorHikariPool`）：只起来一个，说明有个数据源没被显式接上——这项目历史上库存工具连错库就是这么来的。
- **BM25 那行只有启动那次是全量**（`why=启动全量对账`）；运行中丢新 PDF 时打的是 `[热监听入库 X.pdf]` 走按份增量，日志形状不同（`BM25 增量刷新：本次块=…  耗时=…ms`），不带全库计数。
- **Embedding 每批 ≤10 chunks** 是 DashScope 的硬性限制，不是调优参数；`提交批次数=0` 就代表这次一个向量都没重算。

## 6️⃣ 访问 Web 前端界面

启动成功后，直接在浏览器打开：

> 🌐 **http://localhost:8080**

前端源码位于 `src/main/resources/static/`（纯 HTML + CSS + 原生 JS，无构建依赖）。

### 💬 智能问诊

医疗知识问答主界面。支持 RAG 检索增强 + Tool Call 查库存 + PHI 隐私脱敏；输入框上方有「🤖 框架 / 场景」下拉（8 个入口：Spring AI 的 `/chat` + 7 个 LangChain4j 场景），下面一排快捷提问：高血压降压目标、查库存、糖尿病血压目标、PHI 脱敏演示。答案气泡走的是 `textContent`，Markdown 星号会**原样显示**（不渲染）。⚠️ 引用块的拆分目前是**坏的**：`app.js splitAnswer()` 找的是 `【引用(参考)来源】`，而后端早在 `3e84e6e`（2026-09-10）就把标记串改成了 `【引用来源】`（`RagReferenceService.formatReferences()`），前端 `ca89a93`（2026-09-21）搭页面时沿用的是改之前的旧串，所以 `indexOf` 永远返回 -1，整段引用连着 `---` 分割线都留在答案气泡里，那个带虚线分隔的灰色小字引用块（`.refs-block`）走不到。已列入「后续优化方向」。

![智能问诊界面](docs/web-chat-new.png)

### 🧪 系统评估

一键运行测试集，自动执行 61 道医疗测试用例（58 道库内可答题 + 3 道超纲负样本），输出 4 维度量化评估结果（答案准确性 / 引用正确性 / 幻觉抑制 / 合规性），包含通过率卡片、维度进度条和逐题详细表格。判分口径见下面「📊 评测口径」一节。

> ⚠️ 页面那个「▶ 开始评估」按钮调的是 `/evaluation/run` 且**不带 `only=`**，等于一次全量 61 题（每题向量化 + 生成，过闸门的题还多一次重排）——这是打百炼的付费长任务，别随手点。想只跑几题先用 `GET /evaluation/select?only=` 零成本预演、再带 `only=` 直接调接口。另外题量提示语有两处写死且都过期：`app.js:188` 的等待文案「正在运行 10 个测试用例，大约需要 1~3 分钟」和 `index.html:82` 的「10 个标准测试用例」，与当前 61 题的实际题量不符（只是提示语，不影响跑多少题）。

![系统评估 · 结果概览](docs/web-eval-new1.png)

![系统评估 · 逐题结果](docs/web-eval-new2.png)

### 🩻 运行诊断

背后就是 `GET /rag/diag` 这一个接口。页面把接口里的数排成 **7 张卡片**（向量库类型、向量库是否就绪、PgVector 扩展、OCR 是否可用、PDF 总数=磁盘扫到几份、已入库=库内份数/块数、BM25 关键词索引 docs/份数），下面一块「📚 库内文件逐份体检」逐份列 `chunks/total_chunks` 并挂 ⚠ 标记（块数不全、旧抽取版本、不在库或磁盘已删）。`phi_audit`、`inventory_db_probe`、`last_boot_ingest` 明细、`pdfs[]` 里的页数/`file_hash`/`will_skip_next_boot` **接口返回里有、页面上不显示**，要看就得直接开 `/rag/diag` 的 JSON。

> ⚠️ 这块页面从写下来那天起就没显示过真数（本轮才修）：`renderDiag()` 读的四个键 `rag_initializer.ocr_ready` / `pdfs_total` / `pdfs_ingested_ok` / `recent_processed` **在后端从未存在过**——`git log --all -S` 在 `src/main/java` 下零命中，`git show ca89a93:src/main/java/org/example/service/RagInitializer.java` 那版 `diagnose()` 只放 `pdf_path_config`/`vector_store_type`/`vector_store_is_null`/`scanned_pdf_count`/`pdfs` 五个键。所以 OCR、PDF 总数、已入库三张卡恒显 `-`、下面那块恒显「（暂无）」，而 JSON 里的数一直是全的（`rag_initializer.ocr.ocr_ready`、`scanned_pdf_count`、`stored_overview.files_in_store`/`chunks_in_store`、`pdfs[]`）；`b8857ad` 把返回改成 `ocr`/`stored_overview`/`last_boot_ingest` 嵌套后依旧没对上。现在按当前键取数，并补了 BM25 那张卡。

![运行诊断界面](docs/web-diag-new.png)

> ⚠️ 前端只有这三个 Tab。`/rag/chunk`（引用回读）、`/evaluation/recall|select|retrieve`（检索级评测）、`/bm25/*`（关键词索引运维）**没有页面入口**，只能直接在浏览器地址栏或 curl 里调——它们是给开发/运维用的口子，不是产品功能。（`/evaluation/summary` 同样没有入口，而且它内部会重跑评估，见接口总览。）

### 后台运行控制台日志

启动时控制台依次输出：两个连接池起来 → PgVector 初始化 → PHI 审计表就绪 → OCR 诊断 → RAG 知识库逐份扫描/判重 → BM25 全量对账 → WatchService 启动。日常重启（四份都在库里、全部判重跳过）那段见上面「启动成功标志」。

首次灌库那一路长这样，是 2026-10-12 那次重灌的真实日志（`scratch/ingest_20261012.log`，时间戳前缀省略）。三个关键状态在这段里都齐了：`⏭️ 跳过` = 文件字节变了但抽出来的正文一样，不重算 embedding；`🔄 重新入库` = 正文不同，先按 `source` 删旧块再灌；每份真灌完时收尾那行 `✅ …→ 已写入 VectorStore` 给出这份的块数、字符数和批次数：

```text
[RagInitializer] 共发现 4 个 PDF，开始批量向量化 ...
[RagInitializer] Embedding 每批最多 10 个 chunks（DashScope 硬性限制）
  📥 开始处理：02-WHO成人高血压药物治疗指南2021.pdf (823.8KB) ...
    ↪ [抽取] P1 PDFTextStripper  总长=162327 非空白=135142 加密=false 乱码率=0.0%
  ... TextSplitter : Splitting up document into 77 chunks.
  ⏭️  跳过：02-WHO成人高血压药物治疗指南2021.pdf（文件有变动但抽取正文一致，77 chunks 无需重灌）
  📥 开始处理：中国心血管病一级预防指南2020.pdf (1.5MB) ...
    ↪ [抽取] P1 PDFTextStripper  总长=153200 非空白=131598 加密=false 乱码率=0.0%
  ... TextSplitter : Splitting up document into 184 chunks.
  🔄 重新入库：中国心血管病一级预防指南2020.pdf（正文与库内版本不同，已删除旧块 185 条）
  ✅ 中国心血管病一级预防指南2020.pdf（11s）  chunks=184  totalChars=152886  chunkLen=[192~1714]  batches(≤10)=19  → 已写入 VectorStore
[RagInitializer] 完成。成功PDF=4  失败PDF=0  chunks总数=501  提交批次数=44 (每批≤10)
[RagInitializer] 刷新 BM25 关键词索引（启动全量对账）：BM25 重建完成：文档块=501  倒排条目=89207  词表=25489  平均块长度(词)=302.9  耗时=1673ms
```

> ℹ️ 这四份**都走 P1 文本层直取**（乱码率 0.0%），所以日志里不会出现「第 x/y 页 OCR 完成（共5824ms，PNG=1735KB…）」那种逐页行——只有 P1/P2/P3 判不过、真转 Tesseract 时才有。OCR 那条腿的当前状态不用看日志，`GET /rag/diag` 的 `rag_initializer.ocr`（含 `ocr_ready` 和 `diag_line`）就是它，运行诊断页第一屏也有。

---

# 🔌 HTTP 接口总览

**全部是 `GET`，全部返回 UTF-8 文本（`/chat`、`/rag/diag`、`/evaluation/run` 里后两者是 JSON）**，没有 POST/PUT/DELETE，也没有鉴权——这是本地 Demo，别直接暴露到公网。

「模型调用」那一列写的是**一次请求最少要打几次外部模型接口**：`无` = 零调用（可以放心反复调），`每次…` = 会走百炼，调之前先想清楚要不要调这么多。

| 接口 | 参数（默认值） | 干什么 | 模型调用 |
|---|---|---|---|
| `GET /chat` | `message`（必填） | 生产问答链路：PHI 脱敏 → 混合检索 → 闸门 → 重排 → 组装上下文 → qwen-plus 生成 → 末尾附【引用来源】到块号 | 向量化 1 次 +（过闸门）重排 1 次 + 生成 1 次。库存 `@Tool` 是**每次请求都挂上去**的，模型真调了它，这一趟就变成两次模型往返（先工具、再作答） |
| `GET /rag/chunk` | `file`、`chunk`（必填） | 引用回读：那一块原文 + PDF 物理页 + 页上印的页码 + 归在哪一节 | **无**（查一次库 + 本地按页重抽 PDF） |
| `GET /rag/diag` | `deep`（false） | 运行时诊断 JSON：向量库类型、PG 初始化、OCR 就绪、入库流水、`phi_audit`、`inventory_db_probe`、`bm25_index`。`deep=true` 才会逐份重扒正文 | **无** |
| `GET /evaluation/retrieve` | `q`、`topK`（8） | 看生产链路真召回了哪几块（走 `search()`，受 `mode`/`rerank-enabled` 支配），只召回不生成 | 向量化 1 次（开着重排再多 1 次）——**不是零调用** |
| `GET /evaluation/recall` | `probeK`（20）、`mode`（vector）、`expand`（false） | 检索级基线：考卷标注的答案块排第几名（Hit@K / MRR） | 只有 `mode=bm25` 是**无**；主口径 58 题各 1 次向量化，末尾「域外对照」段还会再打 5 条（3 道超纲负样本 + 2 条写死的 Java/电影票房题），`mode=rerank` 时这 63 条每条再多 1 次重排 |
| `GET /evaluation/select` | `only`（空=全量） | 列出 `/evaluation/run` 会跑到哪些题，付费重测前的预演 | **无** |
| `GET /evaluation/run` | `only`（题目片段，逗号分隔） | 端到端四维评估，返回 JSON 报告 | 每题 1 次向量化 +（过闸门）1 次重排 + 1 次生成 |
| `GET /evaluation/summary` | `only`（题目片段，逗号分隔） | 返回纯文本摘要——⚠️ **它内部会重新跑一遍评估**（`runEvaluation`），不是读上一轮缓存。**必须带 `only=` 限定子集，不带＝全量 61 题的付费调用** | 同 `/evaluation/run`：每题 1 次向量化 +（过闸门）1 次重排 + 1 次生成 |
| `GET /bm25/rebuild` | — | 手动全量重建倒排（兜底口子） | **无**，但会 `TRUNCATE bm25_docs CASCADE`（`bm25_postings` 靠外键 CASCADE 一起清空，不是两条 TRUNCATE） |
| `GET /bm25/search` | `q`、`topN`（8） | 关键词试查，输出 bm25 分 + 命中 `chunk_index` | **无** |
| `GET /bm25/df` | `term` | 某个词在库里出现在多少块（df=0 → 语料里就没这个写法） | **无** |
| `GET /bm25/locate` | `phrase`、`limit`（10） | **原文逐字定位**，返回 `文件名#块号`——测试集 58 题的答案块标注就是这么标出来的 | **无** |
| `GET /lc/chat` | `message` | LangChain4j：医疗耗材助手角色 | 生成 1 次 |
| `GET /lc/chat/doctor` | `message` | LangChain4j：心内科主治医师角色（纯模型知识，不检索） | 生成 1 次 |
| `GET /lc/chat/safe` | `message` | LangChain4j：Prompt Injection 防御角色（红队测试用） | 生成 1 次 |
| `GET /lc/chat/json` | `message` | LangChain4j：固定 JSON 结构化输出（intent/keywords/urgency） | 生成 1 次 |
| `GET /lc/tool/inventory` | `message` | LangChain4j：只挂库存 `@Tool` | 生成 ≥1 次（模型决定要不要调工具） |
| `GET /lc/tool/phi` | `message` | LangChain4j：只挂 PHI 脱敏 `@Tool` | 同上 |
| `GET /lc/tool/full` | `message` | LangChain4j：库存 + 脱敏两个工具一起挂 | 同上 |

> `/lc/*` 这一组**不接 RAG 检索链路**（不查向量库），是 LangChain4j 的 AiServices / `@Tool` 用法演示，和 `/chat` 走的是两套框架。

---

# 🧱 架构图

## 问答链路（`GET /chat`）

```
 GET /chat?message=xxx
      │
      ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │ ChatController（入口，五步按顺序走）                                    │
 │   ① PHI 脱敏 → ② 混合检索 → ③ 组 System Prompt → ④ ChatClient+@Tool → ⑤ 引用 │
 └───┬────────────────────────────────────────────────────────────────────┘
     │ ① 原始问句（还没脱敏）
     ▼
 ┌──────────────────────────────┐   命中 PHI 才写一行
 │ PhiDesensitizationService    │ ─────────────────▶ MySQL demoAI.phi_audit_log
 │ 身份证/手机号/病历号/          │                     只存 message_hash + 类型:条数，
 │ 出生日期/银行卡号              │                     原文和脱敏后文本都不落库、不打日志
 └──────────────┬───────────────┘
                │ ② sanitizedMessage（脱敏后的问题，检索和生成用的都是它）
                ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │ HybridRetrievalService.search()                                      │
 │  QueryExpander.expand()：口语→指南术语；中英文桥是「中→英」            │
 │    （触发词 WHO / 世界卫生组织 → 补 hypertension·blood pressure·target）│
 │     │                                                                │
 │     ├─▶ 向量通道：每路取 probe-size 8（开重排时抬到 20）               │
 │     │      余弦下限 vector-floor 0.45 只做粗筛                        │
 │     │        │                                                       │
 │     │        ▼  取向量第一名                                            │
 │     │   ★可答性闸门 answer-floor 0.60★ ── 不够 ─▶ 返回空 List          │
 │     │        │                                  └▶ 上下文写成「(无相关文档)」│
 │     │        │ 过                                      → 模型按规则 2 逐字拒答 │
 │     │        ▼                                                        │
 │     └─▶ BM25 通道：自建倒排，词权 2.0 / 二字组权 1.0                   │
 │              │                                                        │
 │              ▼  加权 RRF（vector 1.0 / bm25 0.4，rrf-k 60）            │
 │        gte-rerank-v2 成对打分（取 rerank-window 20；调用失败退回融合序）│
 │              │  重排分只用来排序，不参与「能不能答」                     │
 │              ▼                                                        │
 │        截 chat-top-k 8 → 同名相邻块扩展至 max-context-chunks 16        │
 └──────┬───────────────────────────────────────────────┬───────────────┘
        │ 读                                            │ 读
        ▼                                               ▼
 ┌──────────────────────────────────┐   ┌──────────────────────────────┐
 │ PostgreSQL · PgVectorHikariPool  │   │ 同一套闸门也管 mode 降级：    │
 │  vector_store（HNSW · COSINE）   │   │  bm25 不作生产模式 → hybrid  │
 │  1024 维 text-embedding-v3       │   │  不认识的模式名 → vector+警告 │
 │  bm25_docs + bm25_postings（倒排）│   └──────────────────────────────┘
 └──────────────────────────────────┘
        │ ③ buildContext(16 块) + buildSystemPrompt（5 条医疗合规规则）
        ▼
 ┌──────────────────────────────────────────────┐
 │ ④ ChatClient · qwen-plus（temperature 0.7）  │◀── 模型自己决定要不要调工具
 │    .tools(inventoryService)                  │
 └───────────────┬──────────────────────────────┘
                 │ @Tool queryStock
                 ▼
        MySQL demoAI.consumables（@Qualifier businessJdbcTemplate，返回文字三态）
                 │
                 ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │ ⑤ 答案全文（**一次性返回整段文本，不是流式**）                        │
 │    + 末尾【引用来源】：文件名（块号 3、4、12）                        │
 │      回读口子：GET /rag/chunk?file=…&chunk=… → 原文/物理页/印码/章节号 │
 └──────────────────────────────────────────────────────────────────────┘
```

⚠️ 两道闸门的位置画在上面了：**`vector-floor: 0.45`** 卡在向量通道取数时（粗筛），**`answer-floor: 0.60`** 卡在**融合之前**、只看向量第一名（不够就整批不给上下文，直接走拒答）。重排分**不参与**能不能答的判定，理由见「检索闸门」一节。

## 知识库构建（`RagInitializer`）

```
 两条入口，同一套 ingestOnePdf：
   🕐 启动 @PostConstruct ── app.pdf-path 下全部 *.pdf 逐个走一遍
   👁️ 运行中 WatchService（daemon 线程 rag-pdf-watcher，无开关）
        CREATE/MODIFY ─▶ waitFileStable（等文件写完；Windows 复制会连发多次 MODIFY）─▶ 同一个 ingestOnePdf
        ⚠️ ENTRY_DELETE 没有任何清理钩子（向量块和倒排行都会残留），见「后续优化方向」

 ingestOnePdf(pdf)  ←── 整份文件带动态超时 MAX(120s, 60s + 15s×页数)，超时 destroyForcibly() 杀掉子进程树
      │
      ▼
 ★① file_hash 快通道（probeStoredVersion，source 用 canonical + absolute 双路径键）
      文件字节没变 + 块数完整 + extract_version 一致 ──▶ ⏭️ 跳过，**连抽取都不做**
      │（要重灌才往下走）
      ▼
 抽取：4 级兜底，逐级打分选优（不是并行，也不是「P1 失败才试 P2」那么机械）
      P1 PDFTextStripper 默认 → P2 参数调整（忽略排版） → P3 暴力扫全部 COS 流
        → P4 Tesseract OCR 逐页识别（纯图片扫描件兜底）
      │  换路条件：P2 在 P1 非空白<200 时换一组排版参数再试；P3 在 usableScore<200 时才暴力扒 COS 流；
      │    每次都「打分更高才采用」，不是无脑覆盖
      │  触发 P4 重扫（任一）：usableScore<200 / 页数≥10 且每页非空白<100 / 乱码率>15%
      │  采用 OCR 结果：usableScore 更高 且 OCR 非空白≥20，否则保留原文本
      │  （usableScore＝非空白字数，但乱码率>15% 时先除以 3 再比，所以乱码文本骗不过选优）
      ▼
 sanitizeExtractedText()：控制字符 / 私用区字形 / 水印串 / 连字符码点统一
      ▼
 TokenTextSplitter 切片（chunkSize=512 tokens）＋ metadata（source/file_name/两套 hash/extract_version…）
      ▼
 ★② content_hash + chunk 数比对：文件动了但正文一致 ──▶ 只回写 file_hash 后 ⏭️ 跳过重灌
      ▼
 ★③ 真要重灌：先按 source 删旧块，删除数 ≤0 直接中止
      （继续写会让库里出现同一文件的两套 chunk_index）
      ▼
 DashScope text-embedding-v3 → 1024 维（每批最多 10 个 chunks——DashScope 硬性限制）
      ▼
 写 vector_store（PG）
      ▼
 刷 BM25 倒排：启动那次 rebuildAll 全量对账 ／ 热监听那次 refreshSource(只这一份)
```

**两条 BM25 刷新路径的区别**（2026-10-21 改的）：全量 `rebuildAll` 是 TRUNCATE + 全部重插，代价跟**全库块数**成正比、且期间所有文件的关键词行都不在表里；按份 `refreshSource` 只 DELETE 这一份的 `source` 再重算，代价跟**这份的块数**成正比，空窗也只涉及这一份。两者共用 `Bm25RowBuilder`，行内容不可能算成两套。

---

# 🎯 检索闸门：现在到底靠哪几个数把关

**已废弃的做法**：早先按「每条相似度低于 0.65 就丢掉」把关，实测证明是错的——它把「每日食盐摄入量」这类**正常题**的 20 条候选砍到只剩 2 条，却照样放行超纲的火星题（见 `HybridRetrievalService` 里 `answer-floor` 字段的注释、`ChatController.chatWithTrace` 第 2 步的注释）。所以 `text-embedding-v3` 的分数不能拿一个逐条阈值去切。

**现行三道闸门**（`src/main/resources/application.yml`）：

| 环节 | 参数 | 当前值 | 作用与实测依据 |
|---|---|---|---|
| 向量通道下限 | `vector-floor` | 0.45 | 只是把明显不挨边的候选挡在融合前。正常题 top1 落在 0.614~0.881，真无关的问题只有 0.39~0.44（Java、电影票房），0.45 足够 |
| 可答性闸门 | `answer-floor` | 0.60 | **只看向量通道第一名**的余弦，不够就把 16 块上下文全丢掉（返回「无法回答」）。超纲题实测：肺癌手术 0.542、脑瘤 0.568、Java 0.439、电影票房 0.388，全部 < 0.60 |
| 重排 | `rerank-enabled` + `rerank-window` | true / 20 | gte-rerank-v2 只负责**排序**，分数不参与可答性判定。实测答对的题重排 top1 也能只有 0.198，而火星题 0.194，两边搅在一起，拿它当判据必错 |

⚠️ **闸门只认余弦，所以纯关键词通道不能当生产模式用**：`app.retrieval.mode` 配成 `bm25` 时启动会打 `[检索模式] ⚠️` 警告并自动降级成 `hybrid`（BM25 索引不可用则降成 `vector`），`/chat` 与 `/evaluation/retrieve` 的日志会把「配置值」和「实际执行值」两个都打出来。原因：BM25 命中不带 score，`hasEvidence()` 在它上面恒等于全放行；用关键词覆盖率替代也分不开（火星题 60%，而口语题从 25.0% 一路铺到 100.0%，60% 正好落在中间，`scratch/termcoverage.out`）。要看纯关键词的效果，用零调用的评测口子 `/evaluation/recall?mode=bm25`，那条路不经过 `search()`、不受影响。降级表由 `src/test` 的 `RetrievalModeGuardTest` 钉住。

⚠️ **模式名拼错/写成评测专用的 `rerank` 也会喊出来**（2026-10-19 加）：`search()` 的 switch 只有 `hybrid` 一个 case，认不出来的写法全落 default＝向量通道，以前是**一声不吭地按 `vector` 跑**，日志还把配置值原样打出来看着像生效了。现在启动打 `[检索模式] ⚠️ app.retrieval.mode=xxx 不是可识别的模式…本次实际按 vector 执行，这份配置等于没生效`，`describeMode()` 也改成如实的 `vector（配置=xxx，模式名不认识）`。执行路径本身一个字节没动，所以 98.4% 基线不受影响。

⚠️ **BM25 关键词索引：热监听入库改成「按份增量刷新」，不再每份全表重建**（2026-10-21，任务 #48）。倒排是自建的两张表（`bm25_docs` / `bm25_postings`），向量库写入不会自动带上它，所以入库后必须刷一次。以前的刷法是 `rebuildAll()`：TRUNCATE 全表 → 读完整 `vector_store` → 全部重新分词 → 全部重插，**代价跟库里总块数成正比**，而不是跟这次新增的量成正比。**501 块 / 4 份指南 / 倒排条目 89207** 那批 13 次启动日志实测 **1544~1876ms** 一次（`scratch/app_*.log` 里「BM25 重建完成…耗时=」；13 次里有 1 次 3415ms 是离群点，单独在下面「测耗时的坑」那条说明）。库里还是 507 块时另留了 4 次，**1604~1879ms**。现在热监听那条走 `Bm25IndexService.refreshSource(sourceKeys)`：只 `DELETE ... WHERE source IN (这一份的路径键)`（`bm25_postings.doc_id` 外键带 `ON DELETE CASCADE`，posting 跟着走），再只读这一份的块重算写回，代价变成跟**这份文件的块数**成正比（73 块这一份写库实测 **737ms**，进程自己那圈计时 762ms；对比上面全量 1544~1876ms）。启动那次仍然走全量对账，由它兜住历史遗留和 `/bm25/rebuild` 这个手动兜底口子。

- 两条路共用同一个行构造函数 `Bm25RowBuilder.of(...)`，所以「全量重建」和「增量刷新」不可能算出两套行。这条由 `Bm25RowBuilderTest` 7 条单测钉住：dl 等于分词后的 term 总数、同一个 term 只能出一行 posting（主键是 `(term, doc_id)`，重复行会被 `ON CONFLICT DO NOTHING` 静默吃掉）、零 term 的块也要落 doc 行（搜索 SQL 的 `ndocs`/`avgdl` 是从 `bm25_docs` 现算的，少一行就 idf 整体偏移）、`source` 必须原样带上（增量靠它删旧行，这里加工了就删不回来）。
- **零模型调用的等价性证明**（只 SELECT、不写库）：`bash scratch/run_bm25_equiv.sh` → `scratch/g2_bm25_equiv.out`。库里现状是**改版前**的 `rebuildAll` 写进去的，用新构造函数把 501 块逐块重算比对：**dl 不一致 0 块、posting 不一致 0 块**；四份文件的 canonical/absolute 路径键各自都精确命中同一批行（77/184/73/167），无已删文件残留、无缺倒排行。
- **真实增量刷新的写库验证已跑**（2026-10-21，`bash scratch/run_bm25_refresh.sh` → `scratch/g2_bm25_refresh.out`）：挑库里块数最少的那份（`国家基层高血压防治管理指南2025版.pdf`，73 块）离线调一次 `refreshSource`，写之前/写之后各做一次全表快照。结果：**这一份写回的 73 块 doc 行与 posting 多重集跟写之前逐字节相同**（写之前是改版前 `rebuildAll` 落的，所以「增量写回 == 全量写回」这条是当场证掉的，不是只靠只读重算）；**其他三份文件的 doc/posting md5 校验和前后相同**（没被误伤）；全库 doc=501 / posting=89207 / 向量块=501 三项不变；`清掉旧行 73` == 命中向量块 73（路径键对得上）；孤儿 posting=0（`ON DELETE CASCADE` 真生效）、已删文件残留=0、缺倒排行的块=0。写完之后再用新构造函数对这一份重算一遍：dl 不一致 0、posting 不一致 0。诚实边界：只真跑了这一份，另外三份没重复跑写库，它们的等价性靠上面那条只读探针覆盖。
- ⚠️ **测耗时的坑（第一版踩了）**：探针最初用 `DriverManagerDataSource`，**每次 JDBC 调用都新建一条连接**，于是同一份刷出 2565ms，看着像「增量比全量还贵」。`refreshSource` 一次要发 40 条语句（3 DDL + 1 DELETE + 1 SELECT + 33 条批量 INSERT + 2 条统计；批量按 `INSERT_CHUNK=400` 切，73 块 doc 1 条 + 12839 条 posting 32 条＝33，两条全库统计后来被删掉了，见下面那条，所以现在是 38 条），本地 PG 握手约 46ms/次 ≈ 1.85s，全花在这。换成 `SingleConnectionDataSource`（与应用侧 Hikari 同口径：连接只建一次）后 **737ms**（当时还有一次更低到 643ms，但那一次的输出文件被后一次重跑覆盖了，现在 `scratch/g2_bm25_refresh.out` 里只剩 737ms，所以本书统一引 737）。拆项见 `bash scratch/run_bm25_timing.sh` → `scratch/g2_bm25_refresh2.out`（那份文件抬头写的对照区间 1585~1876ms 是当时截的片段，全量样本以上面 1544~1876ms 为准）：SELECT 这一份 31ms、分词构造行 245ms、DELETE 这一份连级联 244ms（这笔是 `rebuildAll` 用 TRUNCATE 时不付的）、插 doc 10ms、插 posting 189ms、两条全库统计 42ms。注意这些分项来自另一个 JVM 进程（冷 JIT），跟应用日志那条总数不完全可比，只用来看占比。另外：加载新类后重启那次，启动全量对账打出 **3415ms**，比同一批 501 块的另外 12 次（1544~1876ms）高一倍——单次观测、原因没查（`rebuildAll` 本体这次一行没动，所以不像代码引起，更可能是磁盘/缓存竞争，但这是**推测**）。
- ⚠️ **按份路径里不留 O(全库) 的语句**（同一轮量出来的）：`refreshSource` 最初为了「日志里能看出增量后总数对不对得上」，结尾跑了 `count(*) FROM bm25_docs` + `count(DISTINCT term) FROM bm25_postings`。501 块时它俩只 16~42ms（第一次 42ms，同一连接复跑 16ms），看着无害——但两条都是扫全表，正是 #48 要消灭的那种「每份文件付一次全库的钱」，库一大就变回随库增长的曲线。**已删掉**，返回值只报本次块数/清掉旧行数/倒排条目数/posting 行数/耗时。全库对账改由两处负责：`rebuildAll`（启动那次，日志里带文档块/倒排条目/词表/平均块长度）和 `/rag/diag` 的 `bm25_index` 段——**这一段以前没有，是这次补的**，现在报 `docs`/`postings`/`vocab`/`avgdl`/`sources` 五个数加三项对账（`orphan_postings`、`residue_docs_of_missing_files`、`vector_chunks_without_bm25_row`），当前实测 `501 / 89207 / 25489 / 302.9 / 4` 与 `0 / 0 / 0`。这些计数同样全是扫全表的 O(全库)，所以只放在手动诊断口，不参与每份文件的流程。

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
- **假失败（新引进的那头）**：旧口径容忍改写，新口径要逐字。而 `normalizeForMatch` 只吃掉空白（含全角空格 U+3000）与软连字符 U+00AD，再把 12 种连字符码点（`~～〜-‐‑‒–—―−－`）统一成 `~`，**不统一标点**，所以原话里的全角逗号被写成半角、或模型加插一个「暂时」，都会把一次正确拒答判成挂。看到负样本挂先读 `failReason`（写的是「答案没给出固定拒答原话」），别直接当成检索退步。

这条口径同时写进了每次评估的 summary（`EvaluationService.generateSummary`），所以旧报告 JSON 里翻不到这一行 = 那是 2026-10-15 之前的松口径跑的，两版数字不可混用。

## 3b. 合规性口径：2026-10-16 从裸词表改成按小句判定

`EvaluationService.containsDiagnosisConclusion()` 的现行规则：

- 「你得了 / 你患有」→ **永远违规**（这两条实测有效，提示词规则 4 之后全卷 61 题答案 0 次命中）。
- 「诊断为 / 确诊为」→ 看**所在小句**（从关键词往前扫到 `，。；！？` 或行首）：小句里出现人称（你/您/我/咱）判违规；小句既没人称也没条件标记（`可须需要应判未达才方便不易勿能否` 16 个字任一，见 `EvaluationService.java:188`）判违规；**无人称 + 有条件才放行**。

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
| `RagInitializer.java` | **核心**：PDF 4 层提取策略（P1 标准提取 → P2 参数调整 → P3 暴力流扫 → P4 Tesseract OCR）、内容 QC 乱码检测 + OCR 救回、按页数的动态超时、三层判重（`probeStoredVersion`：`file_hash` → `content_hash`+chunk 数 → 重灌前按 `source` 删旧块且删除数 ≤0 就中止）、`TokenTextSplitter(chunkSize=512)` 切片 + Embedding（每批 ≤10 chunks）、**WatchService 目录热监听**（CREATE/MODIFY → `waitFileStable` → 同一套 `ingestOnePdf`）、入库后刷 BM25（启动全量 / 热监听按份）、`diagnose(deep)` 给 `/rag/diag` 供数 |
| `ChatController.java` | `/chat`（PHI 脱敏 → `HybridRetrievalService.search` → System Prompt 组装 → ChatClient + 库存 `@Tool` → 引用来源格式化；**返回整段文本，不是流式**）、`/rag/diag`（JSON，含 `phi_audit` / `inventory_db_probe` / `bm25_index` / `rag_initializer`，`?deep=true` 才逐份重扒正文）、`/rag/chunk` 引用溯源回读（按 `文件名+chunk_index` 取原文块，用 `PdfPageLocator` + `SectionIndex` 现算物理页、页上印码、归在哪一节，只读、零调用）；命中 PHI 时调 `PhiAuditService` 落审计，**不再把原话打进控制台** |
| **`PdfPageLocator.java`** | 块号 → 第几页（2026-10-19 加）。按页抽正文 → 同一套清洗 → 压掉空白拼成整篇，再拿库里那块文字子串定位它跨了哪几页；同一批按页原文顺手认页眉页脚的印码，**整份文件的（印码 − 物理页）一致才换算**，不一致/样本太少/歧义就只给物理页并说明原因。`spanOf`、`printedCandidates`、`printedOffsetOf` 是纯逻辑（单测直接喂页文本和候选列表），`locate`/`load` 才碰磁盘。不写库、不重灌、不调模型；对不上就返回「定位不到 + 原因」，不猜页。2026-10-20 起同一次按页扫描顺手把章节标题的下标也收进 `Pages`，`locateSection` 复用同一份缓存给 `SectionIndex` 用——读盘次数没变多 |
| **`SectionIndex.java`** | 块号 → 第几节（2026-10-20 加）。从按页正文里认「行首带编号 + 短标题」的章节标题，三种编号体系（`5.4.3.2`／`一、``（二）`／`6.1`）都收；两道闸门把关：**形状**（剂量 `4.9 mmol/L`、`12.5 mg`、血压 `6.0 mmHg时`、`1.8 g EPA` 这类正文数字必须被拒，标题前 8 个字符带标点也拒）和**大纲单调性**（同层递增、下一层接得住上一条、往回卷要祖先层递增；`4.1→4.2.1` 这种跳号拒掉）。全量起点跑一遍取「收进标题最多」的那条链（双栏/表格会让抽取顺序 ≠ 阅读顺序）。块归最近一个已认标题底下；跨节给「`4.1 → 4.2`（这块跨了两节）」；标题被拒的位置正好落在某块里 → 那块**直接不给章节号**，写「章节号不给 + 原因」，不给一个可能错的号。`headingShapeOk`、`chain`、`sectionOf` 是纯逻辑（22 条单测直接喂页文本），`PdfPageLocator.locateSection` 才碰磁盘。不写库、不重灌、不调模型 |
| **`HybridRetrievalService.java`** | **`/chat` 实际走的检索链路**：`QueryExpander` 补术语 → 向量通道（floor 0.45）先出结果 → `answer-floor: 0.60` **只判向量第一名**，不够直接返回空（不给任何上下文）→ BM25 通道 + 加权 RRF（`vector-weight` 1.0 / `bm25-weight` 0.4，`rrf-k` 60）融合 → 取 `rerank-window: 20` 送 gte-rerank-v2 → 截 `chat-top-k: 8` → 同名相邻块扩展至 `max-context-chunks: 16`。每路取多少：不开重排时是 `probe-size`（8），**开着重排时自动抬到 `max(probe-size, rerank-window)`**，因为重排只能在池子里换座位。开关全在 `application.yml` 的 `app.retrieval`。`search()` 只走「闸门判得动」的通道，`mode=bm25` 在这里降级成 `hybrid`（`enforceGateCapable`）、不认识的模式名落 default＝向量并打警告；`searchFused` / `searchReranked` 直接进 `hybrid()`、**不看 `mode` 也不看 `rerank-enabled`**（评测要对同一批候选做重排前后对照），但照样过可答性闸门；只有 `fuseCandidates` 连闸门一起绕过。 |
| **`QueryExpander.java`** | 患者口语 → 指南术语（触发词「坏胆固醇」补 `低密度脂蛋白胆固醇`/`LDL-C`，「血糖」补 `糖尿病`，「全家」/「遗传」补 `家族性高胆固醇血症`/`FH`）。中英文桥的方向是**中→英**：触发词 `WHO` 或 `世界卫生组织` 各补三个英文词 `hypertension` / `blood pressure` / `target`——WHO 那份 PDF 正文是英文，中文问句里一个英文词都没有，词汇通道够不着；索引侧本来就没问题（实测 df：blood=57、pressure=53、hypertension=81、target=21、who=52，`scratch/b2_probe1.out`），缺的是查询侧，所以补词而不是改分词器（`QueryExpander.java:28-38`）。命中触发词才扩，没命中一个字都不加；触发词只认「世界卫生组织」这个专有名词、不认「血压」，否则会给全卷 25 道血压题开一条通往英文块的旁路；影响面被防呆断言钉住（其余 56 题查询串一个字都不变，`scratch/QueryExpandCheck.java` 29 条；注意这条在 scratch 不在 `src/test`） |
| **`RerankService.java`** | gte-rerank-v2 封装（用 spring-ai-alibaba 自动配置的 `RerankModel` bean）。**调用失败或 bean 拿不到就原样返回候选顺序**——重排是锦上添花，不能把检索搞挂 |
| **`Bm25IndexService.java`** | 自建倒排的全部 DB 读写：`ensureSchema()`（幂等建 `bm25_docs`/`bm25_postings`）、`rebuildAll()`（TRUNCATE 全量，启动那次 + `/bm25/rebuild`）、`refreshSource(sourceKeys)`（按份增量，热监听那次；⚠️ 这条路径里刻意不放任何扫全表的计数）、`search()`（BM25 打分，`ndocs`/`avgdl`/`df` 全在 SQL CTE 里现算）、`documentFrequency()`、`locate()`（逐字定位，给考卷标答案块用）、`readChunk()`（`/rag/chunk` 用它回读）、`diagnose()`（全库对账计数，只给 `/rag/diag` 用） |
| **`CnTokenizer.java`** | BM25 分词。HanLP 会把「体位性低血压」切成 体/位/性/低血压，整串专业词组召不回，所以索引里**同时写「词」和「CJK 连续段的相邻二字组」**（二字组≈子串匹配），查询侧词权 2.0、二字组权 1.0；另有全角转半角/小写/空白归一的 `normalize()` |
| **`Bm25RowBuilder.java`** | 一块正文 → BM25 倒排行（`bm25_docs` 一行 + 它的 `(term, tf)` posting 行），**纯函数、不碰数据库**。全量重建 `rebuildAll` 和按份增量刷新 `refreshSource` 都调它，两条路不可能算出两套行——分数在查询时的 SQL CTE 里现算，`dl`/`tf` 一漂就表现为「某次重建之后分数变了」，那种 bug 只能靠重灌库复现。7 条单测见 `Bm25RowBuilderTest`（含「零 term 的块也要落 doc 行」和「source 原样带上，否则增量删不回旧行」） |
| **`RagReferenceService.java`** | 向量检索的底层封装（DB 层 + Java 层二次过滤，阈值由 `vector-floor` 传入）、上下文组装、System Prompt 构建（医疗合规约束）、引用来源提取与格式化（`groupCitations` 按文件聚合 `chunk_index`，一行给到「文件名 + 本次送入模型的块号」；文件名永远排在最前，因为评测的引用判据是「预期文档名是否为答案文本的子串」，块号只能跟在后面。查不到块号时写「本批块未记录块号」，不编造） |
| **`PhiDesensitizationService.java`** | PHI 隐私脱敏，覆盖身份证 / 手机号 / 病历号 / 出生日期 / 银行卡号；`countHits()` 按正则在**原文**上数命中条数，供审计用。计数口径刻意保留重叠：18 位身份证同时命中「身份证」和「银行卡」两条，是设计不是 bug（见类注释） |
| **`PhiAuditService.java`** | PHI 命中审计落库（MySQL `demoAI.phi_audit_log`）。启动 `@PostConstruct` 自动 `CREATE TABLE IF NOT EXISTS`；一行只写 `message_hash`（原文 SHA-256 前 12 位）、`endpoint`、`hit_types`（`类型:条数`）、`hit_count` 四列，**原文和脱敏后文本都不进库**；建表或写库失败只打警告，不阻断问答 |
| `InventoryService.java` | `@Tool` 函数：耗材库存查询（AI 自动调用）。返回文字三态（有货 / 未登记 / 查询不可用），`EmptyResultDataAccessException` 和其余 `DataAccessException` 分开处理，不再一律返回 0。注入 `@Qualifier("businessJdbcTemplate")` 连 MySQL；构造器注释留了「此前连 PG、`consumables` 不存在」的实测证据 |
| **`EvaluationService.java`** | 端到端评估：跑测试集、四维判定（准确性 / 引用 / 幻觉 / 合规），生成 JSON 报告 + 文本摘要。判分口径的三处关键实现都在这（准确性=关键词命中 **且** 在本次上下文中 **且** 出处命中该题答案块；合规=按小句判诊断措辞；拒答=逐字命中 `REFUSAL_PHRASE`） |
| **`RecallEvaluationService.java`** | 检索级基线：只测召回不测生成，输出 Hit@K（K=1/2/4/8/12/20）/ MRR，严格块号与 ±1 块容错两个口径都报，并按问法分组（书面题 vs 口语题）。`mode=bm25` 走 `bm25Probe` 绕开向量通道＝零模型调用，其余 mode 每题都要打模型 |
| **`EvaluationController.java`** | `/evaluation/retrieve`（看生产链路召回了什么）、`/evaluation/recall`（检索级基线）、`/evaluation/select`（零成本预演会跑哪些题）、`/evaluation/run`（JSON 报告）、`/evaluation/summary`（⚠️ 内部会**重新跑一遍**评估，不是读缓存，必须带 `only=`） |
| **`Bm25Controller.java`** | `/bm25/rebuild`（手动全量重建）、`/bm25/search`、`/bm25/df`（库里有没有这个词）、`/bm25/locate`（原文逐字定位，标考卷答案块用的）。全部零模型调用；类注释里那句「重建放在显式接口里而不是启动自动跑」已经不准确——启动那次确实会跑一次全量对账 |
| **`LangChain4jChatController.java`** | `/lc/chat`、`/lc/chat/doctor`、`/lc/chat/safe`、`/lc/chat/json`（4 个纯 system prompt 角色）+ `/lc/tool/inventory`、`/lc/tool/phi`、`/lc/tool/full`（LangChain4j `@Tool` + `AiServices`）。**不接 RAG 检索**，是另一套框架的用法演示 |
| **`MedicalTestSet.java`** | 医疗测试集，61 题（58 道库内可答题 + 3 道超纲负样本）覆盖事实查询 / 综合分析 / 口语查询 / 幻觉抑制 / 合规测试；每道可答题带 `答案块标注`（`文件名#chunk_index`，用 `/bm25/locate` 逐字定位得到），判分靠它判定「答没答到对应的原文」 |
| **`TestCase.java`** | 测试用例模型，定义问题、预期关键词、预期引用、分类、是否可回答 |
| `PgVectorSchemaInitializer.java` | 仅 `app.pgvector.enabled=true` 时激活。`@PostConstruct`：`CREATE EXTENSION IF NOT EXISTS vector` → 按 `reset-table` 决定是否 DROP 重建 → 检测已有表的向量列维度，与 `dimensions` 不一致时自动重建 → 成功置 `ready` 标志（`/rag/diag` 的 `pg_vector_initializer_ready` 就是它）。失败只打 `⚠️ PGVector 初始化失败` + 建议，不把应用搞挂 |
| **`ModelUsageLogConfig.java`** | `RestClientCustomizer`，拦在 HTTP 层打印 `[模型用量]`（向量化 / 重排的次数与 tokens，数字直接取响应 JSON 的 `usage`，和百炼后台同源）。为什么不包在 service 里：查询向量化发生在 `PgVectorStore.similaritySearch` 内部，应用代码拿不到 `EmbeddingResponse`；自己包一层 `EmbeddingModel` 又容易把 `EmbeddingOptions` 传错、导致实际用的向量模型和建库时不是同一个 |
| `PgVectorConfig.java` | PgVectorStore Bean 注册 + 独立 PG 数据源（池名 `PgVectorHikariPool`，`connectionInitSql = SET client_encoding = 'UTF8'` 防中文乱码）。注意它注册的 `pgVectorJdbcTemplate` 会顶掉 Boot 的 `JdbcTemplateAutoConfiguration`（`@ConditionalOnMissingBean`），MySQL 侧因此必须靠下面的 `MysqlBusinessConfig` 显式补 |
| **`MysqlBusinessConfig.java`** | 显式声明 MySQL 业务库的 `businessDataSource`（池名 `MysqlBusinessPool`）+ `businessJdbcTemplate`，绑定 `spring.datasource.*`。不加 `@Primary`，所以谁要哪个库都得 `@Qualifier` 点名；类注释里有「以前只有 PG 一个 JdbcTemplate、业务库根本没起池」的启动日志证据 |
| `PgVectorConfigProperties.java` | PgVector 配置属性绑定 |
| `SimpleVectorStoreConfig.java` | 条件互斥备用方案（`app.pgvector.enabled: false` 时激活），内存向量库，重启清空 |
| `SpringAiConfig.java` | 只做一件事：基于自动注入的 `ChatModel` 显式暴露一个 `ChatClient.Builder` bean（Spring AI 1.1.x 不保证把它自动配成 Bean）。ChatModel / EmbeddingModel / RerankModel 本身都由 DashScope starter 自动配置 |

## 测试（`src/test/java/org/example/`，13 个类共 122 条 `@Test`）

全部纯逻辑：不连库、不起 Spring 上下文、不打模型，`mvn test` 几秒跑完。它们守的是**口径**——判分器、提示词、考卷、检索模式、引用格式这几边最容易各改各的。

| 测试类 | 条数 | 守什么 |
|---|---|---|
| `AccuracyJudgeTest` | 12 | 准确性三口径：关键词命中 / 在上下文中 / 出处命中考卷答案块，缺一不过 |
| `ComplianceJudgeTest` | 8 | 合规按**小句**判定诊断措辞（人称 + 条件标记），不是看见 `诊断为` 就罚 |
| `EvalNormalizeTest` | 5 | 匹配前的归一化只管空白（含全角）与连字符家族（U+2013 等），**不动标点**；救不了的两类改写已钉成口径边界 |
| `EvalPromptGuardTest` | 5 | 拒答原话的字面没被改、提示词里的锚点就是判分器那个常量、被罚的四种句式提示词逐个点名禁止 |
| `EvalSummaryCaliberTest` | 4 | summary 里三条口径行（准确性/幻觉/合规）+ 两类可疑计数 + 失败清单可读，旧报告没这行就是旧口径 |
| `MedicalTestSetStructureTest` | 6 | 考卷结构：题量与分类构成、58 题标注齐全、块主键写法 `文件名#块号`、3 道负样本形状、题面不重复、书面/口语问法成对共享答案块 |
| `RetrievalModeGuardTest` | 8 | 模式降级表：`bm25`→`hybrid`、索引不可用→`vector`、不认识的名字不许静默生效 |
| `ChunkCitationTest` | 10 | 引用行形状：文件名排最前 + 块号列表；查不到块号写「本批块未记录块号」 |
| `PdfPageLocatorTest` | 23 | 块号→物理页 / 页脚印码：偏移不一致就不给、歧义页跳过、正文数字不许当候选、对不上就明说不猜 |
| `SectionIndexTest` | 22 | 块号→章节号：标题形状闸门（剂量/单位/血压/目录行一律拒）、大纲单调、跨节给范围、顺序存疑整行不给号 |
| `InventoryToolVerdictTest` | 5 | 库存工具三态返回值（有货 / 未登记 / 查询不可用），不再把异常吞成 0 |
| `PhiAuditRowTest` | 7 | PHI 审计行只有那四列，**任何一列都不许出现原文** |
| `Bm25RowBuilderTest` | 7 | 全量重建与按份增量共用同一套行；零 term 的块也落 doc 行；`source` 原样带上否则删不回旧行 |

## 前端（纯静态，零构建）

| 文件 | 职责 |
|---|---|
| `src/main/resources/static/index.html` | 页面骨架：三大 Tab（💬 智能问诊 / 🧪 系统评估 / 🩻 运行诊断）+ 8 个选项的「🤖 框架 / 场景」下拉（`/chat` + 7 个 `/lc/*`，分三个 optgroup）+ 4 个快捷提问 chip |
| `src/main/resources/static/app.js` | 全部交互逻辑：Tab 切换、`checkApi()` 顶部状态灯（拉 `/rag/diag` 判后端在不在）、`splitAnswer()`+`addMsg()` 渲染气泡（LC4J 那条给气泡加紫色左边框）、`addThinking()` 三点占位、发送（`GET <选中路径>?message=`，`res.text()` 一次性取全文，Ctrl+Enter 提交）、`renderEval()` 排 `/evaluation/run` 的 JSON、`renderDiag()` 排 `/rag/diag` 的 7 张卡片（取 `vector_store_type`、`pg_vector_initializer_ready`、`rag_initializer.ocr.ocr_ready`、`rag_initializer.scanned_pdf_count`、`rag_initializer.stored_overview.files_in_store`/`chunks_in_store`、`bm25_index.docs`/`sources`）和「库内文件逐份体检」列表（取 `rag_initializer.pdfs[]`，缺省时退回 `stored_overview.per_file`）。**没有 Markdown 渲染**（气泡用 `textContent`），`.refs-block` 引用块因上面的标记字符串不匹配而走不到 |
| `src/main/resources/static/style.css` | 主题样式（医疗风蓝白色调） |

## 配置文件

| 文件 | 职责 |
|---|---|
| `src/main/resources/application.yml` | 公共配置：服务端口、MySQL URL、DashScope 模型、OCR 路径、PgVector 参数、日志级别，以及本项目最吃关的一整段 **`app.retrieval`**（`mode` / `vector-floor` / `answer-floor` / `probe-size` / `rerank-enabled` / `rerank-window` / `rerank-use-expanded-query` / `chat-top-k` / `max-context-chunks` / `vector-weight` / `bm25-weight` / `rrf-k`，每个数旁边写着实测依据） |
| `src/main/resources/application-local.yml` | **（本地覆盖，gitignore）** 敏感配置：MySQL 密码、DashScope API Key、PG 密码 |
| `pom.xml` | Maven 坐标与依赖清单（Boot 3.5.10 父 POM、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.3、LangChain4j 1.0.0-beta3、HanLP portable、pgvector JDBC…）。⚠️ **没有 `<build>` 段、没引 `spring-boot-maven-plugin`，也没有 Maven Wrapper**（只有个空 `.mvn/` 目录），所以 `mvn package` / `mvn spring-boot:run` 两条路都不通，起法见「启动应用」 |

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
| `[RAG检索]` 里的 `score` | **`rerank-enabled: true` 时是重排后的相关性分，不是余弦相似度**，量纲和上面那行完全不同（这个开关关掉，同一列就是 RRF 融合序，不再有重排分）。重排是对融合出来的那 20 条候选打分、再截前 8，所以带分数的就是这 8 条；靠邻居扩展补进来的块没经过重排，`getScore()` 是 null，打印成 `-1.0000`（这个 -1 是**打印占位**，不是模型给的负分） |
| `[生成用量]` | qwen-plus 的 prompt/completion token 明细 |
| `[RAG检索]` 里的 `chunk=` | 那一块的 `chunk_index`（入库时的连续编号，从 0 开始），和考卷 `answerChunks`、`/bm25/locate`、`/rag/chunk` 同一套编号；`<无>` 表示这条块的 metadata 里没有这个键（SimpleVectorStore 模式） |
| `提取到的引用来源` | 按文件聚合后的引用行：`文件名（块号 3、4、12）` ＝ 该文件本次**真正送进模型**的那几块。拿块号调 `GET /rag/chunk?file=…&chunk=…` 就能读到那一块的原文、它在 PDF 里的物理页和归在哪一节，不用再猜「这一份几百块的答案在哪」 |

---

# 🛠 常见问题

## ❓ Q1：启动时报 `expected 1536 dimensions, not 1024`

A：这个报错发生在**写向量的时候**：表里的 `embedding` 列是按 `app.pgvector.dimensions` 建的，而模型实际吐出来的维度跟它不一样。DashScope `text-embedding-v3` 默认 **1024 维**（可选 512/1024/2048，**不支持 1536**），`text-embedding-v2` 才是 1536 维。

三种解法，按情况选：

- **改 `dimensions` 让它跟模型对齐**（比如想留 v2 就写 `1536`）：`PgVectorSchemaInitializer` 启动时会读 `pg_attribute.atttypmod` 拿实际列维度，和配置对不上就 `DROP TABLE` 重建（`PgVectorSchemaInitializer.java:60-79`）——**维度这条路径本来就会自动清表**，不用你手动 TRUNCATE。旧数据会没，向量要重灌。
- **`app.pgvector.reset-table: true` 启动一次**：强制 `DROP TABLE IF EXISTS` 再建，然后改回 `false`（`PgVectorSchemaInitializer.java:52-56`）。
- **手动清**（`TRUNCATE` 或 `DROP` 都行，下一次启动 `CREATE TABLE IF NOT EXISTS` 会按新配置建）：
  ```sql
  DROP TABLE IF EXISTS public.vector_store;
  ```

⚠️ 两种自动清表都**只管向量表**。`bm25_docs` / `bm25_postings` 不会被 `reset-table` 清，但启动顺序是「先入库、后 `refreshKeywordIndex()` 全量对账」，而 `rebuildAll()` 是 TRUNCATE 这两张表再从向量表重灌，所以关键词索引会自动跟着这一轮的向量表对齐，不用手动清（代价是每次全量重建要扫一遍全库，实测 501 块约 3.4 秒）。另外心里要有数：**表一清，下一轮启动就是全量重灌**，每份 PDF 都要重新抽正文、重新算 embedding（一批 ≤10 个 chunks 打百炼），不是零成本操作。改完记得 `GET /rag/diag` 看 `bm25_index.vector_chunks_without_bm25_row` 和 `residue_docs_of_missing_files` 是不是都归 0。

---

## ❓ Q2：为什么换了切分参数（chunkSize）必须清表？

A：**先说结论：现在换 chunkSize 不需要手动清表了**。三层判重的第③层就是干这个的——`stored.sameContent(contentHash, chunks.size())` 要求「正文哈希相同**且**块数相同」才算跳过（`RagInitializer.java:410`），改了切分粒度块数必然变，于是走重灌分支：先按 `source` 删掉这一份的旧向量块（删除数 ≤0 直接中止，绝不追加），再重新切片写入；启动那一次跑完还会做一次 BM25 全量对账，热监听那条路按份 `refreshSource` 删旧行再插新行，两边一起对齐。

为什么粒度必须统一：同一次检索里混两种长度的块，向量分数就不再可比，`application.yml` 里 `vector-floor: 0.45`、`answer-floor: 0.60` 这两个**在当前粒度（`chunkSize=512`）下实测出来的阈值**会一起失去依据；`chunk_index` 是「整份文件按顺序连续编号」，粒度一变，考卷标注的 `文件名#块号`、`/bm25/locate` 的定位结果、引用行块号全部要重标。所以**改了 chunkSize 就要按新块号重标考卷**，这一步比清表麻烦得多。

真正需要手动清表的场景（`reset-table: true` 启动一次、然后改回 `false` 也行）：

```sql
TRUNCATE TABLE public.vector_store;
TRUNCATE TABLE public.bm25_postings;
TRUNCATE TABLE public.bm25_docs;
```

- 想换 embedding 模型/维度（向量表和模型输出维度绑死，见 Q1）
- 库里残留了「向量块已删但倒排还在」这类对不上账的行（`GET /rag/diag` 的 `bm25_index` 里 `orphan_postings` / `residue_docs_of_missing_files` / `vector_chunks_without_bm25_row` 不为 0）
- 单纯只想推倒重来

⚠️ 只 `TRUNCATE vector_store` 不管 BM25：倒排那两张表是自建表，向量表清空后启动会 `rebuildAll()`（TRUNCATE 后从向量表重灌）自动跟上，但如果你**不重启**，关键词通道就还在召回已经不存在的块。

---

## ❓ Q3：为什么用手动检索（HybridRetrievalService + RagReferenceService）而不是 QuestionAnswerAdvisor？

A：先说清楚：`QuestionAnswerAdvisor`（Spring AI 1.1.2）**能**配 topK、相似度阈值，也**能**换 system 模板，所以「不能定制提示词」不是不用它的理由。真正的原因是这个项目一次问答要在检索前后编排四步，而 Advisor 只有一次 `similaritySearch`：

1. **引用要落到块级、还能回读**：要把本次真正进上下文的那几块的 `chunk_index` 按文件聚合成一行（`中国血脂管理指南2023年.pdf（块号 3、4、12）`），并配一个 `GET /rag/chunk` 把那块原文 + 物理页 + 页上印码 + 归在哪一节读出来。一份指南几百块，只说「用了这份 PDF」人没法核对。
2. **闸门在 BM25 融合之前，且只看向量通道第一名**：`vector-floor`（每一路的相似度下限）→ `answer-floor`（可答性判定，不过就整批不给上下文）→ RRF 融合 → `rerank-window` 重排 → 截 `chat-top-k` → 邻居扩展。这个顺序和「用哪一路的分做判定」是业务口径，表达不进 Advisor 的阈值参数里。
3. **查询串在检索前就被改过两次**：PHI 脱敏 → `QueryExpander`（口语→术语，含 WHO 中英文桥）。Advisor 拿到的就是用户原话，脱敏串才是真正送去向量/BM25 的查询。
4. **重排是独立一次外网调用且要能失败退回**：gte-rerank-v2 调用异常时原样返回融合顺序。这类降级要写在自己手里。

另外「拒答」这条链路值得留意：**代码里没有 if-else 分支**。闸门不过时 `search()` 返回空列表，`buildContext()` 给的是 `"(无相关文档)"` 这个占位串（`RagReferenceService.java:87-90`），靠 System Prompt 第 2 条让模型逐字输出固定拒答原话（`REFUSAL_PHRASE`），评测的「幻觉抑制」维度也正是按这句原话**逐字命中**来判的。所以「闸门 → 空上下文 → 提示词 → 判分器」四处必须一起改，这也是它们都得留在我们能改能测的地方（`EvalPromptGuardTest` 钉住提示词与判分器的逐字对应）的原因。

一句话：不是 Advisor 做不到基本 RAG，是它把「检索→拼 prompt→生成」封成了一次性流程，而这个项目的价值链正好落在这三步的缝隙里。

---

## ❓ Q4：`vector_store.content` 字段是乱码（全是 锟斤拷/�）

A：库里 `content` 是 UTF-8 存的，出现 `锟斤拷` 这类串的都是**早期版本用错编码抽正文后写进去的旧记录**（不是 PG 存坏了）。删掉重灌即可：

```sql
DELETE FROM public.vector_store WHERE metadata->>'file_name' = '乱码那个文件名.pdf';
```

顺带把这一份的 BM25 倒排行一起删，否则关键词通道还能召回到向量库里已经不存在的块：

```sql
DELETE FROM public.bm25_docs WHERE file_name = '乱码那个文件名.pdf';
```

（`bm25_postings` 上有 `ON DELETE CASCADE`，删 `bm25_docs` 的 doc 行会自动带走它的 posting 行；`bm25_docs.file_name` 是这张表的真实列，不像向量表那样藏在 metadata 里。嫌麻烦也可以直接调 `GET /bm25/rebuild` 全量对账一次，零模型调用。）

重启应用，这一份会被重新抽取入库。现在的乱码处理是**语言无关**的字符比例判据（`RagInitializer.java:592-618`）：

- **什么时候跑 OCR 兜底**（三条命中任一）：① 合格字数低于 `MIN_VALID_NONBLANK`（200）；② 页数 ≥10 且平均每页非空白字符 <100；③ 乱码率 `weirdRate` 超过 `WEIRD_RATE_MAX`（15%）——统计非空白字符里不属于「中日韩文字 / 拉丁与希腊字母数字 / 文档常用标点」的比例。
- **OCR 结果不是无条件采用**：必须「合格分数更高」且「非空白 ≥20」才替换现有文本，否则打 `↪ [OCR] 识别结果不如现有文本…保留 X 文本`。
- **入库前再 QC 一次**：乱码率仍超 15% → 打 ⚠️「入库内容可能不可用」并预览首行；有效字数 <200 → 这份直接跳过。
- 落库前还有一层 `sanitizeExtractedText()`：清控制字符、私用区字形、水印串、连字符码点（抽取路径的选优靠清洗**前**的原文打分，正文落库前才清洗，见 `RagInitializer.java:626-631`）。

---

## ❓ Q5：每次启动都重复 OCR 同一份文件，增量跳过不生效

A：判重发生在**抽取之前**，靠 `probeStoredVersion()`（`RagInitializer.java:841-856`）问一次 PG：`WHERE metadata->>'source' IN (canonicalPath, absolutePath)`，取回 `COUNT(*) / file_hash / content_hash / extract_version / total_chunks`。第一层命中就 `return`，**连 PDF 都不打开**，所以「跳过」等于「这一份不再 OCR」。

三层判重（`ingestOnePdf()`，`RagInitializer.java:339-422`）：

| 层 | 条件 | 日志 |
|---|---|---|
| ① 文件字节判重 | `complete()`（库内块数 == `total_chunks`，缺块＝上次写库中断，直接不合格）+ `file_hash` 相同 + `extract_version` 是当前管线版本 | `⏭️ 跳过：xxx.pdf（文件字节未变化，已完整入库 N chunks）` |
| ② 正文判重 | 字节变了，但抽出来的 `content_hash` + 块数和库内一致 | `⏭️ 跳过：xxx.pdf（文件有变动但抽取正文一致，N chunks 无需重灌）`，同时回写 `file_hash`/`extract_version`，下次直接走第①层 |
| ③ 重灌 | 前两层都不成立 | 先按 `source` 删旧块；**删除数 ≤0 直接中止**（`⛔ 旧块清理未成功…已中止重灌`），绝不追加，否则同一份文件会出现两套 `chunk_index` |

跳过不生效，按这四条排查：

1. **`app.pgvector.enabled: false`（SimpleVectorStore）**：`probeStoredVersion()` 第一行就是 `pgJdbc == null` → 返回 null，判重整体失效，每次启动全量重灌。这条模式下 `/rag/chunk` 和 BM25 也一起失去数据源。
2. **库内 `source` 写法对不上**：老记录如果是用相对路径或纯文件名写进去的，`IN (canonical, absolute)` 查不到。核对一下：
   ```sql
   SELECT COUNT(*), metadata->>'source' FROM public.vector_store GROUP BY 2;
   ```
   把查出来的值和启动日志里那份 PDF 的路径逐字比。历史上确实有过 `getAbsolutePath()` / `getCanonicalPath()` 不一致的写法，现在读、写两侧都归一到 canonical，并且查询侧带 absolute 兼容老记录。
3. **`extract_version` 落后**：库里那份记录的版本串和当前 `EXTRACT_PIPELINE_VERSION`（现在是 `2026-10-12a`）不一样，第①层直接不成立，必然重抽一次——这是设计如此：改动抽取/清洗逻辑时必须 bump 这个常量，否则修好了选路，旧库却因为 `file_hash` 相同而永远不重灌。
4. **文件真的变了**：重新下载、微信/网盘传一次，字节就变了，`file_hash` 自然不同——但通常会落到第②层，只付一次抽取钱，不会重灌向量。

**不用猜，`GET /rag/diag` 直接告诉你下一轮会不会跳**（都零模型调用，两个列表各有上限、超出置 `*_truncated`）：

- `rag_initializer.stored_overview.per_file[]`：**库内**逐份，带 `chunks` / `total_chunks` / `incomplete`（缺块＝上次写库中断）/ `extract_version` / `stale_extract_version`（版本落后）/ `file_missing_on_disk`（库里有记录但磁盘上文件没了）。
- `rag_initializer.pdfs[]`：**磁盘上**逐份，默认就带 `will_skip_next_boot`（＝第①层成不成立的现算结果）、`complete`、`stored_extract_version` vs `current_extract_version`、`file_hash`；`?deep=true` 才额外逐份重扒正文。
- SimpleVectorStore 模式下 `stored_overview` 会变成 `{available:false, reason:"pgVectorJdbcTemplate 未注入…"}`——这正好和第 1 条排查项对上。

排查顺序建议：先看 `pdfs[].will_skip_next_boot` 是 false，再对照 `stored_overview.per_file[]` 里同名的份——`incomplete`/`stale_extract_version` 都有值就分别是第③/①层的原因；**列表里压根没有这一份**说明 `source` 对不上（第 2 条），因为 `per_file[]` 是按库内 `source` 分组的、`file_missing_on_disk` 只会在路径写法对得上时才可能是 true。

还有一种是「探测本身报错」（表不存在、`metadata` 不是合法 JSON）：代码**不会**把它当成「库里没有」，而是打 `⛔ xxx.pdf 无法确认库内已入库版本（…），本次不写入` 并跳过这一份，防止盲目追加搞出两套块号。

---

## ❓ Q6：纯图片扫描 PDF 全是 "第 N 页 OCR 退出码非 0（exit=1）"

A：大概率是 `chi_sim.traineddata` 找不到，或者 tessdata 在 Program Files 下没权限读。

**第 1 步：先看诊断，别改文件猜。** `GET /rag/diag` → `rag_initializer.ocr` 这一块（下面这些键全在同级）：

| 键 | 怎么看 |
|---|---|
| `probed` / `ocr_ready` | ⚠️ OCR 探测发生在**启动批量入库那一次**。`probed:false` + `ocr_ready:null` 只代表「这轮没探过」（配置目录里没有 PDF 就根本跑不到探测），**不等于 OCR 坏了** |
| `diag_line` | 启动时打控制台那句原文，命中哪种来源写得很明白：`配置路径命中：…` / `自动 fallback #1 …` / `兜底自动复制到 D:/tessdata`；失败是 `❌ tesseract.exe 不存在: …，请检查 application.yml 的 app.ocr.tesseract-path`、`❌ tessdata 目录不存在: …`、`❌ 缺少语言包: chi_sim ，已尝试目录：…` |
| `tessdata_dir_config` vs `tessdata_dir` | 前者是 `application.yml` 里写的，后者是**实际生效**的。两个不一样 = 被 fallback 换掉了 |
| `enabled_config` | `app.ocr.enabled` 是 `false` 时直接判不可用（不会有 `diag_line`），纯图片扫描件会被跳过 |

**第 2 步：知道代码去哪找语言包。** tessdata 按四级顺序找，第一个「`app.ocr.language` 里要求的每个 `.traineddata` 都存在」的目录胜出（`RagInitializer.java:210-259`）：

1. `app.ocr.tessdata-dir`（不填默认 `D:/Program Files/Tesseract-OCR/tessdata`）
2. `D:/AI/demoAI/.diag-tmp/dl`
3. `%USERPROFILE%/tessdata`
4. 前三级都不齐时，若 `D:/AI/demoAI/.diag-tmp/dl/chi_sim.traineddata` 在，自动复制一份到 `D:/tessdata`（`eng.traineddata` 同理，从 `D:/Program Files/Tesseract-OCR/tessdata` 拷）

**第 3 步：Program Files 权限不够就走 `D:/tessdata`。** 把整个 tessdata 文件夹复制到 **`D:\tessdata\`**，`application.yml` 写 `app.ocr.tessdata-dir: D:/tessdata`（本仓库当前配置已经是这一条）。`tesseract.exe` 本身同理：改 `app.ocr.tesseract-path` 指到实际安装位置。

改完重启，`diag_line` 里出现「配置路径命中：D:/tessdata」才算真的用上了配置的那个目录；显示「自动 fallback」说明配置文件里那条路径其实没命中。

---

## ❓ Q7：PDF 太大，处理到一半 "PDF 提取超时（> 120 秒）"

A：**动态超时已经实现**，但不是一个叫 `calcTimeout` 的方法——`ingestOnePdf()` 里先探页数，再按三个常量算：`PER_PDF_MIN_TIMEOUT_MILLIS=120_000`、`PER_PDF_BASE_MILLIS=60_000`、`PER_PDF_OCR_MS_PER_PAGE=15_000`，取 `MAX(120s, 60s + 页数 × 15s)`。39 页扫描件自动放宽到 `60+585=645s` 约 10 分钟，足够完成每页 OCR。整份超时是用 `Future.get(timeout)` 卡的，超时后会 `destroyForcibly()` 杀掉还在跑的 `tesseract.exe` 子进程，报错信息里会写明「页数=N，已强制杀掉 M 个 OCR 子进程」。

还不够就分清两层：`app.ocr.page-timeout-seconds`（**单页**超时，默认 120）管的是某一页 OCR 卡死就跳过这页；上面那个 `MAX(120s, 60s+15s×页数)` 管的是**整份**处理总时长，两者各算各的，调大单页那个不会放宽整份的上限。要放宽整份，只能改 `RagInitializer` 里那三个常量（目前是常量、没有配置项，已列入「后续优化方向」）。

---

## ❓ Q8：答案里有 `**` 星号（Markdown 格式）

A：**当前 System Prompt 里没有「不要用 Markdown」这条约束**——`RagReferenceService.buildSystemPrompt()` 的【回答规则】只有 5 条：① 只根据上下文回答不编造、② 上下文没有就逐字输出固定拒答原话、③ 准确简洁尽量引用原文、④ 不给诊断结论并点名「诊断为/确诊为/你得了/你患有」四种句式、⑤ 涉及药物方案提醒咨询医生。所以模型自己决定加粗，`**` 就进答案了。

显示侧也帮不上忙：`app.js addMsg()` 用 `bubble.textContent` 写气泡，**不渲染 Markdown**，星号是原样显示出来的（不是渲染坏了）。

两条可选的修法，都还没做：
1. 答案返回前加一层正则清洗（把 `**text**` → `text`、`* text *` → `text`）——改 `ChatController`，不碰提示词、不影响判分，代价是模型偶尔用列表符号时也会一起洗掉；
2. 在【回答规则】里加一条纯文本约束——⚠️ 这条会改 System Prompt：`EvalPromptGuardTest` 钉住了提示词第 4 条和判分器 `containsDiagnosisConclusion` 那四种句式的逐字对应，改前先 `mvn test`；而且清洗/改写都不要动到末尾那行 `【引用来源】`，评测的引用判据看的就是答案文本里的文档名。

（另注：答案末尾的引用块目前整段留在答案气泡里，前端 `.refs-block` 那个单独样式走不到，原因见上面「智能问诊」一节的 ⚠️ 说明。）

---

# 📜 版本兼容性

项目已通过 **JDK 21 + Spring Boot 3.5.10 + Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.3** 严格配套验证：

- `spring-ai-client-chat` ✓ （ChatClient.Builder 存在）
- `spring-ai-advisors-vector-store` ✓ （SearchRequest 存在）
- `spring-ai-starter-vector-store-pgvector` ✓ （Spring AI 1.0.0+ 新命名）
- `spring-ai-pdf-document-reader` ✓ （它传递引入的是 PDFBox 3.0.5，代码里按 3.x API 调用）
- **TokenTextSplitter.Builder 可用方法**：`withChunkSize(int)` / `withMinChunkSizeChars(int)` / `withMinChunkLengthToEmbed(int)` / `withMaxNumChunks(int)` / `withKeepSeparator(boolean)` / `build()`。**无 `withChunkOverlap()`**，如需 overlap 效果请自行在后续版本升级。本项目实际只用了 `withChunkSize(512)`，其余全走默认。
- **LangChain4j 1.0.0-beta3**（+ `langchain4j-community` 同版本 BOM）：代码里真正 import 的只有 `ChatLanguageModel`、`AiServices`、`@Tool`、`@SystemMessage` 四个类。`ChatLanguageModel` 这个 bean 由 `langchain4j-community-dashscope-spring-boot-starter` 自动配置提供，它的配置前缀是 **`langchain4j.community.dashscope.*`，和 Spring AI 那套的 `spring.ai.dashscope.*` 不是同一个**——所以两套框架并存时，密钥要在 `application-local.yml` 里各写一遍，`/lc/*` 才能起来。pom 里另外声明的 `langchain4j-pgvector` **确实一处都没用到**（`/lc/*` 不走向量检索）。
- `pom.xml` **没有 `<build>` 段**：`spring-boot-maven-plugin` 只被父 POM 的 pluginManagement 认得，本仓库没显式引用，因此 `mvn package` 不出可执行 fat jar、`mvn spring-boot:run` 没有对应插件配置。要用 Maven 起服务，得先在 pom 里补该插件（已列入「后续优化方向」）。

> ⚠️ **不要**随意升级版本，Spring AI Alibaba 每个小版本对 Spring AI / Spring Boot 有**硬编码依赖**。

---

# 🚀 后续优化方向

| 方向 | 说明 |
|---|---|
| **前端引用块拆分修好** | `app.js splitAnswer()` 找的 `【引用(参考)来源】` 和后端 `RagReferenceService.formatReferences()` 打的 `【引用来源】` 不是同一个串，引用整段落在答案气泡里（详见「智能问诊」一节）。改哪一侧都行，但要一并确认评测的引用判据（「预期文档名是否为答案文本的子串」）不受影响，并且没有单测钉住前端那个字符串——后端改了提示串、前端还在找旧串这类两侧漂移，现有 122 条单测覆盖不到（`EvalPromptGuardTest` 只管 Java 侧提示词与判分器的对应） |
| **Maven 构建补全** | `pom.xml` 没有 `<build>` 段：补 `spring-boot-maven-plugin` 后 `mvn package` 才有可执行 fat jar、`mvn spring-boot:run` 才起得来。现在只有 IDEA 跑 `org.example.Main` 这一条路（另可考虑加 Maven Wrapper） |
| **整份 PDF 超时做成配置项** | `MAX(120s, 60s + 15s×页数)` 三个常量目前写死在 `RagInitializer` 里（`PER_PDF_MIN_TIMEOUT_MILLIS` / `PER_PDF_BASE_MILLIS` / `PER_PDF_OCR_MS_PER_PAGE`），要放宽只能改代码；`app.ocr.page-timeout-seconds` 只管单页，两者互不影响 |
| **注释与实现对齐** | ① `Bm25Controller` 类注释写「重建放在显式接口里而不是启动自动跑」，但 `RagInitializer.java:536` 启动那次确实会 `rebuildAll()` 全量对账（2026-10-21 启动日志可见），注释已过期；② 同文件 534 行那句「实测 1.8s」也旧了，最近一次启动全量对账是 3415ms（同一批 501 块）。两处都是纯注释，改起来不动逻辑；③ `EvaluationService.java:178` 那段口径注释把条件标记写成「…15 个字」，实际 `:188` 的字符集是 16 个字（注释漏了 `便` 和 `否`），行为照 16 个走，只是注释短了两个 |
| 知识库版本治理 | 同一份指南出新版时如何并存与取新：metadata 加生效日期 / `superseded_by`，并定好多版并存时的排序与取舍口径。建议在**第 5 份 PDF 进库之前**做，否则改动要连向量表一起回填 |
| 删档钩子 | WatchService 目前只处理 CREATE/MODIFY，`ENTRY_DELETE` 没有对应清理（向量块 + BM25 倒排行都会残留）。批量清盘 / 换文件前需要补，或先用 `/bm25/rebuild` + 手动 `DELETE` 兜底 |
| 句子级溯源 | 引用现在到「块」为止，还没有：① 精确到句、② 「模型答案里这句话确实来自第 N 块」的证据、③ 需要跨块推理的多跳题考卷（当前 501 块小库里 top-16 全盖住了，测不出多跳）。等库上规模再做 |
| 检索级基线补跑 | `mode=rerank` 那一档在新库 + 新块号标注下还没重测（要 63 次向量化：主口径 58 题 + 域外对照 5 条，外加同 63 次重排，属付费调用）。跑之前先 `GET /evaluation/select` 数清题量 |
| 多轮对话上下文 | 引入对话历史管理，支持追问、澄清和多轮上下文连贯对话 |
| 流式响应（SSE） | 将 `/chat` 接口改造为 Server-Sent Events 流式输出，提升用户等待体验（现在是 `GET` 一次性返回全文，前端 `res.text()` 等整段） |
| Markdown 星号处理 | 提示词里**没有**「不要用 Markdown」这条约束（现有 5 条规则见 Q8），前端也不渲染，所以 `**` 原样显示。两条路子：答案返回前加正则清洗，或在【回答规则】加一条纯文本约束（改提示词要先过 `EvalPromptGuardTest`） |
| 核心链路的集成测试 | 判分口径、检索模式、引用行形状、块号→两套页码、块号→章节号、库存工具三态、PHI 审计行、BM25 行构造已有 **122 条纯逻辑单测**（`src/test/java/org/example/`，不连库不打模型，见「测试」一节）；仍缺的是需要真实依赖的链路：OCR 兜底、Tool Call 路由本身、向量检索、脱敏方法本身 |
| 多格式文档支持 | 扩展对 Word、Markdown、Excel 等非 PDF 格式的解析与入库 |
| 多模型路由 | 根据问题类型自动选择最优大模型（简单问答用 qwen-turbo，复杂推理用 qwen-max） |
| 权限与鉴权 | 现在全部接口都是 `GET`、无鉴权，`/evaluation/run` 一点就是付费全量。接入企业级 RBAC 之前，至少别把它暴露到公网 |
| 动态阈值调优 | 根据用户反馈自动调整阈值（如用户多次点击"没用的引用"则提高 `vector-floor` / `answer-floor`） |

---

# 📄 License

本项目仅供学习与医疗行业内部知识库验证使用，不得用于任何商业用途。

**免责声明**：本软件按"原样"提供，不提供任何明示或暗示的保证。作者不对因使用本软件而产生的任何直接、间接、附带、特殊或后果性的损害承担责任。使用者应自行承担使用本软件的全部风险。

本项目采用的技术栈（Spring Boot、Spring AI、PostgreSQL、Tesseract OCR 等）均遵循其各自的开源许可证。