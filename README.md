# Data Agent

在 IntelliJ IDEA 中打开 `data-agent/pom.xml` 即可导入工程。产品根 POM 聚合 `backend/`，统一管理 Java 21 和 Spring Boot 4.1.0。`backend/pom.xml` 是七个后端 Java 子模块的 Maven 父工程与聚合入口。React 19 前端位于 `frontend/`，使用 TypeScript、Vite 和 npm 独立构建。

## Java 模块与依赖方向

| `backend/` 下的子模块 | 职责与调用入口 | 直接依赖的 产品模块 |
| --- | --- | --- |
| `model/` | 定义模型消息和 `ModelClient`，负责 Chat Completions 请求编码与流式响应解析。 | 无 |
| `tool/` | 定义 `AgentTool`，通过 `ToolRegistry` 校验可用工具、解析参数并执行调用，提供时间工具。 | `model` |
| `session/` | 定义会话、运行、补充消息和检查点的 `SessionStore` 接口及业务模型。 | `model` |
| `memory/` | 组装长期记忆提示词，判断读写授权，并在同一事务中保存记忆变更与会话回执。 | `model`、`tool`、`session` |
| `agent-runtime/` | 通过 `AgentRunner` 驱动一次运行，协调上下文压缩、历史回查、模型请求和工具调用。 | `model`、`tool`、`session`、`memory` |
| `persistence/` | 实现 `SessionStore` 和 `MemoryRepository`，集中维护实体、Mapper、持久化服务、建表脚本和启动恢复。 | `model`、`session`、`memory` |
| `app/` | 装配 Spring Boot 应用及持久化实现，通过 HTTP 和 SSE 暴露会话与运行入口。 | `agent-runtime`、`session`、`persistence` |

各模块的单元测试随实现存放；需要完整 Spring 应用和数据库的集成测试在 `backend/app/` 运行。可在 data-agent 目录运行 `mvn -pl backend/memory -am test`，只构建长期记忆及其依赖并执行对应的单元测试。

## 后端开发约定

持久化模块在 `com.datagent.persistence` 下按职责分包：`entity` 存放数据库实体，`mapper` 存放 MyBatis Mapper，`service` 存放持久化服务，`mapping` 存放 MapStruct 转换器。检查点和运行视图的映射在编译期生成。后端使用 MyBatis-Plus 3.5.16，长期记忆通过 Mapper 执行带版本条件的更新。在持久化模块内定义 MyBatis Mapper 接口，使用 `@Mapper` 和 `BaseMapper<T>` 获得基本增删改查能力。条件查询优先使用 Lambda Wrapper 引用实体属性。实体按需使用 Lombok 的 `@Getter`、`@Setter`，构造器注入可使用 `@RequiredArgsConstructor`。

单用户会话、运行及完整消息保存在 Docker PostgreSQL 的 `data_agent_v3` 库。业务读写使用 MyBatis-Plus 的 `IService` 与 Lambda Wrapper。后端按单实例启动，接收请求前会释放上次进程中断的运行。

## 本地运行

在工作区根目录执行 `docker compose -f infra/compose/compose.yml run --rm data-agent-db-init` 初始化独立数据库。在 data-agent 目录打开两个终端，给后端进程设置连接密码 `AGENT_DB_PASSWORD`（也可继承 `POSTGRES_PASSWORD`）。默认 JDBC 地址为 `jdbc:postgresql://localhost:5432/data_agent_v3`，账号为 `data_agent`，可用 `AGENT_DB_URL`、`AGENT_DB_USER` 覆盖。后端运行在容器内时使用 Compose 主机名 `postgres`。

默认模型端点为本机 Ollama 的 `http://localhost:11434/v1/chat/completions`，模型名为 `qwen3.5:9b`。也可使用支持流式工具调用的兼容端点，通过后端环境变量 `AGENT_MODEL_ENDPOINT`、`AGENT_MODEL_NAME`、`AGENT_MODEL_TOKEN` 和 `AGENT_MODEL_TIMEOUT` 配置完整请求 URL、模型名、可选凭据及单次超时（默认 120 秒）。

在第一个终端启动后端：

```powershell
mvn -pl backend/app -am compile spring-boot:run
```

第二个终端启动前端（推荐使用 Node.js 24 和 npm）：

```powershell
cd frontend
npm ci
npm run dev
```

打开 Vite 输出的本地地址（默认 `http://127.0.0.1:5173`），发送“UTC 现在几点”，再问“把刚才的时间换算成东京时间”。回复会逐步显示，展开工具条目可查看实际参数与结果。左侧可新建对话，也可按首条提问找回最近有过交互的 50 个会话。窄屏点击左上角按钮展开侧栏。保存含 `session` 参数的网址可直接回到同一会话。

回答开始后，只要本轮仍可补充，输入框就显示「补充本轮」。正在生成的文字和已执行的工具不会撤回；补充在下次业务模型调用前纳入同一轮，即使上一段回答已经结束，也不另开会话或 Run。「等待纳入」表示服务端已收件但尚未写入对话；若本轮中断，未纳入的原文显示为「未使用」，可手动复制重发。每 Run 最多 30 次业务模型请求，开始最后一次请求的取件时关闭补充入口。单条补充最多 2 KiB UTF-8，每 Run 最多 8 条且累计最多 8 KiB。

## 打包

```powershell
mvn clean verify
npm --prefix frontend ci
node --test frontend/test/chat-api.test.mjs
npm --prefix frontend run build
```

后端测试需要运行 Docker，Testcontainers 自动启动隔离的 PostgreSQL，原有 Mapper 探针继续使用 H2。后端产物为 `backend/app/target/app-0.1.0-SNAPSHOT.jar`，前端静态文件输出到 `frontend/dist/`。分别部署时，将 `/actuator/health` 与 `/api/` 转发到后端，并关闭 SSE 路径的代理响应缓冲。

### 长对话与上下文

近期问答直接进入模型上下文。较早的长工具结果归档到 PostgreSQL 表 `agent_context_archive`，上下文保留预览和服务端生成的 `[DATUS_ARCHIVED] path=pg:…` 引用。默认保护最近 4 个历史轮次；轮中预算占用达到 75% 时优先保护最新 5 条工具结果，接近硬预算时优先保留最近一批。普通预览最多 1000 个 UTF-16 单元，失败结果使用双倍预览。

摘要附带压缩前完整会话的 JSONL 归档引用，保留轮次和消息顺序，并沿用长期记忆工具的历史脱敏规则。旧版本保存的摘要在恢复时由原始消息补建归档。跨轮检查点随成功 Run 提交，聊天界面仍展示原始消息。

`AGENT_CONTEXT_WINDOW`（默认 32768）必须按实际端点配置；`AGENT_CONTEXT_ANSWER_TOKENS`（默认 2048）和 `AGENT_CONTEXT_SUMMARY_TOKENS`（默认 768）分别限制普通回复和摘要的输出。预检用协议 JSON 的 UTF-8 字节数加固定余量做保守估算，不等同于端点的精确 token 计数。若原问题或工具调用参数本身太长，且无法安全压缩，返回明确错误。当上下文中出现归档引用时，模型可以调用 `read_archive`，复制 `path` 读取本会话归档。可选参数 `offset`、`limit` 按 Unicode 字符分页，每页最多 1000 个字符，下一页使用返回的 `nextOffset`。整个回读过程只访问 PostgreSQL。上下文压缩节省模型输入，不删除原始数据，也不减少数据库保留容量。

### 每轮模型调用统计

可按 `run_id` 查询 PostgreSQL 表 `agent_run_usage`。每个新 Run 创建一行，`business_calls` 和 `summary_calls` 在发起业务或摘要模型调用时分别加一，失败与中断计入次数。升级前的历史 Run 没有统计行。

`input_tokens` 和 `output_tokens` 分别累加端点实际报告的输入和输出 token，未收到该项报告时为 NULL。`input_reported_calls` 和 `output_reported_calls` 表示各自覆盖的调用数，少于本轮总调用数时，用量为部分合计。统计不参与上下文预算估算。默认发送 `stream_options.include_usage=true`；如果兼容端点拒绝此字段，可设置 `AGENT_MODEL_INCLUDE_USAGE=false`。

### 跨会话长期记忆

长期记忆适用于当前的单用户部署。每个 Agent 在 PostgreSQL 里只存一条记录，整份正文是一个 `TEXT` 字段（最多 2000 UTF-8 字节，包含换行）。新会话会读取最新版本；它和会话历史、上下文压缩摘要相互独立。

| 示例 | 行为 |
| --- | --- |
| 以后经营周报金额单位默认用万元 | 明确保存或更新这个范围的默认单位。 |
| 这次周报用元 | 只覆盖本次，不改变已保存默认值。 |
| 你记住了什么？ | 查看当前 Agent 已保存的整份文本。 |
| 把记忆「经营周报金额单位默认用万元」改为「经营周报金额单位默认用元」 | 精确替换该行。 |
| 忘记「经营周报金额单位默认用元」 | 从生效记忆删除该行，不删除旧聊天。 |
| 这次忽略记忆 | 本轮不使用长期记忆，不清空数据库。 |
| 总结这次会话中值得记住的偏好 / 整理长期记忆 | 只提建议，不自动保存、改写或删除。 |

模型须支持流式工具调用。以真实工具回执为准：若有待处理的补充、容量不足或遇到版本冲突，不能把未发生的变更报告为成功。
