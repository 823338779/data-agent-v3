# Data Agent V3

在 IntelliJ IDEA 中打开 `data-agent-v3/pom.xml` 即可导入 Maven 工程。根 POM 聚合 `backend/` 模块，统一管理 Java 21 和 Spring Boot 4.1.0。React 19 前端位于 `frontend/`，使用 TypeScript、Vite 和 npm 独立构建。

## 后端开发约定

后端持久层使用 MyBatis-Plus 3.5.16。在 `com.datagent.v3` 下定义 Mapper 接口，使用 `@Mapper` 和 `BaseMapper<T>` 获得基本增删改查能力。条件查询优先使用 Lambda Wrapper 引用实体属性。实体按需使用 Lombok 的 `@Getter`、`@Setter`，构造器注入可使用 `@RequiredArgsConstructor`。

本地默认使用 H2 内存数据库，应用停止后数据清空。在 V3 根目录运行 `mvn verify` 会执行 Mapper 集成测试，覆盖主键回填、条件查询、更新和删除。

## 本地运行

在项目根目录打开两个终端。第一个终端启动后端：

```powershell
mvn -pl backend spring-boot:run
```

第二个终端启动前端（推荐使用 Node.js 24 和 npm）：

```powershell
cd frontend
npm ci
npm run dev
```

打开 Vite 输出的本地地址（默认 `http://127.0.0.1:5173`），点击“查看后端健康状态”。开发服务器会将请求转发到后端的 `8083` 端口，正常时响应中的 `status` 为 `UP`。

## 打包

```powershell
mvn clean verify
npm --prefix frontend ci
npm --prefix frontend run build
```

后端产物为 `backend/target/data-agent-v3-0.1.0-SNAPSHOT.jar`，前端静态文件输出到 `frontend/dist/`。分别部署时，前端站点需将 `/actuator/health` 转发到后端。
