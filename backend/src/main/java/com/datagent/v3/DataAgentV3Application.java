package com.datagent.v3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Data Agent V3 的 Spring Boot 启动入口。 */
@SpringBootApplication
public class DataAgentV3Application {

    public static void main(String[] args) {
        // 启动 Spring 容器和内嵌 Web 服务。
        SpringApplication.run(DataAgentV3Application.class, args);
    }
}
