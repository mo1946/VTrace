# VTrace

集成视频上传、语音转写、画面文字识别与 AI 分析的长视频内容理解平台。用户可以上传本地视频或导入公开 B 站视频，按目标生成带时间戳证据的分析报告，并围绕原视频继续追问。

项目采用 **Vue 3 + Spring Boot + FastAPI** 混合后端架构：Java 承担分析任务的业务入口，Python 保留视频处理、Agent 编排和检索能力，通过共享数据库与 RocketMQ 协作。

## 核心功能

- **视频接入**：分片上传、断点查询、用户级 MD5 内容去重；支持 BV 号预览与公开视频异步导入。
- **内容提取**：faster-whisper 提取语音时间轴，PaddleOCR 识别关键帧文字，统一保存为 ASR/OCR 证据片段。
- **目标驱动分析**：LangGraph 编排计划、证据检索、核验、报告生成与审查，输出可追溯的引用和时间戳。
- **视频追问**：持久化会话与短期记忆，追问时重新检索原始视频证据，支持刷新后恢复报告与对话。
- **异步任务**：展示任务状态、处理阶段、进度和工具调用 Trace，支持失败重试与重复任务防护。
- **用户隔离**：JWT 登录认证，按用户校验视频、任务和会话的访问权限。
- **工具扩展**：提供 MCP 工具、资源模板，以及课程笔记、会议复盘、操作指南和证据审计 Skill。

## 技术栈

| 模块 | 核心技术 | 职责 |
| --- | --- | --- |
| 前端 | Vue 3、Vite（rolldown-vite）、Axios、Marked | 上传、任务交互、进度轮询、报告与对话展示 |
| Java 后端 | Java 21、Spring Boot 3.5、MyBatis、Spring Data Redis | 任务创建、权限校验、事务持久化、消息投递、按任务 ID 查询状态与报告 |
| Python 后端 | FastAPI、Uvicorn、SQLAlchemy、Alembic | 用户认证、视频接入、AI 接口、会话管理和数据库迁移 |
| 数据与消息 | MySQL、Redis、RocketMQ 4.9.4 | 业务持久化、防重与限流、异步任务传递 |
| 视频处理 | PyAV、faster-whisper、PaddleOCR、yt-dlp | 视频校验、音频提取、抽帧、ASR/OCR 与公开视频导入 |
| Agent 与模型 | LangGraph、OpenAI-compatible API | 结构化分析与模型调用，示例配置使用 DeepSeek |
| 检索增强 | 词法检索、BGE ONNX Embedding、RRF、Qdrant | 视频证据的词法、向量与混合检索 |
| 部署与测试 | Docker Compose、Nginx、Caddy、JUnit/Mockito、pytest、Node Test Runner | 服务编排、代理、HTTPS 与分层回归测试 |

视频校验、音频提取和 OCR 抽帧已使用 PyAV；Docker 镜像仍安装 FFmpeg，供公开视频下载等工具使用。Qdrant 是可配置的检索后端，可切换为无需向量服务的词法检索。

## 系统架构

```text
浏览器 / Vue 3
      |
      +-- /business-api --> Spring Boot
      |                       |-- JWT / 资源归属 / 参数校验
      |                       |-- MyBatis + MySQL：创建任务、查询状态与报告
      |                       |-- Redis：活跃任务防重、限流、撤销记录
      |                       `-- RocketMQ Producer
      |
      `-- /api -----------> FastAPI
                              |-- 注册登录、上传、BV 导入
                              |-- 报告恢复、追问、会话与 Agent 工具
                              `-- 原分析接口，供显式回退及 Python 客户端使用

RocketMQ --> Python Worker
                 |-- 视频处理、faster-whisper、PaddleOCR
                 |-- 证据检索：词法 / BGE + Qdrant 混合检索
                 |-- LangGraph + AI Provider
                 `-- 更新共享 MySQL：进度、报告、错误与 Trace

MCP 客户端 --> MCP Server --> FastAPI（转发用户 Bearer Token）
```

Java、Python API 和 Worker 复用现有 MySQL、Redis 与消息协议；Java 模块没有新增数据库表，也不执行 ASR/OCR 或重写 Python Worker。

### 分析任务调用链

1. 前端通过 Python 完成视频上传或导入，取得视频 ID。
2. 前端向 Java 提交视频 ID 与分析目标；Java 校验登录身份、资源归属和参数。
3. Redis 活跃任务锁配合 MySQL `active_key` 唯一约束防重。MyBatis 在同一事务内插入 `analysis_tasks` 并更新视频状态。
4. 数据库事务提交后，Java 向 RocketMQ 发送任务消息。
5. Python Worker 消费消息，通过条件更新领取任务，提取或复用证据，再执行 AI 分析并更新数据库。
6. 前端轮询 Java 状态接口，完成后展示报告；追问继续调用 Python。

消息格式保持一致：

```text
Topic：video-analysis-topic（可通过 ROCKETMQ_TOPIC 配置）
Tag：analysis
Key：taskId
Body：{"type":"analysis","taskId":"任务 UUID"}
```

任务状态主要为 `QUEUED → PROCESSING → COMPLETED`；可重试异常进入 `RETRYING`，达到次数上限后进入 `FAILED`。默认最多尝试 3 次，部分 Agent 质量门禁错误会直接结束任务。消费端利用任务状态条件更新避免重复领取正在处理或已结束的任务。

MQ 发送异常或超时时，Java 保留已落库任务，返回 `202` 和 `dispatchStatus=PENDING`，由已运行的 Python Worker 排队任务 watchdog 兜底。**这不是数据库与 MQ 的原子事务，也不是 Outbox 或 Exactly Once 方案。** Worker 无法启动时，任务需要等待依赖恢复。

## 项目结构

```text
VTrace/
├── business-backend/          Java 业务模块
│   ├── src/main/java/        Controller / Service / Mapper、认证、Redis、MQ
│   ├── src/main/resources/   Spring Boot 配置
│   ├── src/test/             单元与 MySQL/Redis 集成测试
│   └── scripts/              Python 样例、隔离 Broker 与链路验证脚本
├── backend/
│   ├── seeit/                FastAPI、Worker、Agent、检索、OCR、MCP
│   ├── alembic/              现有数据库迁移
│   ├── tests/                Python 回归测试
│   ├── evals/                检索与 Agent 评测数据
│   └── scripts/              离线评测脚本
├── client/                   Vue 3 前端、代理配置与接口测试
├── skills/                   视频分析场景的 MCP Skill
├── deploy/                   生产配置、Caddy、备份与部署脚本
├── docs/                     设计与发布文档
├── .github/workflows/        CI：Python、Java、前端与部署配置检查
├── docker-compose.yml        本地基础服务与后端编排
└── docker-compose.prod.yml   包含前端和 HTTPS 入口的生产编排
```

部分包名、数据库名和配置仍沿用 `seeit` / `SeeIt AI`，以兼容现有实现。

## 本地快速启动

推荐使用 Compose 启动基础设施与后端，在宿主机运行 Vue 开发服务器。

### 1. 准备环境

- Docker Engine 或 Docker Desktop，以及 Docker Compose v2；Windows 使用 Linux 容器。
- Node.js 22+ 与 npm。
- 当前 Python Dockerfile 使用 Linux amd64 的 RocketMQ C++ 客户端安装包，其他架构需要适配。

```bash
git clone https://github.com/mo1946/VTrace.git
cd VTrace
cp .env.example .env
```

PowerShell 可用 `Copy-Item .env.example .env` 代替 `cp`。已有 `.env` 时保留自己的配置。

### 2. 配置 AI 模型

在根目录 `.env` 中填写：

```dotenv
AI_BASE_URL=https://api.deepseek.com
AI_API_KEY=你的模型服务密钥
AI_MODEL=deepseek-v4-flash
```

`AI_MODEL` 应使用你的服务账号实际支持的模型名称，也可改为其他兼容 OpenAI 接口的模型服务。密钥为空时，后端使用确定性 `MockProvider`，适合验证任务与页面流程；Mock 输出不能作为真实模型分析效果。

Compose 的 Worker 默认启用本地 Whisper ASR 和 PaddleOCR，首次启动或使用时需要下载模型。模型及上传文件分别保存在 Docker volume 中，后续复用缓存。

### 3. 启动后端与依赖

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

该配置启动 MySQL、Redis、RocketMQ NameServer/Broker、Qdrant、FastAPI、Spring Boot、Worker 和 MCP，**不包含前端服务**。API 入口脚本执行 `alembic upgrade head`，Java 不负责创建或修改表结构。

查看初始化与任务日志：

```bash
docker compose logs -f api business-backend worker
```

### 4. 启动前端

在另一个终端执行：

```bash
cd client
npm ci
npm run dev
```

| 入口 | 默认地址 |
| --- | --- |
| Vue 页面 | http://localhost:5173 |
| Python 健康检查 | http://localhost:9090/health |
| Python OpenAPI 文档 | http://localhost:9090/docs |
| Java 业务接口 | http://127.0.0.1:8080 |
| MCP Streamable HTTP | http://localhost:8001/mcp |

Java 接口需要 Python 登录签发的 Bearer Token，当前未集成 Swagger UI。先在页面注册并登录，再上传视频、创建分析任务和追问。访问 `http://localhost:5173/?demo` 可查看不依赖后端的内置界面演示。

开发 Compose 使用固定的本地数据库与 JWT 配置，仅用于本地开发；服务器部署使用生产模板。

## 配置与本地开发

### 配置作用范围

| 文件或配置 | 作用 |
| --- | --- |
| 根目录 `.env` | 开发 Compose 变量插值，只有 Compose 引用的变量会进入容器 |
| `backend/.env` | 直接启动 Python 时使用，可从 `backend/.env.example` 复制 |
| `client/.env.local` | 前端与 Vite 配置，可从 `client/.env.example` 复制 |
| `deploy/.env.production` | 生产 Compose 配置，从生产示例复制并替换密码、域名等 |
| Java 进程环境变量 | Spring Boot 读取，不会自动加载根目录或 Python 的 `.env` |

真实 `.env`、令牌和模型权重不提交仓库。修改 Compose 环境变量后，需重新执行 `docker compose up -d` 更新容器；修改 `VITE_*` 后需重启开发服务器或重新构建前端。

### 关键配置

| 配置 | 说明 |
| --- | --- |
| `DATABASE_URL` / `VTRACE_DB_URL` | Python SQLAlchemy URL / Java JDBC URL，必须连接同一 MySQL 数据库 |
| `JWT_SECRET` | Java 与 Python 必须一致，否则用户令牌无法跨后端验证 |
| `REDIS_URL` | Java、API 与 Worker 共用的 Redis 地址 |
| `ROCKETMQ_NAMESERVER` / `ROCKETMQ_TOPIC` | 生产者与消费者使用一致的 NameServer 和 Topic |
| `UPLOAD_ROOT` | Python API 与 Worker 必须访问同一份视频文件 |
| `LOCAL_ASR_ENABLED` / `ASR_BASE_URL` | 本地 ASR 开关 / 远程 ASR 地址；直接运行 Python 的示例默认关闭本地 ASR |
| `OCR_ENABLED` | 是否执行画面文字识别 |
| `EVIDENCE_RETRIEVER_PROFILE` | 默认 `coverage-aware-qdrant-hybrid-v2`；`coverage-aware-lexical-v3` 可使用词法检索 |
| `EVIDENCE_RETRIEVAL_FALLBACK_ENABLED` | 启用时，Qdrant/向量检索故障可降级到词法检索 |
| `VALIDATE_VIDEO_CONTENT` / `MAX_VIDEO_DURATION_SECONDS` | 上传视频内容校验开关及启用校验后的时长上限；生产示例启用校验，上限为 900 秒 |
| `BILIBILI_MAX_DURATION_SECONDS` | BV 导入的时长上限，示例为 900 秒；面向长视频理解不代表无限时长 |

开发 Compose 中部分参数直接写在服务定义里，例如 Worker 的 ASR/OCR 开关；只修改根 `.env` 中同名变量不一定生效，需要核对 Compose 的实际引用。

### 单独调试服务

- **Java**：需要 JDK 21、Maven 和已完成原 Alembic 迁移的 MySQL。配置 `VTRACE_DB_URL`、`VTRACE_DB_USERNAME`、`VTRACE_DB_PASSWORD`、共享 `JWT_SECRET`、Redis 和 RocketMQ 后，在 `business-backend/` 执行 `mvn spring-boot:run`。详见 [Java 模块文档](./business-backend/README.md)。
- **Python**：建议 Python 3.11。在 `backend/` 安装 `requirements.txt`、准备 `.env`、执行 `python -m alembic upgrade head`，再运行 `uvicorn seeit.main:app --reload --port 9090`。Worker 需要 `requirements-rocketmq.txt` 和 RocketMQ C++ 动态库，推荐在 Docker 或 Linux/WSL 运行 `python -m seeit.worker`。
- **前端**：默认 `/api` 代理 Python `9090`，`/business-api` 代理 Java `8080`。可通过 `PYTHON_API_TARGET`、`BUSINESS_API_TARGET` 调整，详见 [前端文档](./client/README.md)。

`backend/.env.example` 的 SQLite 配置适合独立 Python 开发；Java + Python 联调需要共享 MySQL。单独调试服务时避免与容器占用相同端口。混用宿主机、WSL 和容器时，还需确保 Broker 广播地址可被生产者和消费者同时访问。

### 回退到原 Python 分析接口

在 `client/.env.local` 中设置：

```dotenv
VITE_ANALYSIS_BACKEND=python
```

重启 Vite 后，任务创建和按 ID 状态查询一起切回 FastAPI。生产环境需在部署环境文件中修改该变量并重新构建前端。登录、上传、报告恢复和追问始终使用 Python。

前端不会在创建任务超时后自动向另一后端重发，避免原请求已落库时再次创建任务。重复提交返回 `409`；响应带有既有 `taskId` 时，前端恢复对该任务的轮询。

## 主要接口

以下为服务内部路径；通过前端代理访问时，需要使用对应前缀。除注册、登录和健康检查等接口外，业务接口需要 Bearer Token。

| 方法 | 路径 | 默认后端 | 作用 |
| --- | --- | --- | --- |
| POST | `/user/register`、`/user/login` | Python `/api` | 注册与登录 |
| POST | `/media/init-upload`、`/media/upload-chunk`、`/media/complete-upload` | Python `/api` | 分片上传 |
| GET | `/media/upload-status`、`/media/list` | Python `/api` | 断点查询与视频列表 |
| POST | `/media/bilibili/preview`、`/media/bilibili/import` | Python `/api` | BV 预览与异步导入 |
| POST | `/analysis/ai?id={mediaId}&goal={goal}` | Java `/business-api` | 创建分析任务，受理返回 `202` |
| GET | `/analysis/task-status?taskId={taskId}` | Java `/business-api` | 查询状态、进度、错误与报告 |
| POST | `/analysis/transcribe?id={mediaId}` | Python `/api` | 单独提取视频文字 |
| GET | `/analysis/report`、`/analysis/agent-memory` | Python `/api` | 恢复报告与会话 |
| POST | `/analysis/follow-up?id={mediaId}&question={question}` | Python `/api` | 基于原视频继续追问 |

Java 两个分析接口使用与原 Python 接口兼容的参数；完整 Python 接口以运行时 `/docs` 为准。无效身份返回 `401`，跨用户查询视频或任务返回 `404`，分析请求限流返回 `429`。

## 测试与验证

### 常规回归

准备好各模块依赖后，分别从仓库根目录进入对应目录执行：

```bash
cd backend
python -m pytest -q
```

```bash
cd business-backend
mvn verify
```

```bash
cd client
npm test
npm run build
```

Java 的 MySQL/Redis 集成项、Python 的外部 Qdrant 集成项和前端链路测试需要额外环境；条件未配置时部分测试会跳过。单元测试通过不能代替真实消息链路验收。

### 跨语言链路与异常测试

项目提供 Python ORM 样例、独立 RocketMQ Broker 和前端 API 联调脚本，用于验证：

- Python 创建的任务可被 Java 读取，Java 创建的任务可被原 Worker 消费。
- 分片上传、断点查询、内容去重、并发重复提交与事务回滚。
- MQ 发送失败返回 `PENDING`，保留任务和防重状态。
- 未登录与跨用户访问、任务失败重试、报告恢复、追问和 Python 回退。

集成测试使用独立的 `vtrace_business_test_*` 数据库，不能对业务库运行。脚本、配置与历史验证范围见 [Java 链路记录](./business-backend/VALIDATION.md) 和 [前端整体联调记录](./business-backend/FRONTEND_VALIDATION.md)。其中的 MockProvider、ASR fixture 测试不证明真实模型效果；浏览器交互、Docker 构建及生产部署的未验证项以记录为准。

### 检索与 Agent 评测

`backend/evals/` 和 `backend/scripts/` 包含证据检索、真实视频及离线 A/B 评测，关注 Recall@K、MRR、多证据完整命中、拒答、延迟及 Provider Token 使用。基础证据检索评测可在 `backend/` 执行：

```bash
python scripts/evaluate_evidence_rag.py
```

真实评测还需要对应的证据快照、模型文件或服务配置。开发集效果不能外推为未见视频准确率；现有评测已暴露多证据召回、ASR/OCR 实体识别和拒答泛化的边界，Qdrant 持久化也不等于准确率自动提升。

## MCP 与场景 Skill

`backend/seeit/mcp_server.py` 提供 14 个工具和 4 个资源模板，支持视频列表、元数据、时间轴检索、上下文展开、引用校验、分析、报告与追问等操作。HTTP 模式使用网站用户 Bearer Token，资源权限由 Python API 校验。

Compose 启动后可连接 `http://localhost:8001/mcp`。本地 stdio 模式在已安装依赖的 `backend/` 目录运行：

```powershell
$env:SEEIT_API_URL = 'http://127.0.0.1:9090'
$env:SEEIT_MCP_TOKEN = '网站登录返回的用户令牌'
python -m seeit.mcp_server --transport stdio
```

场景 Skill 位于 [skills/](./skills/)，客户端需要自行配置相应的 MCP 连接。MCP 当前调用原 Python API，不经前端的 Java 路由选择。

## 服务器部署

生产模板包含前端 Nginx、Spring Boot、Python API/Worker、MySQL、Redis、RocketMQ、Qdrant、MCP 和 Caddy HTTPS 入口。

```bash
cp deploy/.env.production.example deploy/.env.production
# 修改域名、数据库与 Redis 密码、JWT_SECRET、模型配置等
docker compose --env-file deploy/.env.production -f docker-compose.prod.yml config --quiet
docker compose --env-file deploy/.env.production -f docker-compose.prod.yml up -d --build
```

需同步修改连接 URL 中的凭据，并保持 Java 与 Python 的数据库、Redis、JWT 和 Topic 配置一致。域名解析、HTTPS、持久卷备份和更新步骤见 [部署文档](./deploy/README.md)。

生产模板只映射公网入口 `80/443`，基础服务位于 Compose 内部网络。当前 Worker 默认单消费线程并配置 CPU/内存上限；实际容量取决于视频长度、ASR/OCR 和模型延迟，项目尚未提供生产压测结论。

## 常见问题与当前边界

| 现象 | 检查方向 |
| --- | --- |
| 登录正常，但 Java 返回 `401` | 两端 `JWT_SECRET` 是否一致，用户表和 Redis 是否指向同一环境 |
| 任务一直排队或显示投递待确认 | Broker、Worker 是否运行，NameServer、Topic 与 Broker 广播地址是否可达 |
| ASR/OCR 已完成，Agent 仍失败 | 查看 Worker 日志和任务 `error`，检查模型权限、请求限制与 HTTPS 连通性 |
| 出现 `SSL: UNEXPECTED_EOF_WHILE_READING` | 检查运行 Worker 的实际环境及 VPN/代理路由；Windows 能访问不代表 WSL/容器也能访问 |
| 首次任务处理较慢 | 可能正在下载或加载 ASR/OCR/Embedding 模型，查看 Worker 日志并确认下载源可达 |
| 向量检索不可用 | 检查 Qdrant 与模型文件，或使用词法 Profile；启用 fallback 时注意日志中的降级记录 |

目前 Java 聚焦任务入口，登录、上传、视频导入、报告恢复和追问仍由 Python 提供。系统支持可追溯引用与证据不足时拒答，但不能保证模型输出完全正确；识别质量、跨片段实体恢复和复杂问题的证据覆盖仍需持续验证。

更多模块说明：[Java 后端](./business-backend/README.md) · [Vue 前端](./client/README.md) · [前端设计规范](./docs/前端Soft-UI设计与开发规范.md)。
