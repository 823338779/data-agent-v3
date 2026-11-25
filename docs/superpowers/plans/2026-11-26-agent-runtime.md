# Data Agent V3 首版通用 Agent 运行时实施计划

> 执行时使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development`，按复选框跟踪。两个仓库均直接使用已有本地目录。

**目标**　在浏览器里完成一次带工具调用的多轮对话，能查看执行过程，刷新或重启后能找回历史。

**架构**　V3 的 AgentRunner 驱动工具循环。模型适配器只负责一次请求，PostgreSQL 保存完整消息和运行状态。HTTP 响应把运行事件通过 SSE 发送给 React，刷新后的数据来自数据库快照。

**技术栈**　Java 21、Spring Boot 4.1.0、Spring MVC、MyBatis-Plus 3.5.16、Docker PostgreSQL、React 19、TypeScript、Vite。

**设计依据**　[已确认的运行时设计](../specs/2026-11-26-agent-runtime-design.md)。

## 全局约束

- 单用户、单后端实例。工具限于 `current_time` 和 `convert_timezone`。模型可见的工具清单与后端执行授权共用一个来源。
- `general` Agent 每个 Run 最多请求模型 8 次。上下文包含最近 10 个成功 Run 和本轮消息。工具 ID、名称、参数与结果必须可还原。
- 业务表归 V3，数据库名为 `data_agent_v3`。共享 Compose 只负责提供数据库。现有 H2 Mapper 测试继续验证原有行为，新增事务及恢复测试使用真实 PostgreSQL。
- 代码注释、字段注释、常量、测试和提交遵循归属仓库规则。实施前复核工作区差异，尤其是根仓库 Compose 与 V3 根 POM 的已有修改。完成计划后按仓库分别提交本计划变更，保留其他工作的暂存状态。

## 重点复核

| 易遗漏的场景 | 通过条件 | 归属 |
|---|---|---|
| 两个浏览器标签同时发送 | 同一会话仅一个事务成功，另一请求得到 409，无多余用户消息 | 任务 2、5 |
| 上次进程在工具调用后退出 | 旧 Run 标记失败，会话可继续，孤立工具调用不进入新上下文 | 任务 2、4 |
| 模型将两个工具的参数分片交错发送 | 按调用位置分别拼接，完整响应后才分派，结果 ID 一一对应 | 任务 3、4 |
| 第八次模型请求仍要求工具 | 不调用该工具，不发第九次请求，只写一个失败终态 | 任务 4 |
| 浏览器收了一半文本就断开 | 后端完成原 Run，刷新快照可还原最终文本与工具条目，无重复执行 | 任务 5、6 |

## 文件与依赖顺序

根仓库路径以 `agent-root/` 为起点，其余路径以 V3 为起点。下文 Java 类路径以 `backend/src/main/java/com/datagent/v3/` 为起点，对应测试位于 `backend/src/test/java/com/datagent/v3/` 同包下。

| 任务 | 新增或修改的主要文件 | 交付边界 |
|---|---|---|
| 1 | 根仓库 `infra/compose/{compose.yml,README.md}`；V3 `backend/pom.xml`、`backend/src/main/resources/{application.properties,schema-postgresql.sql}`、`README.md` | 已有卷上建库，配置业务数据源和三张表 |
| 2 | `session/{SessionStore,SessionMapper,RunMapper,MessageMapper}.java` 及同包对应记录类型；`session/SessionStoreIntegrationTest.java` | 会话事务、历史快照、启动恢复 |
| 3 | `agent/AgentDefinition.java`、`tool/{AgentTool,ToolRegistry,CurrentTimeTool,ConvertTimezoneTool}.java`、`model/{ModelClient,ChatCompletionsClient}.java`；`tool/ToolRegistryTest.java`、`model/ChatCompletionsClientTest.java` | 一个 Agent、两个只读工具、一次流式模型请求 |
| 4 | `agent/{AgentRunner,RunEvent}.java`、`agent/AgentRunnerTest.java` | 受限执行循环与一致的终态 |
| 5 | `api/{SessionController,RunStreamService}.java`、`api/SessionHttpIntegrationTest.java` | 运行与 HTTP 连接独立、会话快照与 SSE 契约 |
| 6 | `frontend/src/{main.tsx,chat.tsx,chat-api.ts,style.css}`、`frontend/vite.config.ts`、V3 `README.md` | 可用的对话页及真实链路验收 |

## 任务 1　让 V3 在 Docker PostgreSQL 上保存数据

- [ ] 在根仓库核对当前 Compose 的 `postgres` 服务和命名卷，实施前运行 `docker compose -f infra/compose/compose.yml config --quiet`。记录 V3 当前 Mapper 测试基线。
- [ ] 按相邻数据库初始化服务的方式增加 `data-agent-v3-db-init`。复用实例、账号环境变量及健康检查，仅在库不存在时建库。更新对应 Compose 使用说明。连续运行初始化两次，均应成功，已有数据库和数据继续可用。
- [ ] 为 V3 backend 引入 PostgreSQL JDBC 驱动，将 H2 保留为测试依赖。运行配置从环境读取连接参数，宿主机与容器内地址分别使用 `localhost` 和 Compose 服务名 `postgres`。令 PostgreSQL 专用脚本及业务恢复逻辑只在相应数据源配置下执行，保持现有 H2 探针测试独立可运行。
- [ ] 在 V3 SQL 脚本中定义 `agent_session`、`agent_run`、`agent_message`，以外键关联，分别约束会话内轮次序号和运行内消息序号唯一。保存工具调用列表及结果的结构化字段，给新增列添加注释。连续启动两次，验证建表幂等；在独立测试库中断言 `count(businessTables) == 3`。
- [ ] 更新 V3 README 的连接方式与测试前提。运行 `mvn -pl backend -Dtest=MapperIntegrationTest test`，原有 CRUD 测试通过。后续 PostgreSQL 测试使用专用测试数据库或隔离 schema，避免测试恢复逻辑触及开发会话。

## 任务 2　保存完整轮次并处理并发与中断

**接口**　`SessionStore.createSession(String agentKey)` 返回 UUID；`beginRun(UUID sessionId, String userText)` 返回已占用会话的 runId；`append(UUID runId, Message message)` 追加完整消息；`context(UUID runId)` 返回有序消息列表；`finish(UUID runId, Message answer)`、`fail(UUID runId, String reason)` 提交终态；`snapshot(UUID sessionId)` 读取页面所需快照。

- [ ] 写 `SessionStoreIntegrationTest`，用 PostgreSQL 两个独立事务竞争同一会话，断言 `accepted == 1`、`storedUserMessages == 1`。构造 11 个成功 Run 和一个失败 Run，断言上下文只包含最近 10 个完整成功轮及当前轮，顺序和工具调用 ID 正确。
- [ ] 运行 `mvn -pl backend -Dtest=SessionStoreIntegrationTest test` 确认失败点后实现 Store。开始事务锁定对应会话行，检查占用、分配轮次、保存用户消息并设置活动运行 ID。结束事务同时写入最终消息或失败原因、更新状态并按原 Run 释放会话。模型网络请求在事务外执行。
- [ ] 增加 `recoversInterruptedRunBeforeAcceptingRequests`，模拟保留已提交的工具请求但没有结果。启动时先修复遗留运行及会话占用，再开放执行入口。断言 `old.status == FAILED`、`activeRunId == null`，且下一轮上下文无旧调用。重复运行该测试类至通过。

## 任务 3　实现工具契约和模型适配器

**接口**　`AgentDefinition` 保存标识、系统指令、模型配置引用及允许工具名。`ToolRegistry.visibleTools(AgentDefinition agent)` 返回模型可见的描述列表，`invoke(AgentDefinition agent, ToolCall call)` 返回带原调用 ID 的 ToolResult。`ModelClient.complete(ModelRequest request, Consumer<String> onText)` 向回调推送文本并返回完整 ModelReply，其中包含文本、工具调用列表和结束原因。

- [ ] 写 `ToolRegistryTest`。固定 Clock 为 `2026-11-26T01:00:00Z`，断言东京结果为 `2026-11-26T10:00:00+09:00`。断言未知时区、缺少偏移、参数校验失败或未授权调用产生错误结果，未授权实现的调用次数为 0。启动时重名工具、配置引用未知工具均报错。
- [ ] 在 `AgentTool` 中定义名称、用途、参数 Schema、参数校验和执行契约，按上述接口实现注册表与两个工具。用 `Clock` 读 UTC，用 `OffsetDateTime` 和 `ZoneId` 保留瞬间并换算目标时区。
- [ ] 写 `ChatCompletionsClientTest`，本地 HTTP 桩交错发送两次调用的参数片段，断言返回两个完整调用及原始 ID；另测中文文本增量、缺失结束帧、截断与请求超时。运行 `mvn -pl backend "-Dtest=ToolRegistryTest,ChatCompletionsClientTest" test`，确认新增断言能暴露缺失行为。
- [ ] 用 Java 21 HTTP 客户端实现一次 OpenAI 兼容 Chat Completions 流式请求。后端注入地址、模型名、凭据及有限的整次请求超时。测试使用本地桩配置，超时覆盖响应体读取。遇到截断或不完整工具参数，整次请求失败。重跑本任务测试，确认 HTTP 桩只收到一次模型请求。

## 任务 4　由 AgentRunner 驱动执行循环

**接口**　`AgentRunner.execute(UUID runId, Consumer<RunEvent> emit)` 消费已创建的 Run。事件带 runId 和单调递增的 seq。文本事件带本轮助手消息序号，工具事件带调用 ID 与名称，终态带最终消息或错误原因。

- [ ] 写 `AgentRunnerTest`，用脚本化模型验证直接回答、连续调用两个工具、同次响应的多个工具调用及参数错误后自纠。断言第二次模型请求含完整调用和对应结果，最后一个工具结果写入后才发起下一次请求。
- [ ] 增加 `stopsOnEighthRequest`，断言 `modelCalls == 8`、`toolCalls == 7`、`failedEvents == 1`。另测空回答、截断和模型流中断，确认失败 Run 的已完成消息可查看且会话已释放。
- [ ] 运行 `mvn -pl backend -Dtest=AgentRunnerTest test` 验证失败后实现循环。分派前用任务 3 的允许列表和校验；成功结束、失败退出均先提交数据库再发布终态。拒绝或参数无效直接产生带原因的 `tool.finished`，可执行工具按 `tool.started`、`tool.finished` 成对输出。
- [ ] 重跑该测试类，检查快速路径和工具路径都只发布一个终态，流式文本与最终消息使用同一消息序号。

## 任务 5　实现 HTTP 快照和不依赖连接存活的 SSE

**接口**　`POST /api/sessions` 返回 `{sessionId}`；`POST /api/sessions/{sessionId}/runs` 接收 `{message}` 并返回 SSE；`GET /api/sessions/{sessionId}` 返回 `{sessionId,activeRunId,runs}`，各轮含状态、有序消息、失败原因。SSE 以 event 字段携带设计中的六种事件名，以 data 携带 JSON。

- [ ] 写 `SessionHttpIntegrationTest`，走真实 HTTP 端口并使用可控模型。空消息返回 400、未知会话返回 404、并发占用返回 409，三者都在 SSE 响应开始前返回。成功请求首条事件为 `run.started`，末条为唯一终态。
- [ ] 在 `SessionController` 校验输入并调用 `beginRun`。在 `RunStreamService` 将 Run 提交给应用管理的执行线程，用 Spring MVC 的 SseEmitter 推送事件。若执行未能提交则调用 `fail` 释放会话。连接写失败只解绑发往该连接的输出，运行继续保存结果。
- [ ] 增加 `disconnectDoesNotCancelRun`：客户端收首帧后断开，放行受控模型，轮询 GET，断言 `status == SUCCEEDED` 且仅一条用户消息和一份最终回答。运行 `mvn -pl backend -Dtest=SessionHttpIntegrationTest test`，同时验证流中执行失败可从快照查询。

## 任务 6　完成聊天页面及端到端验收

- [ ] 在 `chat-api.ts` 实现创建、提交及读取快照三个请求；使用 fetch 流式解码 UTF-8 和 SSE，保留未完成的帧。在 `frontend/test/chat-api.test.mjs` 为解析逻辑加一个跨字节切分中文和跨块切分帧的用例。使用 Node 24 运行 `node --test frontend/test/chat-api.test.mjs`，断言解析后的事件名称、顺序和中文文本与输入一致。
- [ ] 在 `chat.tsx` 实现单会话页面，用 URL 参数保存 sessionId，按 runId 和消息序号更新文本、按调用 ID 更新工具条目；终态以完整消息核对文本。刷新后先读快照，有活动 Run 时轮询至终态。提交后禁用重复发送，遇到断线从快照恢复，避免自动重发用户消息。在 Vite 中将 `/api` 代理到现有 8083 端口，保留健康检查入口。
- [ ] 从 V3 目录运行 `mvn verify`、`npm --prefix frontend ci`、`npm --prefix frontend run build`。在浏览器核对“UTC 现在几点”“把刚才的时间换算成东京时间”，确认可见工具过程，追问引用原时间。用可控模型验收确定顺序，用已配置的真实端点单独验证协议兼容性。
- [ ] 对隔离的测试实例执行两项重启验收：后端在 Run 中途退出后重启，原会话可查可继续；数据库容器使用原测试卷重建后，成功轮次及工具结果仍在。记录实际验证结果，区分真实模型联调与可控模型测试。
- [ ] 核对根仓库和 V3 的状态、差异及暂存区，以本计划的文件和改动块为范围提交。V3 产品实现与共享 Compose 变更分别归各自仓库。建议提交说明为 `feat: 实现 V3 单用户 Agent 运行时` 和 `feat: 初始化 V3 PostgreSQL 业务库`，保持根仓库已有的子模块版本选择。
