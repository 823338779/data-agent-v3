# Data Agent V3 单文档长期记忆 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 V3 跨会话使用用户明确保存的默认偏好，并支持查看、更正、遗忘和只读整理。

**Architecture:** PostgreSQL 每个 Agent 一行，用单个 `TEXT` 保存整份正文；一个带版本的事务同时提交记忆和工具回执。Runner 在取件后从真实用户消息绑定授权，把读到的整份正文装入下一请求的系统提示词，对话历史与压缩摘要保持独立。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus/JdbcTemplate、PostgreSQL、JUnit/AssertJ/Testcontainers、现有 Chat Completions 与 SSE 协议。

**Spec:** [2026-11-26-long-term-memory-design.md](../specs/2026-11-26-long-term-memory-design.md)，基线 `data-agent-v3 develop@507c024`。

## 全局约束

- 只在云端 V3 子仓工作，不动主仓子仓指针。首版是单用户 `general`，无条目表、JSON 列、分片、向量库或通用 skill 引擎；不新增运行时依赖。
- 以整份正文 **2000 UTF-8 字节**为上限，计入 LF。缺行是空文本、版本 0；读不建行；删空保留行和递增版本。写冲突不自动换版本重试。
- 记忆只有明确授权才能改；当前用户要求优先于长期偏好。旧聊天不删除，失败工具不得冒充成功。原话来源不能是提示词、历史、工具结果或压缩摘要。
- 不取消在途请求；继续按现有规则消费同 Run 补充。业务请求最多 8 次。最后一次不挂载记忆操作工具，内部摘要无记忆模板、skill 或工具。
- 只运行测试数据库和可控模型，不改用户本地配置或真实记忆。不把未执行的真实模型验证写成通过。此计划不授权推送。

## 工具契约（任务 2–4）

模型可见名称固定为 `readMemory`、`add_memory`、`edit_memory`，区分大小写。参数对象仅允许下表列出的字段，字段全部必填且为字符串（无参工具只收 `{}`）；禁止额外字段、null 和类型转换。Agent 归属、当前 Run、授权用户消息 `messageSeq` 和预期版本均由后端绑定，不能由模型传入。

| 工具及模型看到的说明 | 参数和调用例 | 开放时机与返回 |
| --- | --- | --- |
| `readMemory`：读取当前 Agent 已提交的整份长期记忆，不修改。 | `{}` | 仅在明确查看或整理时挂载；第一次成功后，本 Run 后续只使用最新注入快照，不再调用。成功返回 `content`（整个文本）、`revision`、`remainingBytes`。空库读出空文本、版本 0，不建行。读取失败要说明未能核实，不当成空记忆。 |
| `add_memory`：追加一条当前用户明确授权的完整偏好。 | `{"content":"经营周报金额单位默认用万元"}` | 后端确认允许追加，或确认同一原文的幂等重申时才挂载；不能用追加绕过同范围替换。同文重试返回 `unchanged`，不改正文或版本。 |
| `edit_memory`：精确替换一行；仅在明确要求遗忘时用空新值。 | `{"old_string":"经营周报金额单位默认用万元","new_string":"经营周报金额单位默认用元"}`；删除时 `new_string: ""` | 后端找到完整且唯一的旧行，并绑定允许的新原文或明确删除时才挂载。不能用子串、近义词或模型自己选的目标进行变更。重申同范围同值不写库；实现统一返回 `unchanged`。 |

**返回载荷。** 沿用现有工具结果协议：下列载荷是 `Message.content` 里的 JSON，数值字段用十进制字符串；成功/失败由外层 `Message.success` 区分。不要把整个工具结果包装成新的 memory 记录。

```json
{"content":"","revision":"0","remainingBytes":"2000"}
{"operation":"added","entry":"经营周报金额单位默认用万元","revision":"1","remainingBytes":"1961"}
{"operation":"deleted","entry":"经营周报金额单位默认用万元","revision":"2","remainingBytes":"2000"}
{"code":"FULL","message":"记忆正文容量不足，未保存。","bytesNeeded":"1"}
```

写操作的 `operation` 只允许 `added / updated / deleted / unchanged`；删除的 `entry` 为原文，其余为有效条目。上例是单行正文从空库追加再删回空库；多行的剩余字节数按整份正文含 LF 计算。

| 失败码 | 明确行为 |
| --- | --- |
| `NOT_AUTHORIZED` | 未挂载、参数不合法、与本次精确授权不符、权限已消耗或末次请求：不写；不回显拒绝输入。 |
| `NO_MATCH` | 要改删的旧行已经找不到或不唯一：不猜，提示用户查看现有正文后重新明确指定。若请求已经因版本过期失败，按 `CONFLICT` 处理，不再基于新文本匹配。 |
| `FULL` | 生成的整份正文超过 2000 字节：回执附 `bytesNeeded`，不截断、不淘汰、不递增版本。 |
| `CONFLICT` | 持有过期版本，或合法更正会撞到另一条当前规则：不改。版本竞争应返回当前 `revision`，关闭本 Run 写资格；不能用新版本偷偷重试。 |
| `DEFERRED` | 有更新的待取件补充：不写、不消耗写资格、不撤回已发回复；在下一次现有业务请求取件后重新判断。 |
| `READ_FAILED` | 显式工具读取失败：`success=false`，不返回伪造的空正文；本轮不得声称已核实。 |

**执行顺序：** 精确匹配和受限工具挂载复用同一份后端决策；调用时再次验资格。整段 CAS 和成功回执同事务，`tool.finished` 在提交后发。复用回执以 `(runId, assistantMessageSeq, toolCallId)` 为键，并核对工具名与参数；同 Run 新的模型响应复用 ID 不命中。成功或版本冲突消耗写资格；`FULL` 或精确目标有歧义时提示用户决定，不自动尝试其他写操作。无法可靠解析的自然语言请求先澄清，不挂写工具。

## Skill 装配矩阵（任务 4–5）

以下是业务请求的**静态提示词资源**，不是工具或存储结构。资源从固定 classpath 路径加载，按场景只拼一次；只读流程的结束条件是直接回复用户，不再尝试调用写工具。

| 场景 | 注入哪些 Skill | 额外记忆工具 | 输出与停止条件 |
| --- | --- | --- | --- |
| 日常任务 / 「这次周报用元」 | 无 | 无 | 用适用偏好做默认；本次明确要求优先，不写。 |
| 「你记住了什么？」 | 无 | `readMemory`（本 Run 首次成功读取前） | 展示最新正文；为空就说明没有，读取失败就说明未核实。后续请求使用新注入快照，不循环读。 |
| 「总结这次会话中值得记住的偏好」 | `session-summarize` → `storage-classify` | 无（可用既有同会话原文回查） | 列候选、原话出处、适用范围；无原话则待核。给可复制的单项保存语句并结束。 |
| 「整理长期记忆」 | `memory-organization` → `storage-classify` | `readMemory`（本 Run 首次成功读取前） | 列冲突、重复、过时或归属错误及处理建议；无法确认写「待确认」。提供逐条建议并结束，不因「对」执行批量改删。 |
| 明确保存、更正、遗忘 | `storage-classify` | 后端选定的一个写工具；非读写同时开放 | 先按完整原文和已有快照选择操作，之后只凭真实回执告知结果。没有明确目标就让用户指定，不写。 |
| 「这次忽略记忆」 | 无 | 无 | 当前 Run 用空记忆，不通过旧工具结果恢复已忽略的默认值；数据库内容和版本不变。 |
| 内部摘要 / 第 8 次业务请求 | 内部摘要无记忆模板或 Skill；末次业务请求可保留相关只读说明 | 无 | 内部摘要只压缩会话；末次向用户如实说明仍未执行的写入，需开新 Run，不暗中加第 9 次。 |

**三个资源各自的输入、输出与边界：**

- `backend/src/main/resources/skills/session-summarize/SKILL.md`：输入当前会话中真实用户原话及已有快照；输出「候选 / 原话出处 / 适用范围 / 是否已保存」。只读取本会话，跳过一次性要求、弱确认和模型推断，列完候选即停止。
- `backend/src/main/resources/skills/storage-classify/SKILL.md`：输入候选或指定的操作；判定「长期偏好 / 会话临时条件 / 外部业务资料 / 不应存」。只把长期偏好交回当前流程；不负责修改文本或决定权限。明确写入场景由 Runner 检验后才挂载工具，分类片段本身没有写入能力。
- `backend/src/main/resources/skills/memory-organization/SKILL.md`：输入 `readMemory` 核实的已提交正文；如果工具读取后其他会话更新，以续跑请求的最新快照为准。输出「完整旧文 / 问题 / 建议动作」，未证实过期则不判定删除；无需要改动则说明并停止。任何变更必须由用户之后明确逐项授权。

**装配顺序：** Agent 基础指令 → 一份只读或可写模板 → 一个整段正文字符串 → 场景内固定顺序的 Skill 片段。记忆正文是数据，不能触发加载、开放工具或改变授权。用同一份渲染后指令和工具表预检、发送；当前快照失败不填空白蒙混。

## 评审重点

1. CJK、引号、LF 和转义后预算都须按字节核算；正文的数据库上限和请求预算不是同一件事（任务 1、4）。
2. 相同版本首次插入竞争、删空后旧版本重试，不能造成两份记忆或把版本重置（任务 1）。
3. 同 Run 的新补充与写入竞争，须由同一行锁明确先后，不能在旧授权上误写（任务 2、3）。
4. 回执事务失败不得留下记忆变更；同名工具重放和不同请求复用 call ID 不能串单（任务 3）。
5. 新会话、旧摘要和忽略记忆场景查到的正文不能混用；只读工作流不得擅自改写（任务 4、5）。

---

### 任务 1：整份正文的 PostgreSQL 持久化

**文件：** 修改 `backend/src/main/resources/schema-postgresql.sql`；新增 `backend/src/main/java/com/datagent/v3/memory/MemoryRepository.java` 与 `backend/src/test/java/com/datagent/v3/memory/MemoryRepositoryIntegrationTest.java`。

**接口：** `MemoryRepository.Snapshot(String content, long revision)`；`read(String agentKey): Snapshot`、`lockCurrent(String agentKey): Snapshot`、`replace(String agentKey, long expectedRevision, String content): boolean`。缺行读取返回 `new Snapshot("", 0)`；`replace` 返回是否成功写到指定版本的后继。

- [ ] 写 `readsMissingBodyWithoutCreatingRowAndStoresOneWholeText`：空读无行；写入带 LF、引号和中文的正文后只有一行，读回逐字一致，且数据库列类型为 `text`。
- [ ] 写 `exactUtf8LimitAndDeleteDoNotResetRevision`：`"字".repeat(666) + "\nA"` 恰好 2000 字节，追加 `B` 后数据库拒绝，原版本不变；清空后版本递增，旧版本 0 不能再次插入。
- [ ] 写 `onlyOneWriterCanUseTheSameRevision`：用两个独立事务和屏障，分别验证版本 0 首写、以及已有版本的并发替换都只有一方成功。
- [ ] 运行 `mvn -pl backend -Dtest=MemoryRepositoryIntegrationTest test`，记录红灯。
- [ ] 实现行结构 `agent_memory(agent_key TEXT PRIMARY KEY, content TEXT NOT NULL, revision BIGINT NOT NULL)`，约束 `octet_length(content) <= 2000`、`revision > 0`。以 `INSERT ... ON CONFLICT DO NOTHING` 处理首次，`UPDATE ... WHERE revision = ?` 处理后续。调用方在事务中使用 `lockCurrent` 判断已存在记录的 no-op 与版本冲突；禁止先提交后补写回执。
- [ ] 重跑本任务命令至绿；验证新版 schema 重复初始化不会覆盖旧会话或正文。

### 任务 2：只从真实用户原话确定权限和文本改写

**文件：** 同 memory 源码目录新增 `MemoryIntent.java`、`MemoryPolicy.java`，同测试目录新增 `MemoryPolicyTest.java`；`backend/src/main/java/com/datagent/v3/session/SessionStore.java` 增加服务端归属与写入关口接口。

**接口：**
- `MemoryIntent.parse(String text): Intent`；类型为普通、查看、提炼、整理、忽略、撤销、追加/更新默认、精确更正、精确遗忘、须澄清。
- `MemoryPolicy.authorize(List<UserInput> inputs, Snapshot snapshot, boolean spent): Decision`；`UserInput(int messageSeq, String rawText)`；授权时包含 `Grant(int sourceSeq, long revision, String toolName, Map<String,String> arguments)`，其余返回只读或澄清。保留实际写入作用域，不暴露给模型选择。
- `MemoryPolicy.apply(Snapshot current, Grant grant): Edit`；结果带完整新正文、操作、实际条目及剩余字节；无修改不调用数据库更新。歧义、范围冲突和容量不足各有稳定拒绝结果。
- `SessionStore.memoryOwner(String runId): String` 从该 Run 的会话读取 Agent；`lockMemoryRun(String runId): MemoryRunGuard` 在当前事务锁 Run 行，并返回归属、是否运行中、是否有未取件补充。不得从模型参数读取 Agent 键。

- [ ] 写 `explicitDefaultReplacesOnlyAnExactScope`：同范围万元改元、日报不受影响；相同默认值 `unchanged`；语义近似范围不合并；精确更正不能覆盖另一生效范围。
- [ ] 写 `requiresExplicitCurrentUserTextAndExactTarget`：覆盖弱确认、临时要求、引用、假设、多行、复合、凭据标记、空范围、精确删除及「不要记住」；合法的「默认不展示明细」保留否定词。多条冲突授权澄清；同一 Run 已消耗后不再授权。
- [ ] 写 `budgetFailureDoesNotProposePartialWrite`：直接断言超额所需字节数、新正文未生成；匹配到一行时只改目标行，剩余正文原样保留。
- [ ] 运行 `mvn -pl backend -Dtest=MemoryPolicyTest test`，记录红灯。
- [ ] 实现基于锚定完整句式与原文的授权，允许 spec 的标点变体。全文仍为一个字符串；只为了匹配暂按 LF 迭代，不产生数据条目或 ID。证据是服务端携带的真实消息与 seq，禁止传入模型视图或检查点文本。最新有效撤销可取消尚未执行的写入；冲突不靠模型猜。
- [ ] 重跑本任务测试至绿；补一条可直接使用的澄清命令断言，不依赖真实模型自然语言表现。

### 任务 3：将版本更新与工具回执绑定在同一事务

**文件：** 新增 `backend/src/main/java/com/datagent/v3/memory/MemoryMutationService.java`、`backend/src/test/java/com/datagent/v3/memory/MemoryMutationIntegrationTest.java`；修改 `backend/src/main/java/com/datagent/v3/session/SessionStore.java`。

**接口：** `execute(String runId, int assistantMessageSeq, Message.ToolCall call, Grant grant): MemoryExecution`；`MemoryExecution(Message result, boolean alreadyRecorded, boolean consumesWritePermission)`。现有 `agent_message` 是回执唯一来源。新增按 Run、助手 seq、工具调用位置读已有结果的会话方法（先核对助手消息的 name、arguments 和 call ID，再核对对应工具消息）。使用已登录 Run 的事务上下文，不建第二份回执或条目存储。

- [ ] 写 `receiptFailureRollsBackMemoryAndMessage`：在隔离库注入目标工具回执写入异常，断言正文和回执都不变；取消故障后重试成功。
- [ ] 写 `replayUsesAssistantSequenceAndExactCall`：同一个调用重新分发拿同一结果，记忆版本不重复推进；不同助手 seq 的同一个 call ID 不命中；同 seq 伪造参数不复用。不追加第二条回执。
- [ ] 写 `pendingSteerAndWriteShareRunLock`：独立事务覆盖补充先提交和写先提交。前者 `DEFERRED` 且不耗资格，后者保留已提交写入；最新取消不导致自动撤销。另测版本冲突时返回最新版本，不用新版本再写。
- [ ] 运行 `mvn -pl backend -Dtest=MemoryMutationIntegrationTest test`，确认红灯。
- [ ] 实现顺序：锁 Run → 查同一调用回执 → 校验请求授权/参数 → 检查是否有待取件补充 → 锁并核对记忆版本 → 整段 CAS → 追加同一次工具结果。写入和成功结果同一个 Spring 事务，结束后才能发 SSE。正确处理 `DEFERRED`、`NOT_AUTHORIZED`、`FULL`、`NO_MATCH`、`CONFLICT`；失败结果为 `Message.success=false`。成功（含 unchanged）或版本冲突消耗本 Run 写资格，DEFERRED 不消费。容量失败带 `bytesNeeded`，不回显敏感的拒绝输入。
- [ ] 重跑本任务测试至绿；检查提交后模型断流不会回滚已完成事务（Runner 侧的可见性由任务 4 测）。

### 任务 4：请求快照、工具白名单和上下文边界

**文件：** 修改 `backend/src/main/java/com/datagent/v3/agent/AgentRunner.java`、`ConversationContextService.java`、`ConversationContext.java`、`backend/src/main/java/com/datagent/v3/tool/ToolRegistry.java`；新增 `backend/src/main/java/com/datagent/v3/memory/MemoryRequestAssembly.java` 及 `backend/src/main/resources/prompts/memory-context.md`；具体挂载见「工具契约」及「Skill 装配矩阵」。测试 `backend/src/test/java/com/datagent/v3/agent/AgentRunnerTest.java`、`ConversationContextTest.java`、`backend/src/test/java/com/datagent/v3/tool/ToolRegistryTest.java`。

**接口：** `MemoryRequestAssembly.prepare(Snapshot snapshot, Decision decision, boolean lastBusinessRequest, boolean readAlreadySucceeded): RequestMemory`，返回追加指令及记忆专用工具集合；工具 schema 分别为 `readMemory({})`、`add_memory(content)`、`edit_memory(old_string,new_string)`。扩展 `ToolRegistry` 的请求级工具集合注册与按名校验（保留同会话回查）；`ConversationContextService.prepare` 接收最终 instructions 和工具表，并用其完成预检与发送。

- [ ] 写 `freshWholeSnapshotIsUsedAndBudgetedOnEveryBusinessCall`：在 A 工具等待期间 B 更新，捕获 A 下一次出站指令的新正文；固定包含 CJK、LF、引号和字面 `</memory>`，从单个字符串解出完整正文，近预算时能走现有容量失败而非截掉正文；内部摘要请求无模板及工具。
- [ ] 写 `ordinaryTurnsCannotDispatchMemoryWritesAndLastCallDoesNotMountThem`：模拟未挂载工具、额外 `agent_key`、篡改参数、重复名称及第 8 次请求；任何非法路由不写；不产生第 9 次业务请求。
- [ ] 写 `forgetAndIgnoreDoNotReinstatePreferenceFromHistory`：旧 checkpoint 或工具回执有旧偏好，以当前快照为准；忽略时模板呈空且写工具关闭，历史快照仍可查看。再测 `committedWriteRemainsVisibleWhenAnswerFails`：写后断流，聊天回执已存在，新会话读到已提交记忆。
- [ ] 运行 `mvn -pl backend -Dtest=AgentRunnerTest,ConversationContextTest,ToolRegistryTest test`，确认红灯。
- [ ] 实现：取件后维护真实 user 序号列表，重读已提交正文并确定本次授权。用一个字符串字面量填充正文模板并安全转义，完整渲染后预检；历史工具结果只作为历史，不复用旧正文作为默认。读取异常走明确失败，不当成空。显式查看工具成功一次后省略，当前答案以最近请求快照为准。无效工具请求只产生失败结果；已持久化的记忆回执不走 Runner 的普通追加，提交后发布原 SSE 工具事件。末次请求仅以只读快照回答，有待授权写入时明确未保存。
- [ ] 重跑本任务测试，检查已有普通提问、回查及同 Run 补充的行为不变。

### 任务 5：只读提炼、整理和交付验收

**文件：** 新增 `backend/src/main/resources/skills/{session-summarize,storage-classify,memory-organization}/SKILL.md`；修改 `MemoryRequestAssembly.java`、`README.md`；新增 `backend/src/test/java/com/datagent/v3/memory/MemoryRequestAssemblyTest.java`。

- [ ] 先按「Skill 装配矩阵」建立请求夹具。写 `routesOnlyRequestedSkillsAndNeverWritesFromReadOnlyWorkflows`：普通请求无 skill；提炼附提炼和分类并要求来源；整理附整理和分类且只挂载一次读取；明确写入只加载分类和匹配的写工具；忽略记忆与内部摘要不偷读旧值。分别捕获提炼、整理的最终回复，验证在建议处停止，不产生写工具调用或版本变更。
- [ ] 运行 `mvn -pl backend -Dtest=MemoryRequestAssemblyTest test`，记录红灯；接入固定资源后重跑至绿。不引入运行时技能发现或多资产执行路径。
- [ ] 更新 README，给出保存、查看、临时覆盖、整行更正与遗忘的例子，以及单用户和 2000 字节边界。说明遗忘不删除旧聊天。
- [ ] 执行 `mvn clean verify`、`node --test frontend/test/*.test.mjs`；如果修改了前端，再执行 `npm --prefix frontend ci` 和 `npm --prefix frontend run build`。无 Docker 时用独立 PostgreSQL 测试实例并还原临时测试连接，不跳过事务用例。
- [ ] 在隔离数据库和可控模型上验证 A 保存、B 读取、重启仍可用、B 明确更正后 A 下一次模型调用见新值、遗忘后只剩空正文及新版本。若有已授权的真实兼容模型端点，再抽测「仅本次」「以后默认」「引述他人指令」；无端点则注明未实测语义效果。
- [ ] 核对 `git diff --check`、V3 与主仓 Git 状态，记录通过数量和未验证项目。仅在获得授权后提交或推送。
