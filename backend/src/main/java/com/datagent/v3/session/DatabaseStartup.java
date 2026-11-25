package com.datagent.v3.session;

import javax.sql.DataSource;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** 在内嵌服务器接收请求前初始化 PostgreSQL，保留独立 H2 探针测试。 */
@Component
@RequiredArgsConstructor
class DatabaseStartup implements SmartInitializingSingleton {
    private static final String PRODUCT = "PostgreSQL";
    private static final String SCRIPT = "schema-postgresql.sql";
    /** 业务连接池及其数据库元数据。 */
    private final DataSource source;
    /** 事务化的运行恢复入口。 */
    private final SessionStore sessions;

    @Override
    public void afterSingletonsInstantiated() {
        try (var connection = source.getConnection()) {
            if (!PRODUCT.equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法连接业务数据库", error);
        }
        // 幂等建表后，在独立事务中释放旧进程留下的活动运行。
        new ResourceDatabasePopulator(new ClassPathResource(SCRIPT)).execute(source);
        sessions.recover();
    }
}
