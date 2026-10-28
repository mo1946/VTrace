# 第二阶段实际验收记录

日期：2026-10-27（Asia/Shanghai）。仅操作本次创建的隔离测试实例和测试库。
原数据库结构、`backend/seeit/worker.py` 和 Python AI 处理代码未改动。

## 自动测试

Java 21.0.3、Spring Boot 3.5.16、MySQL 8.4.10、Redis 5.0.14.1。
使用现有 Alembic 迁移和 Python ORM 创建 `vtrace_business_test_stage2_run2`。
执行 `mvn verify`（指定模块本地 Maven 缓存与测试连接）成功：

| 测试类 | 通过 |
| --- | ---: |
| AnalysisTaskServiceTest | 10 |
| AnalysisTaskMySqlIntegrationTest | 10 |
| AnalysisTaskSubmissionServiceTest | 5 |
| AnalysisTaskCreationMySqlIntegrationTest | 7 |
| PythonTokenAuthenticatorTest | 3 |
| RedisTaskGuardIntegrationTest | 2 |
| RocketMqMessageContractTest | 1 |
| 合计 | **38** |

0 failures、0 errors、0 skipped。报告在 `target/surefire-reports/`。
验证了 6 个并发请求得到 1 个 202 和 5 个 409，数据库只有 1 个对应任务；该测试
禁用 Redis，专门验证数据库唯一约束。另有真实 Redis TTL、原子解锁、撤销和限流验证。
创建接口测试使用模拟 Publisher，MQ 互通另行验证。

## 真实 Java → RocketMQ → 原 Python Worker

环境：Windows Java API + 隔离 RocketMQ 4.9.4 NameServer/Broker，WSL Ubuntu
Python 3.12.3 运行原 `python3 -m seeit.worker`。Python 客户端为 2.0.0，C++ 动态库
2.0.0 的 SHA256 与原项目 Dockerfile 记录一致。

使用独立库 `vtrace_business_test_stage2_chain` 和独立 Redis 实例。Java 使用原 Python
创建的 JWT 提交任务。配置现有 Topic `video-analysis-topic`、Tag `analysis`、消息 Key
taskId 和原 JSON 格式。未使用模拟 MQ、模拟消费者或直接调用 `process_analysis()`。

最终任务：`7dab6b6b-05a5-4bf0-8f62-a20139d8270b`。

- Java API 返回 202、`dispatchStatus=SENT`，Producer 记录 SEND_OK。
- 原 Worker 日志存在该 taskId 的 `task_message_received type=analysis`。
- 原 Worker 记录 `task_message_finished ... outcome=COMPLETED`。
- 数据库为 COMPLETED，attempt_count=1，报告 437 个字符，active_key=NULL。
- Java 状态查询返回 COMPLETED；Worker 释放了 Java 创建的同一把 Redis 锁。
- 本任务没有使用 watchdog。另一次真实消息任务也通过，ID 为
  `8d74b01f-6004-49e4-9972-7aed6947d5d6`。

机器可读证据在 `target/chain-result.json`；进程日志为 `target/api.stdout.log`、
`target/broker.stdout.log`、`target/worker.stderr.log`。

**边界：使用 Python 原 MockProvider 和已保存的 ASR 证据，实际执行证据处理、报告生成
和写回；没有调用真实 ASR/OCR、付费大模型或真实模型驱动的 LangGraph 流程，不能据此
声称模型效果、真实转写质量或生产性能已验证。**

## 真实 MQ 故障

停止本次隔离 Broker 后，对仍在运行的 Java API 再次提交任务。
任务 `4b033d39-bd8a-4b44-b7fb-77da10912cac` 实际返回：

```json
{
  "dispatchStatus": "PENDING",
  "state": "QUEUED",
  "activeKeyRetained": true,
  "duplicateStatus": 409
}
```

确认数据库提交没有因发送失败丢失，重复请求返回相同 taskId。证据在
`target/mq-failure-result.json`。没有把发送失败描述为执行成功。

本次没有验证 Broker 恢复后的自动补偿完成；实现没有新增 Outbox 或 Java 调度器，
排队兜底继续依赖原 Python Worker。为避免误判真实 MQ 链路，联调时 watchdog 阈值
设为 86400 秒。本次新创建任务不可能依靠短时 watchdog 完成。

Broker 停机后的测试 Worker 收到 SIGTERM 后未及时退出，最终只对已核对 PID 的该测试
进程强制结束。未修改原 Worker 退出逻辑；故障状态下的优雅停机没有验证通过。

## 交付范围

可执行应用 JAR 已生成于 `target/business-backend-0.1.0-SNAPSHOT.jar`。
测试用服务端依赖只存在于可选 `broker-test` profile 的 test scope。
本次启动的 API、Worker、Broker、MySQL 和 Redis 在验收后关闭；测试数据和日志留在
模块被忽略的临时目录，不删除业务数据。不迁移登录、上传、媒体管理或其他第三阶段功能。
