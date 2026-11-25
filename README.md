# Data Agent V3

在 IntelliJ IDEA 中打开 `data-agent-v3/pom.xml` 即可导入 Maven 工程。根 POM 聚合 `backend/` 模块，统一管理 Java 21 和 Spring Boot 4.1.0。React 19 前端位于 `frontend/`，使用 TypeScript、Vite 和 npm 独立构建。

## 后端开发约定

后端持久层使用 MyBatis-Plus 3.5.16。在 `com.datagent.v3` 下定义 Mapper 接口，使用 `@Mapper` 和 `BaseMapper<T>` 获得基本增删改查能力。条件查询优先使用 Lambda Wrapper 引用实体属性。实体按需使用 Lombok 的 `@Getter`、`@Setter`，构造器注入可使用 `@RequiredArgsConstructor`。

单用户会话、运行及完整消息保存在 Docker PostgreSQL 的 `data_agent_v3` 库。业务读写使用 MyBatis-Plus 的 `IService` 与 Lambda Wrapper。后端按单实例启动，接收请求前会释放上次进程中断的运行。

## 本地运行

在工作区根目录执行 `docker compose -f infra/compose/compose.yml run --rm data-agent-v3-db-init` 初始化独立数据库。在 V3 目录打开两个终端，给后端进程设置连接密码 `AGENT_DB_PASSWORD`（也可继承 `POSTGRES_PASSWORD`）。默认 JDBC 地址为 `jdbc:postgresql://localhost:5432/data_agent_v3`，账号为 `data_agent`，可用 `AGENT_DB_URL`、`AGENT_DB_USER` 覆盖。后端运行在容器内时使用 Compose 主机名 `postgres`。

默认模型端点为本机 Ollama 的 `http://localhost:11434/v1/chat/completions`，模型名为 `qwen3.5:9b`。也可使用支持流式工具调用的兼容端点，通过后端环境变量 `AGENT_MODEL_ENDPOINT`、`AGENT_MODEL_NAME`、`AGENT_MODEL_TOKEN` 和 `AGENT_MODEL_TIMEOUT` 配置完整请求 URL、模型名、可选凭据及单次超时（默认 120 秒）。

在第一个终端启动后端：

```powershell
mvn -pl backend spring-boot:run
```

第二个终端启动前端（推荐使用 Node.js 24 和 npm）：

```powershell
cd frontend
npm ci
npm run dev
```

打开 Vite 输出的本地地址（默认 `http://127.0.0.1:5173`），发送“UTC 现在几点”，再问“把刚才的时间换算成东京时间”。可展开工具条目查看实际参数与结果。保存含 `session` 参数的网址可回到同一会话。

## 打包

```powershell
mvn clean verify
npm --prefix frontend ci
node --test frontend/test/chat-api.test.mjs
npm --prefix frontend run build
```

后端测试需要运行 Docker，Testcontainers 自动启动隔离的 PostgreSQL，原有 Mapper 探针继续使用 H2。后端产物为 `backend/target/data-agent-v3-backend-0.1.0-SNAPSHOT.jar`，前端静态文件输出到 `frontend/dist/`。分别部署时，将 `/actuator/health` 与 `/api/` 转发到后端，并关闭 SSE 路径的代理响应缓冲。
