plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ownclaw"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Liquibase
    implementation("org.liquibase:liquibase-core")

    // Jackson (JSON)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // HTTP client for LLM APIs + Telegram Bot API (direct HTTP, no heavy SDK)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JWT (auth tokens)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // BCrypt (password hashing)
    implementation("org.mindrot:jbcrypt:0.4")

    // HTML parsing (stripping tags, extracting text from web pages)
    implementation("org.jsoup:jsoup:1.18.3")

    // Logging
    implementation("org.springframework.boot:spring-boot-starter-logging")

    // Dev tools
    developmentOnly("org.springframework.boot:spring-boot-devtools")
}
