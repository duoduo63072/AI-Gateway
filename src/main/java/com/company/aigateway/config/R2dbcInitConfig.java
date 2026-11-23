package com.company.aigateway.config;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;

/**
 * Dev 环境启动时执行 schema.sql（幂等）。生产建议替换为 Flyway/Liquibase。
 */
@Configuration
public class R2dbcInitConfig {

    @Bean
    public ConnectionFactoryInitializer connectionFactoryInitializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        // Optional private seed data. The repository version must not contain real identities or key hashes.
        populator.addScript(new ClassPathResource("seed-employees.sql"));
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
