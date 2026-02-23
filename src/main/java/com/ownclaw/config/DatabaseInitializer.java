package com.ownclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures the data directory exists before Liquibase / HikariCP try to open the SQLite file.
 * Runs as a BeanFactoryPostProcessor — before any beans (including DataSource) are created.
 */
@Configuration
public class DatabaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Bean
    static BeanFactoryPostProcessor dataDirectoryInitializer(Environment env) {
        return (ConfigurableListableBeanFactory factory) -> {
            String dbPath = env.getProperty("ownclaw.database.path", "./data/ownclaw.db");
            Path parentDir = Path.of(dbPath).getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                try {
                    Files.createDirectories(parentDir);
                    log.info("Created data directory: {}", parentDir.toAbsolutePath());
                } catch (Exception e) {
                    log.error("Failed to create data directory: {}", e.getMessage());
                }
            }
        };
    }
}
