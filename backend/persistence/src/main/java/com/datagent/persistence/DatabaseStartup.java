package com.datagent.persistence;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.ClassPathResource;
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
        try (Connection connection = source.getConnection()) {
            if (!PRODUCT.equals(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
            // 整份脚本交给 PostgreSQL 原子执行，保留函数体和迁移块内的分号。
            String schema = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(schema);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException | IOException error) {
            throw new IllegalStateException("无法初始化 PostgreSQL 业务表", error);
        }
        // 幂等建表后，在独立事务中释放旧进程留下的活动运行。
        sessions.recover();
    }
}
