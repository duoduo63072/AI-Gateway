package com.company.aigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

/**
 * 扫描根放宽到 {@code com.company}，以纳入与网关平级的虚拟员工模块
 * （{@code com.company.digitalworker}）。三处扫描各司其职：组件 / 配置属性 / R2DBC 仓库。
 * 网关侧（{@code com.company.aigateway}）仍照常被覆盖，行为不变。
 */
@SpringBootApplication(scanBasePackages = "com.company")
@ConfigurationPropertiesScan("com.company")
@EnableR2dbcRepositories("com.company")
public class AiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}
