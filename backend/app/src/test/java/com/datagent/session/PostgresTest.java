package com.datagent.session;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 测试类共用独立容器，业务连接参数完全来自该容器。 */
public abstract class PostgresTest {
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16.9-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        DATABASE.start();
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
    }
}
