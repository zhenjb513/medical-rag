# 🏥 DemoAI · 医疗行业 RAG 智能助手

> **一句话介绍**：一个基于 Spring AI 的医疗行业 RAG 智能助手，支持文档检索 + 数据库查询双链路。
>
> 基于 **Spring Boot 3.5 + Spring AI 1.1 + 阿里云百炼 DashScope + PgVector + Tesseract OCR** 的完整 RAG（检索增强生成）医疗知识库 Demo。
>
> 原生支持 **纯图片扫描 PDF** 的 OCR 文字识别、**多层级 PDF 文本提取策略**、**乱码自动救回 QC**、**增量入库去重**、**向量维度自动修复**、**库存查询 Tool Call** 等生产级特性。

---

## ✨ 特性总览

| 模块 | 核心能力 |
|---|---|
| 🤖 **大模型对话** | 阿里云百炼 `qwen-plus`，通过 `ChatClient` API 流式调用 |
| 🔌 **Function Call（工具调用）** | 基于 `@Tool` 注解，AI 可实时查询耗材库存（MySQL `consumables` 表） |
| 📚 **RAG 检索增强** | 从知识库检索相关 PDF 片段作为上下文，再让 AI 基于文档回答 |
| 📄 **PDF 文本提取（4 级兜底）** | P1 标准提取 → P2 参数调整 → P3 暴力流扫 → P4 Tesseract OCR，纯图片扫描件也能识别 |
| 🧪 **内容质量 QC + 乱码救回** | 检测 U+FFFD(�) / CJK 占比 / 控制字符，乱码时自动启动 OCR 重扫救回 |
| 🧭 **增量入库去重** | 查 `vector_store` 表判断是否已入库成功，重启不重复 OCR / Embedding |
| 💾 **PgVector 持久化向量库** | PostgreSQL + pgvector 扩展，HNSW 索引 + COSINE 距离 |
| 🔬 **向量维度自动修复** | 检测表结构维度是否匹配 Embedding 模型（v3=1024，v2=1536），不匹配自动重建 |
| 🌏 **中文端到端无乱码** | JVM / JDBC / PG 客户端 / Tesseract 输出全链路强制 UTF-8 + 字节级自检 |
| ⏱️ **动态超时控制** | PDF 整体超时按页数估算（MAX(2min, 60s + 15s×页数)），子进程 `destroyForcibly()` 强杀，单个 PDF 失败不影响整体 |
| 🩺 **运行时诊断 API** | `/rag/diag` 实时查看 OCR、向量库、入库历史、chunk 分布 |

---

## 🛠 技术栈

| 技术 | 用途 |
|---|---|
| Spring Boot 3.5 | 后端框架 |
| Spring AI 1.1 | AI 集成框架 |
| 阿里云百炼 (Qwen) | 大语言模型 |
| PostgreSQL + pgvector | 向量数据库 |
| MySQL 8.0 | 业务数据库 |
| Tesseract OCR | 扫描件文字识别 |

---

## 🚀 快速开始

### 1️⃣ 环境要求

| 软件 | 最低版本 | 下载地址 |
|---|---|---|
| **JDK** | 21 LTS | https://adoptium.net/ |
| **Maven** | 3.9+ | （Spring Boot 父 POM 已绑定） |
| **MySQL** | 8.0+ | 业务库（耗材库存 `consumables` 表） |
| **PostgreSQL** | 17 + pgvector 扩展 | 向量库（vector 列类型） |
| **Tesseract-OCR** | 5.3.3+（chi_sim 语言包） | https://github.com/UB-Mannheim/tesseract/wiki |

---

### 2️⃣ 数据库初始化

#### 🟦 PostgreSQL + pgvector（向量库）

```sql
-- 1. 启用 pgvector 扩展（每个数据库只执行一次）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 验证扩展是否安装成功
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
```

> ℹ️ **pgvector 安装提示（Windows）**：
> 从 https://github.com/pgvector/pgvector/releases 下载对应 PG 17 版本的 `pgvector.dll`，放到 `PostgreSQL/17/lib/` 目录，再执行上面的 `CREATE EXTENSION`。

#### 🟨 MySQL（业务库 · 耗材库存）

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

---

### 3️⃣ 安装 Tesseract OCR（扫描件 PDF 识别）

1. 下载：https://github.com/UB-Mannheim/tesseract/wiki （安装包名类似 `tesseract-ocr-w64-setup-5.x.x.exe`）
2. 安装时 **Additional language data → 勾选 Chinese - Simplified**（`chi_sim.traineddata`）
3. （推荐）把 **tessdata 文件夹**复制到 `D:/tessdata/`，避免 Program Files 权限问题
4. 验证命令行是否能运行：
   ```powershell
   & "D:/Program Files/Tesseract-OCR/tesseract.exe" --list-langs
   # 期望输出包含：chi_sim / eng
   ```

---

### 4️⃣ 修改配置文件 `application.yml`

```yaml
spring:
  datasource:                 # ← 修改 MySQL 账号密码
    url: jdbc:mysql://localhost:3306/demoAI?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_PASSWORD}

  ai:
    dashscope:
      api-key: sk-ws-xxxxxxxx   # ← 替换成你自己的阿里云百炼 API Key
                                #   申请地址：https://dashscope.console.aliyun.com/apiKey

app:
  pdf-path: D:/ai-docs           # ← 把你的 PDF 知识库丢进去（推荐纯目录模式）

  ocr:
    enabled: true
    tesseract-path: D:/Program Files/Tesseract-OCR/tesseract.exe
    tessdata-dir: D:/tessdata    # ← 改成你实际的 tessdata 目录（避免每次启动自动探测告警）
    language: chi_sim+eng
    dpi: 300
    page-timeout-seconds: 120

  pgvector:
    enabled: true
    jdbc-url: jdbc:postgresql://localhost:5432/postgres?charSet=UTF8&stringtype=unspecified&prepareThreshold=0
    username: postgres
    password: postgres           # ← 修改 PG 密码
    dimensions: 1024             # ⚠️ text-embedding-v3 默认 1024；切到 v2 请改成 1536
    reset-table: false           # ⚠️ 正常保持 false（增量入库）。想清空重来时改成 true 启动一次即可
```

---

### 5️⃣ 启动应用

```powershell
# 方式 A：Maven 命令行
mvn spring-boot:run -Dfile.encoding=UTF-8 "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8"

# 方式 B：先打包再跑
mvn -DskipTests package
java -Dfile.encoding=UTF-8 -jar target/demoAI-1.0-SNAPSHOT.jar
```

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
```

---

## 🔌 API 接口

### ① 💬 聊天对话（自带 RAG + Tool Call）

```http
GET /chat?message=高血压患者的降压目标是多少？
```

**示例响应**：

> 根据《国家基层高血压防治管理指南 2025 版》第 3 章，一般高血压患者降压目标为 **< 140/90 mmHg**；
> 合并糖尿病或慢性肾病的患者目标为 **< 130/80 mmHg**；80 岁及以上高龄患者 **< 150/90 mmHg** ...

```http
GET /chat?message=胃镜活检钳还有多少库存？
```

**示例响应**（AI 自动调用 `queryConsumableStock` Tool，真实查 MySQL）：

> 目前「胃镜活检钳」的当前库存为 **128 件**，请按需安排采购。

---

### ② 🩺 RAG 运行时诊断

```http
GET /rag/diag
```

**输出示例**：

```json
{
  "vector_store_type": "org.springframework.ai.vectorstore.pgvector.PgVectorStore",
  "pg_vector_initializer_ready": true,
  "rag_initializer": {
    "ocr_ready": true,
    "pdfs_total": 5,
    "pdfs_ingested_ok": 5,
    "recent_processed": [
      { "name": "中国心血管病一级预防指南2020.pdf", "chunks": 204, "non_blank": 95821, "source": "ocr-p4" },
      { "name": "国家基层高血压防治管理指南2025版.pdf", "chunks": 49, "non_blank": 35210, "source": "extract-p2" }
    ]
  }
}
```

---

## 🧱 架构图

```
    ┌────────────────────────────────────────────────────────────────────┐
    │                          💬  用户请求                               │
    │                  GET /chat?message=xxx                             │
    └────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
    ┌────────────────────────────────────────────────────────────────────┐
    │                     ChatController (入口)                           │
    │           ChatClient + QuestionAnswerAdvisor (RAG 顾问)            │
    └───────────────┬────────────────────────────────────┬───────────────┘
                    │                                    │
                    │ 检索 Top-K 相关 chunk               │ 调用 Tool 函数
                    ▼                                    ▼
    ┌──────────────────────────────┐      ┌───────────────────────────────┐
    │     PgVectorStore (PG 17)    │      │  InventoryService (@Tool)     │
    │   vector_store 表 HNSW 索引  │      │  SELECT stock FROM consumables│
    │   embedding = text-emb-v3    │      │         ════▶ MySQL 8.0       │
    └───────────────┬──────────────┘      └───────────────────────────────┘
                    │
                    ▼
    ┌────────────────────────────────────────────────────────────────────┐
    │                    RagInitializer（知识库构建）                      │
    │  D:/ai-docs/*.pdf  ─┬─▶ P1 标准PDFBox提取                           │
    │                     ├─▶ P2 参数调整提取                             │
    │                     ├─▶ P3 暴力流扫（隐藏文字层）                   │
    │                     └─▶ P4 Tesseract OCR（纯图片扫描件兜底）       │
    │                           │                                         │
    │                           ▼                                         │
    │                    ✅ 内容 QC 乱码检测 + OCR 救回                    │
    │                           │                                         │
    │                           ▼                                         │
    │                    TokenTextSplitter 切片（~500 字/chunk）           │
    │                           │                                         │
    │                           ▼                                         │
    │                    DashScope text-embedding-v3 → 1024 维向量        │
    │                           │                                         │
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

## 📂 关键代码文件

| 文件 | 职责 |
|---|---|
| `RagInitializer.java` | **核心**：PDF 4 层提取策略、OCR 调用、内容 QC 乱码检测、增量入库、切片 + Embedding、诊断接口数据 |
| `PgVectorSchemaInitializer.java` | PgVector 建表、vector 扩展检查、维度不匹配自动重建、全链路 UTF-8 自检、默认保留旧表（`reset-table=true` 才清） |
| `ChatController.java` | `/chat` 对话接口（RAG + Tool Call）、`/rag/diag` 诊断接口 |
| `InventoryService.java` | `@Tool` 函数：耗材库存查询（AI 自动调用） |
| `SpringAiConfig.java` | DashScope EmbeddingModel 配置（text-embedding-v3 / 1024 维） |
| `PgVectorConfig*.java` | PgVectorStore Bean 注册、独立数据源（和业务 MySQL 完全解耦） |
| `application.yml` | 所有配置：DashScope API Key、PDF 路径、OCR、PgVector、日志级别 |

---

## 🛠 常见问题

### ❓ Q1：启动时报 `expected 1536 dimensions, not 1024`

A：DashScope `text-embedding-v3` 默认输出 **1024 维**，但你库里的 `vector_store` 表是按 v2 的 1536 维建的。解决（二选一）：

- 方案 A（推荐）：把 `app.pgvector.reset-table: true` 启动**一次**重建表，重启后改回 `false`
- 方案 B：`application.yml` 里把 embedding 模型切回 `text-embedding-v2`，`dimensions: 1536`

---

### ❓ Q2：`vector_store.content` 字段是乱码（全是 锟斤拷/�）

A：**项目 v1 已经修复所有已知中文乱码路径**，如果你还有旧乱码记录：

```sql
DELETE FROM public.vector_store WHERE metadata->>'file_name' = '乱码那个文件名.pdf';
```

重启应用，**新的 QC 乱码救回**会自动启动 OCR 重扫写入正确中文（修复逻辑：`RagInitializer.java:692` QC 分支 → 只要 OCR 质量达标 CJK≥15% 且 �≤1%，**字数少也用 OCR**，不再被乱码「假字数多」反杀）。

---

### ❓ Q3：每次启动都重复 OCR 同一份文件，增量跳过不生效

A：v1 已修复（`getAbsolutePath()` vs `getCanonicalPath()` 不一致）。如果是升级前写的旧记录，**新代码查库用 `source IN (canonical, absolute)` OR 双路径**，能自动识别老版本写入的 absolutePath 记录。如果还是重复：

```sql
SELECT COUNT(*), metadata->>'source' FROM public.vector_store GROUP BY 2;
```

看 `source` 字段值是否和你本地文件路径（打印到日志里那个）对应得上。

---

### ❓ Q4：纯图片扫描 PDF 全是 "第 N 页 OCR 退出码非 0（exit=1）"

A：大概率是 `chi_sim.traineddata` 找不到 / tessdata 目录权限问题。

1. 开诊断：`GET /rag/diag` 看 `ocr_ready` 和 `tessdata_dir` 是否为 `✅ 配置路径命中`
2. 把 tessdata 从 `C:\Program Files\Tesseract-OCR\tessdata\` 复制到 **`D:\tessdata\`**（无权限限制）
3. `application.yml` 同步改 `tessdata-dir: D:/tessdata`

---

### ❓ Q5：PDF 太大，处理到一半 "PDF 提取超时（> 120 秒）"

A：已实现**动态超时**（`RagInitializer.calcTimeout(pageCount)`）：`MAX(120s, 60s + 页数 × 15s)`。39 页扫描件自动放宽到 `60+585=645s` 约 10 分钟，足够完成每页 OCR。若还不够，把 `app.ocr.page-timeout-seconds` 调大到 180~240。

---

## 📜 版本兼容性

项目已通过 **JDK 21 + Spring Boot 3.5.10 + Spring AI 1.1.2 + Spring AI Alibaba 1.1.2.3** 严格配套验证：

- `spring-ai-client-chat` ✓ （ChatClient.Builder 存在）
- `spring-ai-advisors-vector-store` ✓ （QuestionAnswerAdvisor 存在）
- `spring-ai-starter-vector-store-pgvector` ✓ （Spring AI 1.0.0+ 新命名）
- `spring-ai-pdf-document-reader` ✓ （PDFBox 3.1.x 兼容）

> ⚠️ **不要**随意升级版本，Spring AI Alibaba 每个小版本对 Spring AI / Spring Boot 有**硬编码依赖**。

---

## 🚀 后续优化方向

| 方向 | 说明 |
|---|---|
| 多轮对话上下文 | 引入对话历史管理，支持追问、澄清和多轮上下文连贯对话 |
| 流式响应（SSE） | 将 `/chat` 接口改造为 Server-Sent Events 流式输出，提升用户等待体验 |
| 单元测试与集成测试 | 补充核心链路（OCR 兜底、Tool Call 路由、向量检索）的自动化测试 |
| 多格式文档支持 | 扩展对 Word、Markdown、Excel 等非 PDF 格式的解析与入库 |
| 多模型路由 | 根据问题类型自动选择最优大模型（简单问答用 qwen-turbo，复杂推理用 qwen-max） |
| 权限与审计 | 接入企业级 RBAC 权限控制，记录所有问答操作的审计日志 |

---

## 📄 License

本项目仅供学习与医疗行业内部知识库验证使用，不得用于任何商业用途。

**免责声明**：本软件按"原样"提供，不提供任何明示或暗示的保证。作者不对因使用本软件而产生的任何直接、间接、附带、特殊或后果性的损害承担责任。使用者应自行承担使用本软件的全部风险。

本项目采用的技术栈（Spring Boot、Spring AI、PostgreSQL、Tesseract OCR 等）均遵循其各自的开源许可证。
