# Data Agent V3 同 Run 补充 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户可以在工具执行或回答已经开始时补充要求，保留已显示的回答，并在同一个 Run 内续答。

**Architecture:** PostgreSQL 收件记录用可空消息序号连接现有会话历史；执行器只在模型请求边界取件，并在数据库锁内决定纯文本响应是阶段性提交还是结束 Run。模型视图保护、排序所有用户原话，浏览器通过现有 SSE 和快照恢复。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、JUnit/AssertJ/Testcontainers、React/TypeScript、Node test runner。

**Spec:** [2026-11-26-run-steering-design.md](../specs/2026-11-26-run-steering-design.md)，已确认的无独立收据状态版本；基于 V3 `0e66277`。

## 全局约束

- 只改云端 `data-agent-v3`，不动主仓子仓指针；不新增产品运行时依赖。不实现中断上游请求、撤回回答、重启续跑或历史分页。
- 单实例；每个会话仍只有一个活动 Run；一名执行器负责该 Run 的消息写入和 SSE。补充保留原文与顺序；最多 8 次业务模型调用，第 8 次取件时关闭，不因首个 delta 关闭；摘要调用不取件、不计入业务次数。
- 单条最多 2 KiB UTF-8，每 Run 最多 8 条且累计最多 8 KiB；幂等重试不计入配额。收件无独立状态，失败或重启不更新收件表。
- 回归使用真实 PostgreSQL、可控模型与事务屏障，不依赖真实模型端点。以下全路径以 `data-agent-v3/` 为根；仅写计划不是实施或推送授权。实施后如获授权，检查变更范围，再提交及非强制推送。

## 评审重点

1. UTF-8 多字节输入、精确边界及重复请求不能绕过配额（任务 1）。
2. 写消息后回填失败必须整体回滚；收件不能指向另一 Run 的消息（任务 1）。
3. 已提交阶段回答之后，后续预检失败不能把上一段再写一次；检查点不得推进（任务 3）。
4. 摘要失败或真实用户原文过大时，不能用省略补充来规避容量校验（任务 2）。
5. 切换会话或赶上旧事件时，不能覆盖新草稿、清掉已知 seq 或重新打开终态 Run（任务 4）。

---

### 任务 1：以 PostgreSQL 事务确定收件、取件和关闭

**文件**
- 修改：`backend/src/main/resources/schema-postgresql.sql`、`backend/src/main/java/com/datagent/v3/session/RunEntity.java`、`RunMapper.java`、`SessionStore.java`（后两者同属该 session 目录）。
- 新增：同目录 `SteerEntity.java`、`SteerMapper.java`、`SteerPersistence.java`。
- 测试：`backend/src/test/java/com/datagent/v3/session/SessionStoreIntegrationTest.java`。

**接口**（以下 DTO 定义在 `SessionStore`）
- `SteerReceipt(String runId, UUID clientMessageId, String message, String runStatus, Integer messageSeq)`；`SteerAcceptance(SteerReceipt receipt, boolean created)`。
- `acceptSteer(String sessionId, String runId, UUID clientMessageId, String text): SteerAcceptance`。业务拒绝明确区分 `ID_REUSED`、`STEER_CLOSED`（含原因）和 `STEER_LIMIT`，供任务 3 映射 HTTP。
- `drainSteers(String runId, boolean lastBusinessRequest): SteerDrain`，`SteerDrain(List<SteerReceipt> incorporated, boolean closedAtLimit)`；列表按提交顺序，取件后 seq 非空。
- `commitTextReply(String runId, Message reply, Checkpoint checkpoint): ReplyCommit`，`ReplyCommit(int messageSeq, boolean terminal)`。从同一事务返回真实 seq 及是否终态。
- `append(String runId, Message message): int` 返回实际 seq，沿用单写者约束。快照 `RunView` 在已有字段后添加 `boolean canSteer`、可空 `String steerCloseReason` 和 `List<SteerReceipt> steers`；本任务不暴露新的写入路由。

- [ ] 写红灯测试 `acceptanceUsesRunOwnershipAndStableId`：同一 UUID+原文并发请求只有一条记录，首个 created=true；错会话不回显收件；同 UUID 改原文冲突；终态后同文查回原收件，新 UUID 拒绝。
- [ ] 写 `rejectsUtf8LimitsWithoutChargingRetries`：2048 字节接受、2049 字节拒绝，至少一条多字节输入按字节计；8 条后第 9 条拒绝，累计 8192 字节后即使未满 8 条也拒绝；旧 ID 仍可重试。
- [ ] 写 `drainWritesInFifoAndRollsBackOnReceiptUpdateFailure`：在独立事务注入回填异常，断言消息和 seq 一起回滚；移除故障后重新取件，每条只写一次，序号连续且属于原 Run。
- [ ] 写 `closeAndLastDrainSerializeAgainstAcceptance`：用两个独立事务和屏障覆盖接收先/收尾先、接收先/第 8 次取件先。收件先于收尾则 `terminal=false` 且锁未释放给下一 Run；收尾先或预算关门先都拒绝新 ID。不要靠 sleep 排序。另测 `recoverLeavesUnreadReceiptUnchanged`：旧 Run 结束、未分配 seq 的原文原样保留、已纳入 seq 不变。
- [ ] 运行 `mvn -pl backend -Dtest=SessionStoreIntegrationTest test`，记录缺少契约或新断言失败。Docker 不可用时用专用 PostgreSQL 16.9 测试实例，只改临时连接夹具并在结束后还原；绝不连开发或生产数据。
- [ ] 实现：无独立 `state` 列，`agent_run` 加可空 `steer_close_reason`；用 `ADD COLUMN IF NOT EXISTS` 兼容已有表。收件表加 `(run_id, client_message_id)` 唯一、`(run_id, id) WHERE message_seq IS NULL` 部分索引，以及可空 `(run_id, message_seq)` 外键对应现有消息唯一键。锁目标 Run 行后顺序处理归属、旧收件、关门、配额。取件与回填一个事务；纯文本收尾追加一次回答再查待件，无待件才提交终态、检查点和释放会话；失败收尾同样锁 Run 行，启动恢复在开始接请求前结束旧 Run，不更新收件。快照对该页已有 Run 批量查询收件，保留一致的只读事务，不做逐 Run 查询。
- [ ] 重跑同一命令至绿；同时校验幂等 DDL 能作用于已有 schema、原字段与序号未变。

### 任务 2：补充进入有序模型视图，压缩不动原话

**文件**
- 修改：`backend/src/main/java/com/datagent/v3/agent/ConversationContext.java`、`ConversationContextService.java`、`AgentDefinition.java`。
- 测试：`backend/src/test/java/com/datagent/v3/agent/ConversationContextTest.java`；沿用现有请求估算测试方式，实际出站场景由任务 3 验证。

**接口**
- 保留 `appendBatch(int firstSeq, List<Message> batch)`；新增 `appendUser(int seq, Message message)`、`appendReply(int seq, Message message)`，要求真实 seq 严格递增。续用 `prepare` 和 `checkpoint` 的调用方式。保留含 `turn`、`firstSeq` 的来源信息供压缩与回查。

- [ ] 写 `compactsBetweenUsersWithoutMovingOrDroppingThem`：首问、工具 A、补充、工具 B、补充的实际模型顺序正确；触发重度压缩后两条补充原文仍以 user 角色出现，A 与 B 不跨补充合并；保留下来的 tool 引用可匹配 assistant 工具调用。
- [ ] 写 `keepsProtectedTextWhenSummaryFailsOrBudgetIsTooSmall`：摘要失败且轻压缩后仍超限，或仅用户原文加固定开销就超限，明确报容量失败而非发送缺失补充的请求；生成的续跑提示不被识别为客户原话。再测正文包含字面 `[本轮补充]` 仍只由服务端的首问标签确定类别。
- [ ] 运行 `mvn -pl backend -Dtest=ConversationContextTest test` 并确认红灯。
- [ ] 实现：本轮用按 seq 排序的用户项、工具批次与已提交纯文本片段；仅在同一用户分隔区间内折叠完整助手片段，摘要就地替换并保留来源范围。首问、补充及历史标识只存在于出站视图，原文、消息序号和原文回查不变；用加过标签的协议执行现有容量检查。单次模型输入中序号标识来源于服务端，用户正文里的同名标记不产生边界。
- [ ] 重跑同一命令至绿；现有无补充的跨轮、轻度压缩和轮内续跑测试保持通过。

### 任务 3：在同一 Run 串起收件、续答和 SSE

**文件**
- 修改：`backend/src/main/java/com/datagent/v3/api/SessionController.java`、`backend/src/main/java/com/datagent/v3/agent/AgentRunner.java`、`RunEvent.java`（同属 agent 目录）。
- 测试：`backend/src/test/java/com/datagent/v3/agent/AgentRunnerTest.java`、`backend/src/test/java/com/datagent/v3/api/SessionHttpIntegrationTest.java`。

**接口**
- `POST /api/sessions/{sessionId}/runs/{runId}/steers`，请求 `SteerInput(UUID clientMessageId, String message)`。首收 `202`，重试 `200`；`400/404/409/422` 及错误代码按 spec，关闭原因单独可解析。
- SSE 保留 `seq`，追加 `run.steer_gate`（`canSteer`、`steerCloseReason`）、`run.steer_incorporated`（`clientMessageId`、`text`、`messageSeq`）、`run.reply_committed`（`text`、`messageSeq`）。已有 `run.completed` 是唯一成功终态事件。

- [ ] 写红灯 `steersAfterFirstDeltaWithoutStartingAnotherRun`：真实 HTTP 先收首个 delta，再提交补充；可控模型在首个请求完成前检查页面未收到 completed。最终同一 runId 下有「原回答 → 补充 → 续答」，`run.reply_committed` 先于纳入事件，续答的出站模型请求包含原回答与补充，completed 仅一次。
- [ ] 写 `steersOnlyBetweenCompleteToolBatches`：工具屏障中两次收件，捕获下一次业务模型请求和调用次数，完整调用结果在两条补充前，工具恰好执行一次；内部摘要请求不取件。
- [ ] 写 `failsAfterCommittedReplyWithoutDuplicatingPartialOrCheckpoint`：保存阶段回答，取件后制造预算或模型失败；已有段落只一份，已纳入原文在历史可见，失败轮不进入下一轮模型历史，跨轮检查点不推进。
- [ ] 写 `eighthRequestIsLastAndDoesNotExecuteReturnedTools`：第 8 次前待件可纳入，关门后新 ID 返回 `409 STEER_CLOSED`/`LIMIT`；纯文本路径完成，另一条返回工具调用则失败且该批不执行，没有第 9 次业务请求。测试无 delta 路径与原 `409` 活动 Run 约束。
- [ ] 运行 `mvn -pl backend -Dtest=AgentRunnerTest,SessionHttpIntegrationTest test`，确认新场景失败。
- [ ] 实现：每次请求先取件，在提交后按实际消息 seq 追加到任务 2 的视图、发布事件，再压缩、预检和调用模型。完成整个工具批次才回到取件；纯文本由 `commitTextReply` 区分阶段和终态，提交后清空本段 partial，不撤回任何 delta。阶段事件和终态事件均复用提交的真实回答位置；收件箱中没有待件才不再循环。SSE 断开仍让后台 Run 继续。写入接口直到此任务才开放，避免只有收件、没有执行器。
- [ ] 重跑同一命令至绿；核对普通问答 SSE 与原验收一致，首问只 POST 一次。

### 任务 4：浏览器输入、回执合并和恢复

**文件**
- 修改：`frontend/src/chat-api.ts`、`frontend/src/chat.tsx`；新增 `frontend/src/steer-state.ts`（纯合并规则，不引入状态管理库）；有需要时修改 `frontend/src/style.css`。
- 测试：`frontend/test/chat-api.test.mjs`、`frontend/test/steer-state.test.mjs`（沿用已有 Node 测试的源码加载方式）。

**接口**
- `submitSteer(sessionId: string, runId: string, input: { clientMessageId: string; message: string }, signal?: AbortSignal): Promise<SteerReceipt>`；返回输入所属 Run 的最新收件。
- `mergeSteerReceipt(previous: SteerReceipt | undefined, incoming: SteerReceipt): SteerReceipt`；只合并相同 Run/UUID，保留已知非空 seq、已知终态。
- `deriveComposerMode(activeRunId: string | null, activeRun: Run | undefined, busy: boolean, unresolved: boolean): 'send' | 'steer' | 'disabled'`；Run 存在且开放时允许 steer，最后请求、未知活动 Run、切换会话和当前提交未确认时禁用，普通草稿仍可编辑。
- `deriveSteerDisplay(receipt: SteerReceipt, runStatus: string): 'message' | 'waiting' | 'unused'`；只由序号和 Run 状态推导，不保存这三个值。

- [ ] 写红灯 `lateReceiptDoesNotEraseSequenceOrTerminal`、`streamingDoesNotCloseComposerButLimitDoes`：迟到 202 的 null seq 不抹除已纳入的 seq；旧 RUNNING 或 OPEN 不能覆盖已知终态或 LIMIT；阶段回答不会触发下一 Run。
- [ ] 写 `retriesOriginalRunAndUuidAfterLostResponse`：网络失败后保持未知，快照若已有相同 UUID 则确认；否则用原 Run/UUID 重试，终态同 ID 回执仍能找回，不再 POST 首问。测发送后继续编辑、切换会话、刷新恢复和明确拒绝保留草稿。
- [ ] 运行 `node --test frontend/test/chat-api.test.mjs frontend/test/steer-state.test.mjs` 并确认红灯。
- [ ] 实现：补充前冻结快照 `(sessionId, runId, UUID, message)`；用标签页级 sessionStorage 仅保存未确认提交，解决刷新后仍可重试，确认或明确拒绝即清理。收据键与真实气泡键分开；纳入后只以消息 seq 渲染一次。活动快照只覆盖已提交部分，保留当前流式尾巴；终态快照关闭入口并替换临时尾部。确认仅清除本次输入，不覆盖后续输入或其他会话的草稿。旧会话事件只合并到原会话。
- [ ] 重跑两份 Node 测试；运行 `npm --prefix frontend run build`，类型检查与打包均成功。

### 任务 5：合并验收与交付

**文件：** `README.md` 的使用与验证段落。记录「补充本轮」窗口、等待/未使用含义、8 次业务请求限制和不自动新开 Run，不新增另一套启动流程。

- [ ] 在当前 V3 工作区按 README 执行 `mvn clean verify`、`npm --prefix frontend ci`、`node --test frontend/test/*.test.mjs`、`npm --prefix frontend run build`。记录实际通过数和环境替代方案，不能用编译通过代替集成测试。
- [ ] 使用隔离数据库和可控模型验证：收到流式段落后补「也给我北京时间」，原 UTC 回答保留、同 Run 内出现补充和续答；在工具等待期间补充一次、丢失回执后重试一次、刷新一次。检查只有一个 Run、同一 UUID 只有一条收件和气泡。若浏览器环境不可用，报告未跑交互验证，不将 HTTP 测试写成 UI 实测。
- [ ] 自查 `git diff --check` 和 `git status --short --ignore-submodules=none`，核对未混入其他子仓或用户改动。总结行为、测试与未验证项。未得到授权前不提交或推送。
