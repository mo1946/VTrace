---
name: vtrace-create-course-notes
description: 基于 VTrace 的 ASR/OCR 时间轴生成课程笔记、章节摘要、概念梳理和复习题。适用于整理课程、讲座或培训视频，并需要通过时间戳回看原始依据的请求。
---

# VTrace 课程笔记

将课程视频整理为可回看的学习材料，保持章节覆盖与证据可追溯。优先采用用户指定的笔记格式和关注范围。

## 连接与视频选择

- 使用已连接的 VTrace MCP。现有连接标识仍是 `seeit-ai`，资源 URI 仍是 `seeit://media/{media_id}/...`；`SEEIT_*` 为兼容配置，不要自行改成不存在的名称。
- MCP 转发用户身份到 Python API，不受前端 Java/Python 路由切换影响。鉴权失败时说明连接问题，不输出 Token，也不绕过权限读取数据库或磁盘。
- 已提供 `media_id` 时直接使用；否则调用 `list_media`，根据名称、来源及用户上下文选择。只有无法唯一确定目标时才询问。
- 输入 BV 号且尚未入库时，调用 `preview_bilibili_video`、`import_bilibili_video`、`get_bilibili_import_status`，导入完成后取 `mediaId`。本地文件需先通过网站上传；MCP 没有分片上传工具。导入任务 ID、视频 ID 和分析任务 ID 不可互换。

## 证据工作流

1. 调用 `get_video_metadata` 确认视频来源和证据数量。证据尚未准备好时，可按本次课程目标启动异步分析，取得可用证据后继续；只有 `SYSTEM` 占位文本时不能当作课程内容。
2. 整理全课笔记时，先用 `get_video_timeline(media_id, start_ms, end_ms, limit)` 按时间范围浏览章节，避免只用关键词命中的片段概括整课。单次最多 200 条；达到上限时缩小时间窗口继续读取并去重，不能声称已覆盖完整视频。
3. 针对概念、定义、公式、对比和例题调用 `search_video_evidence`，默认 `top_k=8`，优先 `sources=["ASR", "OCR"]`。检查返回的 `coveragePlan`、`evidenceSufficiency`（若有），多问句不能只回答其中一项。
4. 对含糊、截断或相互矛盾的命中调用 `get_evidence_window` 补齐语境。型号、数字、公式等需核对 ASR/OCR；OCR 只识别画面文字，不证明图形、动作或完整视觉语义，采样时间也不代表文字精确出现的完整区间。
5. 保留原片段的毫秒时间戳、来源和引用文本。用 `verify_video_citations` 批量校验，参数为 `media_id` 和 `citations=[{"timestampMs": ..., "source": "ASR", "content": "原文"}]`。校验通过后仍检查语义支持、否定词和适用条件，不把文本匹配当作结论正确的充分证明。

## 输出

未指定格式时，提供课程概览、按时间排序的章节笔记、核心概念与例题、易错点、复习清单和复习题；没有证据的部分不强行补齐。

- 事实性结论附可定位的 `[MM:SS]`（必要时 `[HH:MM:SS]`）与 ASR/OCR 来源，引用工具返回的原始片段。
- 区分视频直接陈述、跨片段综合与有依据推断；推断要标明“视频未明确说明”。完全缺乏依据时说明无法确定。
- 复习题与参考答案应建立在已核对的课程内容上；用户要求的额外背景知识单独标为补充，不混入视频原意。
- 说明仅查看了部分时间范围、片段截断或识别不清等实际限制，不将检索未命中直接表述为视频绝对没有讲过。

## 平台报告与追问

用户要求保存到 VTrace，或需要异步准备证据时，调用 `start_video_analysis(media_id, goal)`；目标示例：“整理课程章节、概念定义、例题、易错点及复习题，逐项附原视频时间戳证据”。

使用返回的 `taskId` 调用 `get_analysis_status(task_id)`，轮询间隔不少于 3 秒。遇到重复任务响应时复用已有任务，不重复提交；若没有任务 ID，可用 `get_analysis_report(media_id, goal)` 查找对应任务。`QUEUED`、`PROCESSING`、`RETRYING` 都不是完成；`FAILED` 应报告实际错误，不无限重建。完成后用相同目标读取报告，`answerable=false` 表示证据不足的拒答，不得补造答案。交还控制权时明确给出尚未完成的任务 ID 和状态。

用户继续追问已生成的报告时可使用 `ask_video`；需要恢复历史时使用 `get_agent_memory`。历史回答不是原始证据，新增事实仍需核验。
