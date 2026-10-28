# SeeIt AI 前端

本目录是 SeeIt AI 的 Vue 3 前端，提供本地视频分片上传、断点续传、任务列表、分析进度、证据报告、继续追问和用户认证界面。

## 本地启动

推荐使用 Node.js 22 或更高版本。

```bash
cd client
npm install
npm run dev
```

页面默认运行在 `http://localhost:5173`。Vite 将 `/api` 转发到 Python `9090`，
`/business-api` 转发到 Spring Boot `8080`。只有创建分析任务和按任务 ID 查询状态
使用 Java；登录、上传、BV 导入、转写、报告恢复、Agent 元数据和追问继续使用 Python。
两端须共用 MySQL、Redis、JWT_SECRET，Java 提交后仍由原 Python Worker 处理。

复制 `.env.example` 为 `.env.local` 后可调整：

```bash
VITE_API_BASE_URL=/api
VITE_BUSINESS_API_BASE_URL=/business-api
VITE_ANALYSIS_BACKEND=java
PYTHON_API_TARGET=http://127.0.0.1:9090
BUSINESS_API_TARGET=http://127.0.0.1:8080
```

前两个地址用于浏览器请求，后两个只用于 Vite/preview 代理，避免新增 Java CORS 配置。
生产 Nginx 使用同样前缀，Java 上游为 Compose 的 `business-backend:8080`。

## 回退到 Python

设置 `VITE_ANALYSIS_BACKEND=python` 并重启 Vite；生产环境修改部署环境文件后重新构建
前端镜像。开关同时切换创建和按 ID 状态查询，其他接口保持不变。原 FastAPI 接口一直保留。
生产回退示例（仓库根目录）：

```bash
docker compose --env-file deploy/.env.production -f docker-compose.prod.yml build frontend
docker compose --env-file deploy/.env.production -f docker-compose.prod.yml up -d --no-deps frontend
```

不在 POST 网络错误或超时后自动重发到另一个后端，因为原请求可能已提交。再次提交时
由共享数据库约束和 Redis 防重；409 带 taskId 时继续轮询已有任务。
MQ 返回 PENDING 时保留“投递待确认”提示，不显示为已经成功投递。

## 演示模式

访问 `http://localhost:5173/?demo` 可直接查看内置的视频分析结果，不依赖后端、数据库或模型密钥。

## 构建检查

```bash
npm run build
```

前端基于 Vue 3、Vite 和 Marked 开发。界面渲染模型返回的 Markdown 前会过滤不受支持的标签和属性，避免直接执行模型输出中的危险 HTML。

## 回归测试

`npm test` 使用 Node 22 内置 runner，运行 6 项路由、鉴权和异常响应测试；没有独立
服务配置时，两个联调测试显式跳过。没有新增测试框架依赖。

`tests/integration.test.js` 执行真实 `src/api.js`，经过 Vite/Nginx，覆盖有效 MP4 分片
上传、断点查询、去重、并发防重、Java/MQ/原 Worker、报告、追问、权限、ASR 故障重试
及 Python 创建接口回退。只可在独立测试库、MockProvider 环境中执行。

先准备独立 MySQL、Redis、MQ、Java、原 Python API/Worker 和本地 ASR fixture。
从仓库根目录生成真实音视频文件并启动 ASR 成功/503 故障接口：

```powershell
.\.venv\Scripts\python.exe client/tests/fixtures/make_video.py business-backend/target/ui-e2e/video.mp4
.\.venv\Scripts\python.exe client/tests/fixtures/asr_server.py --port 20991
```

API/Worker 的 ASR_BASE_URL 指向 fixture；Worker 的 QUEUED_TASK_FALLBACK_SECONDS
设为 86400，避免把 watchdog 算作 MQ 消费。跨 WSL/容器时上传路径必须共享。
`tests/fixtures/run_python_api.sh` 是测试用原 API 启动器，只接受
`vtrace_business_test_*` 库并禁用付费模型，不能用于生产。
配置 Vite 的实际后端地址后，从 `client` 目录运行：

```powershell
$env:VTRACE_FRONTEND_URL='http://127.0.0.1:5176'
$env:VTRACE_VIDEO_FILE='B:/python/VTrace/business-backend/target/ui-e2e/video.mp4'
$env:VTRACE_ASR_FIXTURE_URL='http://127.0.0.1:20991'
$env:VTRACE_WORKER_LOG='B:/python/VTrace/business-backend/target/ui-e2e/worker.err.log'
node --test tests/integration.test.js
```

停止测试 Broker 后，设置 `VTRACE_EXPECT_MQ_FAILURE=1` 和 seed 脚本生成的
`VTRACE_TEST_FIXTURE_FILE`，运行 `node --test tests/outage.integration.test.js`，
验证 PENDING、重复提交、401/404 和禁止隐式重发。可用 `VTRACE_FRONTEND_RESULT_FILE`
保存机器可读结果。测试用 Python 回退使用原 local executor；默认 Java 路径则必须
有真实 MQ 接收日志才能通过。

实际结果和浏览器/Docker 边界见 [整体联调记录](../business-backend/FRONTEND_VALIDATION.md)。
 
