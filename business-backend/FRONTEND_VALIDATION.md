# Vue / Java / Python 整体联调记录

日期：2026-10-27（Asia/Shanghai）。使用独立测试库与本次创建的临时实例；未修改真实
`.env`、原表结构、Python API/Worker 或 AI 实现。

## 实现边界

- Vue 仅将创建任务及按 ID 状态查询路由到 `/business-api`；其他请求保持 `/api`。
- Vite 开发代理与 Nginx 生产代理分别连接 Java、Python。
- Java 状态响应增加已有数据库 `result` 的 `report` 投影，解决 Vue 完成后没有报告的问题。
- `VITE_ANALYSIS_BACKEND=python` 是显式回退；不在 POST 超时后隐式重发。
- PENDING 在排队期间持续显示“投递待确认”，409 携带 taskId 时恢复已有任务。
- 开发/生产 Compose 新增 Java 应用，不新增中间件。Python 原接口与 MCP 保留。

## 已执行测试

| 项目 | 实际结果 |
| --- | --- |
| 前端路由/鉴权/异常单元测试 | 6 项通过 |
| 前端生产构建 | Java 默认模式、Python 回退模式均通过 |
| Java / MySQL / Redis 回归 | 38 项通过，0 失败、0 跳过 |
| Python 原业务与 Agent/RAG 回归 | 106 passed，2 skipped |
| 前端模块经 Vite 的完整业务联调 | 1 个端到端用例通过，多场景断言见下文 |
| Broker 停机，经 Vite | 1 个异常联调用例通过 |
| Broker 停机，经实际 Nginx | 同一异常联调用例通过 |
| 实际 Nginx | 配置校验通过；静态 Vue 页面 200、Python health 正常、Java 鉴权状态/报告正常 |
| 两份 Compose 配置 | 官方 Compose 2.39.4 `config --quiet` 通过 |

Python 两个 skip 是未配置外部 Qdrant 的集成项。初次回归中三个旧测试仍模拟 FFmpeg
命令，而当前实现已经使用 PyAV；仅修正 `test_flow.py` 和 `test_bilibili.py` 的测试
边界。其中 OCR 测试实际生成短视频并验证首帧 PNG；未修改任何 Python 业务实现。

## 真实前端 API 模块链路

测试直接导入生产 `client/src/api.js`，请求通过真实 Vite 代理，不模拟 fetch 响应、
Java、Broker 或 Worker。生成有真实音视频轨道的 MP4，通过原 Python 注册和上传 API：

1. 两分片上传、缺失分片查询、完成合并；再次上传内容去重成功。
2. 两个并发任务请求得到 202 / 409，仅接收一个新任务。
3. Java 持久化、Producer SEND_OK、原 Python Worker 实际接收消息并更新数据库。
4. Java 状态查询返回 COMPLETED 及 371 字符报告，原 Python 报告接口返回相同文本。
5. 原 Python 追问返回 510 字符回答，会话保存 2 条消息，媒体列表可恢复历史。
6. 第二用户读取他人任务和提交他人媒体均为 404。
7. 注入远程 ASR 503 后，真实 Worker 通过 MQ 重试，第三次执行后 FAILED；前端模块
   查询到失败次数和错误。没有直接篡改任务状态来冒充执行失败。
8. 原 FastAPI 创建接口仍能完成同一业务，Java 也能读取该任务的报告。

成功任务：`367f0c14-18e3-43f0-ab2a-fbe372b05fb3`；失败任务：
`ecdf02ee-4411-416a-a7a4-c7eba6cf43b4`；Python 回退任务：
`3eafdd87-f44b-4df7-bbd4-b513dfa92a59`。

Worker 接收日志与任务 ID 对应；成功任务没有走 watchdog。结果保存在
`target/ui-e2e/frontend-result.json` 与 `frontend-integration.log`。

## 真实 Broker 停机

停止隔离 Broker 后，前端生产 API 模块分别经过 Vite 和 Nginx：

- 202 + PENDING，任务仍为 QUEUED。
- 同目标再次提交为 409，返回原 taskId。
- 他人查询为 404，未登录为 401。
- 没有向 `/api` 隐式重发创建任务。

Nginx 路径任务为 `32b963ea-9a3c-4b36-b80e-ea44d63e1d9c`，Vite 路径任务为
`e5fc6ce5-6bc5-409f-b6c6-f75e274c097e`。结果位于
`target/ui-e2e/frontend-outage-nginx.json` 与 `frontend-outage-vite.json`。

## 未验证与遗留问题

- 当前会话浏览器清单为空，创建 IAB 返回 `Browser is not available: iab`。
  **未进行实际浏览器按钮点击、文件选择器或视觉验收。** 已执行的是前端生产 API
  模块、构建产物、代理和真实服务的功能联调，不能替代完整页面交互测试。
- 没有 Docker Engine；已验证 Compose 配置和本机实际 Nginx，未构建/启动 Docker
  镜像。Nginx 本机验证只将 Docker 上游名替换为隔离实例 IP/端口；Docker DNS 与
  服务器 HTTPS 部署未实测。
- ASR 使用本地 HTTP fixture，但音频提取与请求走原 Python 实现；LLM 使用原
  MockProvider，检索 profile 为 lexical。没有运行真实 Whisper/PaddleOCR 权重、
  付费模型或在线 Qdrant，不能声称实际识别质量、模型效果或生产性能已验证。
- Broker 恢复后的自动补偿未覆盖；PENDING 仍依赖原 Worker watchdog。Broker 停机
  后原生客户端的优雅退出问题沿用第二阶段记录，本轮没有重写退出逻辑。

## 修改文件范围

前端：`src/api.js`、`src/apiRouting.js`、`src/App.vue`、`vite.config.js`、
`nginx.conf`、`Dockerfile`、`.env.example`、`package.json`、`README.md`，
以及 `tests/` 下三个 Node 测试和三个 Python/shell fixture。

Java：`AnalysisTask`、`TaskStatusResponse`、`AnalysisTaskMapper`、`AnalysisTaskService`
及对应测试；`scripts/seed_mysql_fixture.py`、`run_worker_smoke.sh`、`LocalRocketMq.java`；
新增 `Dockerfile`、`.dockerignore`，更新模块说明和本记录。

部署与回归：两份 Compose、`deploy/.env.production.example`、根 README、GitHub CI，
以及前述两份 Python 测试文件。没有迁移上传、追问或任何 Python AI 业务。

Java 工程实践包括分层接口、MyBatis 映射、短事务及提交后发送、JWT 兼容与资源隔离、
Redis/MySQL 双层防重、消费端幂等协作、失败状态维护，以及跨语言消息协议集成。
本轮不引入分布式事务、Outbox、新表或额外业务服务。

验收后已关闭本次创建的临时 API、Worker、Broker、Vite、Nginx、ASR fixture、Redis、
MySQL；保留测试日志、结果和样例文件，不删除原业务数据或停止原有实例。
