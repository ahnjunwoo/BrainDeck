plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
}

group = "com.braindeck"
version = "0.0.1-SNAPSHOT"

// Spring Boot 3.3 dependency-management가 Testcontainers core를 1.19.x로 강제하는 것을 override.
// (core만 구버전이면 docker-java가 Docker Desktop 29.x 소켓에서 Status 400)
extra["testcontainers.version"] = "2.0.5"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // UUIDv7 (DDL 규칙: 앱 생성 ID)
    implementation("com.fasterxml.uuid:java-uuid-generator:5.0.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // Testcontainers 2.x — BOM으로 core 포함 전체 버전을 통일한다.
    // (Boot 3.3 BOM은 core를 1.19.x로 강제 → 섞이면 docker-java 구버전이 Docker 29에 Status 400)
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // 컨텍스트 경계 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> { useJUnitPlatform() }

kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }
