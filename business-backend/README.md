# VTrace business-backend（第二阶段 MVP）

Java 21、Spring Boot 3.5.16、MyBatis、MySQL、Redis、RocketMQ Java Producer。
复用原数据库和 Python Worker；不修改表结构、Worker、AI 代码、前端路由或 Compose。

## 模块

`analysis/controller` 提供接口；`AnalysisTaskSubmissionService` 校验、防重和提交后发送；
`AnalysisTaskWriter` 在独立短事务内插入任务并更新媒体状态；`analysis/mapper` 访问现有表。
`auth` 验证 Python JWT；`infrastructure/RedisTaskGuard` 管理共享锁、限流和撤销；
`messaging` 管理 Java Producer；`common` 返回统一错误。

## 启动

需要 Java 21、Maven 3.6.3+ 和已完成原 Alembic 迁移的 MySQL。Java 不执行 DDL。
在仓库根目录执行：

```powershell
cd business-backend
$env:VTRACE_DB_URL = 'jdbc:mysql://127.0.0.1:3307/media_db?characterEncoding=UTF-8&connectionTimeZone=UTC'
$env:VTRACE_DB_USERNAME = 'root'
$env:VTRACE_DB_PASSWORD = '你的数据库密码'
$env:JWT_SECRET = '与现有Python服务完全相同的JWT_SECRET'
$env:REDIS_URL = 'redis://127.0.0.1:6379/0'
$env:ROCKETMQ_NAMESERVER = '127.0.0.1:9876'
mvn spring-boot:run
```

或执行 `mvn package` 后运行 `java -jar target/business-backend-0.1.0-SNAPSHOT.jar`。
默认监听 `127.0.0.1:8080`，用 `VTRACE_PORT` 改端口；原 Python 服务不受影响。
不会自动读取根目录 `.env`。JDBC URL 与 Python 的 `mysql+pymysql://...` 格式不同。
数据库账号需要相关表 SELECT、`analysis_tasks` INSERT、`media_files` UPDATE 权限。

其他配置：`ROCKETMQ_TOPIC` 默认 `video-analysis-topic`；`ROCKETMQ_PRODUCER_GROUP`
默认 `seeit-java-producer`；`ROCKETMQ_SEND_TIMEOUT_MS` 默认 3000；
`MAX_ANALYSIS_ATTEMPTS` 默认 3。生产应用没有分析 Consumer。

## API 与兼容性

两个接口均使用原 Python 登录返回的 Bearer Token。验证 HS256、时间声明、sub、jti、
Redis 撤销记录和用户启用状态。本阶段不新增登录或 Token 签发。

```powershell
$headers = @{ Authorization = 'Bearer 现有Python登录返回的token' }
$goal = [uri]::EscapeDataString('概括视频主要内容和核心观点，并引用有代表性的时间戳证据')
Invoke-RestMethod -Method Post -Headers $headers "http://127.0.0.1:8080/analysis/ai?id=1&goal=$goal"
Invoke-RestMethod -Headers $headers 'http://127.0.0.1:8080/analysis/task-status?taskId=任务ID'
```

- POST 的 `goal` 省略时使用 Python 原默认目标；显式空白返回 400，原始长度超过
  500 个 Unicode 码点返回 422；首尾空白与 Python `strip()` 一致。
- id 缺失或不是数据库整数返回 422。未登录、无效 Token 或禁用用户返回 401。
- 媒体不存在或不属于当前用户返回 404；同一媒体同一目标存在活跃任务返回 409，
  可查到已持久化任务时同时返回原 taskId。
- 新任务返回 202：`{"taskId":"...","message":"任务已提交","dispatchStatus":"SENT"}`。
- GET 返回状态、阶段、次数、进度、错误、时间及 `report`（直接读取原 `result` 字段），
  供 Vue 完成轮询后显示报告；其他用户的任务统一返回 404。
  **相较第一阶段，GET 现在也必须携带 Token。**

## 事务、防重和异常处理

Redis 锁沿用 `analysis:active:{mediaId}:{UTF-8 SHA256前24位}`，值为 taskId，
`SET NX EX 7200`。MySQL `active_key={mediaId}:{完整goalHash}` 复用唯一约束；
Redis 不可用仍由数据库防重。回滚时 Lua 比较 taskId 后解锁，不删除其他请求的锁。
重复键异常在事务结束后查询获胜任务。

任务显式填写 Python ORM 的应用层默认值，状态及阶段 QUEUED、计数 0、UTC 时间。
`plan_json` 初始留空，由原 Worker 生成；Python 计划查询也有回退。任务插入与媒体状态
更新处于同一事务。**事务提交后**发送 MQ：Topic 沿用配置，Tag 为 `analysis`，Key 为
taskId，UTF-8 Body 为 `{"type":"analysis","taskId":"..."}`。

限流沿用 `rate:analysis:{userId}`，20 次/小时；撤销键为 `jwt:revoked:{jti}`。
Redis 不可用时限流退化为单进程计数，撤销检查按原可选 Redis 模式降级，不能保证
这段时间的撤销生效。

MQ 未配置、异常、超时或非 SEND_OK 时仍返回已持久化的任务 ID，`dispatchStatus=PENDING`，
并明确提示投递待确认。保留 QUEUED、数据库活跃键及 Redis 锁；重复提交仍为 409。
超时可能已经送达 Broker，不能删除任务后重建。此方案不是事务消息或 Outbox。
SDK 有限重试后复用现有 Python Worker 的 QUEUED watchdog；它要求 Worker 实际运行。
若 Worker 本身因 Broker 故障未启动，任务会等待恢复。本阶段不增加补偿调度器、
任务重置、回调服务或新表。

## 自动测试

`mvn test` 运行单元测试；没有测试连接时，MySQL/Redis 集成测试显式跳过。
完整测试仅使用独立库，名称必须以 `vtrace_business_test_` 开头。在仓库根目录：

```powershell
$env:VTRACE_TEST_PYTHON_DB_URL = 'mysql+pymysql://root:test_password@127.0.0.1:13307/vtrace_business_test_stage2'
.\.venv\Scripts\python.exe business-backend/scripts/seed_mysql_fixture.py
if ($LASTEXITCODE -ne 0) { throw 'Python fixture 创建失败' }
$env:VTRACE_TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:13307/vtrace_business_test_stage2?characterEncoding=UTF-8&connectionTimeZone=UTC'
$env:VTRACE_TEST_MYSQL_USERNAME = 'root'
$env:VTRACE_TEST_MYSQL_PASSWORD = 'test_password'
$env:VTRACE_TEST_REDIS_URL = 'redis://127.0.0.1:16379/0'
cd business-backend
mvn verify
```

seed 脚本使用原迁移和 Python ORM，创建用户、任务、证据和真实 Python JWT，并写入
`target/python-fixture.json`，不打印 Token。拒绝覆盖既有固定测试任务，重建时选择新库名。
Python URL 中的特殊字符密码需要编码。可用 `VTRACE_TEST_FIXTURE_FILE` 指定样例路径。

38 项测试覆盖读取兼容性、鉴权、归属、Unicode、回滚、6 并发防重、提交后发送、
发送失败、协议及真实 Redis TTL/原子解锁/撤销/限流。创建接口集成测试的 Publisher
是模拟对象，不能单独作为 Java→MQ→Worker 成功证据。

## 真实链路联调

使用另一个隔离测试库，避免 Worker 消费自动测试创建的排队任务。Java 和 Worker
必须共用数据库、Redis 和 Topic。可使用已有 RocketMQ；可选 `broker-test` profile
仅提供 test scope 的隔离 NameServer/Broker，服务端依赖不会进入应用 JAR：

```powershell
mvn -Pbroker-test dependency:build-classpath -Dmdep.outputFile=target/broker-classpath.txt
$cp = Get-Content -Raw target/broker-classpath.txt
javac -cp $cp -d target/broker-test scripts/LocalRocketMq.java
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp "target/broker-test;$cp" LocalRocketMq "$PWD/target/rocketmq-smoke" 127.0.0.1
```

测试端口：NameServer 19876，Broker 11911/11909，HA 11912。跨 WSL/容器时最后一个
参数应是 Java、Worker 均可达的 Broker IP；不要使用各自不同的 localhost。

在具备原 Python 依赖及 RocketMQ C++ 动态库的 Linux 中，设置 `DATABASE_URL`、
`REDIS_URL`、`ROCKETMQ_NAMESERVER`、`ROCKETMQ_TOPIC` 后，从仓库根目录运行：

```bash
sh business-backend/scripts/run_worker_smoke.sh 2> business-backend/target/worker.stderr.log
```

脚本直接执行原 `python3 -m seeit.worker`，固定 Mock/lexical 并延长 watchdog 阈值。
可用 `VTRACE_LINUX_PACKAGES` 指定隔离依赖目录。不要对业务库运行该脚本。
Java 应用运行后，在仓库根目录保留前述测试数据库配置，并设置：

```powershell
$env:VTRACE_TEST_JAVA_URL = 'http://127.0.0.1:18080'
$env:VTRACE_WORKER_LOG = "$PWD/business-backend/target/worker.stderr.log"
.\.venv\Scripts\python.exe business-backend/scripts/smoke_task_chain.py
```

只有 Java 返回 SENT、Worker 日志确认相同 taskId 的 MQ 接收、数据库 COMPLETED、
报告非空、Java 查询 COMPLETED 才通过。设置 `VTRACE_TEST_REDIS_URL` 还会检查 Worker
释放了 Java 创建的锁。历史固定日期的排队样例可能被 watchdog 处理；脚本拒绝把
本次任务的 watchdog 执行算作消息链路成功。该测试不调用真实 ASR/OCR/付费 LLM。

停止隔离 Broker 后设置 `VTRACE_EXPECT_MQ_FAILURE=1` 再运行脚本，验证 PENDING、
QUEUED、活跃键保留及重复提交 409。机器可读结果位于 `target/chain-result.json` 和
`target/mq-failure-result.json`。测试后关闭自行启动的进程，不清理业务数据。

第二阶段记录见 [VALIDATION.md](./VALIDATION.md)，后续前端接入与整体回归记录见
[FRONTEND_VALIDATION.md](./FRONTEND_VALIDATION.md)。Dockerfile 使用 Java 21，生产 Compose
增加 Java 512 MiB 内存上限，单机部署需结合原服务与系统内存一起预算；未做生产容量测试。
