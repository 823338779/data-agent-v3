package com.datagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Data Agent 的 Spring Boot 启动入口。 */
@SpringBootApplication
public class DataAgentApplication {

    public static void main(String[] args) {
        // 启动 Spring 容器和内嵌 Web 服务。
        SpringApplication.run(DataAgentApplication.class, args);
    }
}
