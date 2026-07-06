# 1단계 — 기반 골격 구현 계획 (docker-compose + Flyway 스키마 + 잡큐 폴링 골격)

> **에이전트 작업자용:** 필수 서브스킬 — 이 계획은 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans로 태스크 단위로 실행한다. 각 스텝은 체크박스(`- [ ]`)로 추적한다.

**목표:** 실행 가능한 기반을 세운다 — docker-compose로 Postgres 16 + pgvector, Flyway 전체 스키마(`sources` / `chunks` / `chunk_embeddings` / `ingest_jobs` + pgvector·pg_trgm 인덱스), 그리고 Kotlin(`embed`/`enrich`)·Python(`parse`) 양쪽의 `FOR UPDATE SKIP LOCKED` 잡 클레임 + 폴링 골격(스텁 핸들러 포함).

**아키텍처:** 단일 Postgres가 잡 큐 포함 모든 것을 담는다. Kotlin/Spring 앱이 스키마 소유자(부팅 시 Flyway 마이그레이션)이자 `embed`/`enrich` 잡 폴러, Python 워커는 `parse` 잡 폴러다. 양쪽 모두 `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING ...`로 잡을 원자적으로 클레임한다. 이 단계에서 핸들러는 **스텁**(로그 남기고 `done` 처리)이며, 실제 파싱/청킹/임베딩/enrich는 이후 단계에서 붙인다.

**아키텍처 스타일:** Modular Monolith + DDD + Hexagonal (CLAUDE.md 참조). 이 단계에서 손대는 컨텍스트는 주로 `ingest`(잡 큐)이며, 스키마는 `knowledge` 컨텍스트의 테이블 전부를 미리 만든다. `common`에 `IdGenerator`(UUIDv7)를 둔다.

**기술 스택:** Kotlin 2.0 + Spring Boot 3.3(JDK 21, Gradle Kotlin DSL), `NamedParameterJdbcTemplate`(잡 큐는 JPA 아닌 native SQL — `SKIP LOCKED` 제어), Flyway(`flyway-core` + `flyway-database-postgresql`), Postgres 16(`pgvector/pgvector:pg16`), `java-uuid-generator`(UUIDv7), Python 3.12 + `psycopg` 3, Testcontainers(JUnit5 + `testcontainers-python`), ArchUnit.

## 전역 제약 (Global Constraints)

- JDK **21**, Gradle **Kotlin DSL**, **Spring Boot 3.x**(스펙 §2 확정 — SignalDeck의 4.x를 따르지 않는다).
- Python **3.12**, 워커는 **파싱 전용** — Python에 청킹/임베딩/태깅/도메인 로직 금지(이 단계에선 스텁이지만 골격이 이를 유도하지 않게 한다).
- 저장소는 **PostgreSQL 16 + pgvector 단일 DB**. 잡 큐 = Postgres 테이블 + `FOR UPDATE SKIP LOCKED` 폴링. MQ·별도 벡터DB·ES 금지.
- 워커 폴링 주기 **5초**. `attempts` **3회** 후 `failed`.
- 임베딩 컬럼은 **`vector(1024)`** 고정(Voyage `voyage-3`). 재임베딩 = `(chunk_id, model)` 새 행 INSERT, 절대 UPDATE 금지.
- `sources.raw_content`, `chunks.content`는 생성 후 **불변**.
- **DDL 규칙(SignalDeck ADR-0007):** 테이블 snake_case 복수형, enum은 **VARCHAR + CHECK**, 모든 timestamp **TIMESTAMPTZ에 DB default `now()` 미사용**(앱 주입), 도메인 ID = **앱 생성 UUIDv7**(DB default 없음).
- **영속성 하이브리드:** 엔티티 CRUD는 JPA(이 단계엔 엔티티 없음 — 이후 단계), 벡터/큐/RRF는 JdbcTemplate. 이 단계는 잡 큐라 JdbcTemplate만 사용.
- 통합 테스트는 **Testcontainers**, 컨텍스트 경계는 **ArchUnit**으로 검증.
- **Kotlin 앱이 스키마 유일 소유자**(Flyway 실행). Python 워커는 마이그레이션하지 않고 행만 읽고 쓴다.

### 설계 노트 (문서화된 제약, 차단 아님)
`chunk_embeddings.embedding`은 `(chunk_id, model)`로 키가 잡힌 단일 고정차원 `vector(1024)` 컬럼이다. 1024차원 모델끼리는 공존 가능하나, 다른 차원 모델(예: OpenAI 3072)은 이 컬럼을 공유할 수 없어 향후 스키마 마이그레이션(별도 컬럼/테이블)이 필요하다. 스펙상 MVP에서 수용한다.

---

## 파일 구조

```
BrainDeck/
  CLAUDE.md                              # 완료됨 (하네스 규칙)
  docs/spec/mvp-spec.md                  # 완료됨 (SoT)
  docker-compose.yml                     # postgres + api + worker
  .env.example
  api/                                   # Kotlin/Spring Boot (스키마 소유자)
    build.gradle.kts
    settings.gradle.kts
    gradle/wrapper/…                      # 생성됨
    Dockerfile
    src/main/kotlin/com/braindeck/
      BrainDeckApplication.kt
      common/IdGenerator.kt                            # UUIDv7
      ingest/domain/IngestJob.kt                       # JobKind/JobStatus enum, ClaimedJob
      ingest/application/port/JobHandler.kt            # 잡 종류별 처리 계약
      ingest/application/service/JobProcessor.kt       # pollOnce 오케스트레이션
      ingest/application/scheduler/JobScheduler.kt     # @Scheduled 5s
      ingest/infrastructure/JobRepository.kt           # 클레임/완료/실패 (JdbcTemplate)
      ingest/infrastructure/handler/StubEmbedJobHandler.kt   # 스텁
      ingest/infrastructure/handler/StubEnrichJobHandler.kt  # 스텁
    src/main/resources/application.yml
    src/main/resources/db/migration/V1__init.sql
    src/test/kotlin/com/braindeck/
      AbstractPostgresTest.kt                          # Testcontainers 베이스
      MigrationTest.kt                                 # 스키마/확장 검증
      architecture/ArchitectureTest.kt                 # ArchUnit 경계 규칙 골격
      ingest/JobRepositoryTest.kt                      # 클레임 로직 TDD
      ingest/JobProcessorTest.kt                       # 폴링 로직 TDD
  worker/                                # Python 파싱 워커
    pyproject.toml
    Dockerfile
    braindeck_worker/__init__.py
    braindeck_worker/db.py
    braindeck_worker/queue.py            # 클레임/완료/실패 SQL (유일 소유)
    braindeck_worker/handlers/__init__.py
    braindeck_worker/handlers/parse.py   # 스텁
    braindeck_worker/worker.py           # 폴링 루프 엔트리
    tests/__init__.py
    tests/test_queue.py                  # 클레임 로직 TDD (testcontainers)
  scripts/smoke.sh
```

각 파일은 단일 책임을 가진다. `queue.py` / `JobRepository.kt`가 각 언어의 잡 클레임 SQL을 **유일하게** 보유한다 — 핸들러는 잡 행 SQL을 만지지 않는다.

---

### Task 1: Kotlin 골격 + docker-compose Postgres + 전체 스키마 마이그레이션 + common.IdGenerator + ArchUnit/Testcontainers 골격

**파일:**
- 생성: `docker-compose.yml`, `.env.example`
- 생성: `api/settings.gradle.kts`, `api/build.gradle.kts`
- 생성: `api/src/main/kotlin/com/braindeck/BrainDeckApplication.kt`
- 생성: `api/src/main/kotlin/com/braindeck/common/IdGenerator.kt`
- 생성: `api/src/main/resources/application.yml`
- 생성: `api/src/main/resources/db/migration/V1__init.sql`
- 생성: `api/src/test/kotlin/com/braindeck/AbstractPostgresTest.kt`
- 생성: `api/src/test/kotlin/com/braindeck/architecture/ArchitectureTest.kt`
- 테스트: `api/src/test/kotlin/com/braindeck/MigrationTest.kt`

**인터페이스:**
- 소비: 없음(첫 태스크).
- 생산: 부팅 가능한 `com.braindeck.BrainDeckApplication`; `sources`·`chunks`·`chunk_embeddings`·`ingest_jobs` 테이블과 `vector`·`pg_trgm` 확장을 가진 마이그레이션된 스키마; 재사용 가능한 테스트 베이스 `AbstractPostgresTest`(Flyway 적용된 pgvector Testcontainer + autowired `NamedParameterJdbcTemplate`); `common.IdGenerator.newId(): UUID`(UUIDv7); ArchUnit 경계 규칙 골격.

- [ ] **Step 1: Gradle 프로젝트 파일 생성**

`api/settings.gradle.kts`:
```kotlin
rootProject.name = "braindeck-api"
```

`api/build.gradle.kts`:
```kotlin
plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.spring") version "2.0.20"
}

group = "com.braindeck"
version = "0.0.1-SNAPSHOT"

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
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // 컨텍스트 경계 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> { useJUnitPlatform() }

kotlin { compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") } }
```

참고: JPA(`spring-boot-starter-data-jpa`)는 이 단계에 엔티티가 없어 넣지 않는다(YAGNI). 엔티티가 생기는 3단계에서 추가한다.

- [ ] **Step 2: Gradle wrapper 생성**

실행(로컬 `gradle` ≥ 8.8 필요; 없으면 다른 프로젝트의 wrapper 복사):
```bash
cd api && gradle wrapper --gradle-version 8.10
```
기대: `api/gradlew`, `api/gradle/wrapper/gradle-wrapper.jar`, `…/gradle-wrapper.properties` 생성.

- [ ] **Step 3: 엔트리포인트 + 설정 + IdGenerator 생성**

`api/src/main/kotlin/com/braindeck/BrainDeckApplication.kt`:
```kotlin
package com.braindeck

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class BrainDeckApplication

fun main(args: Array<String>) {
    runApplication<BrainDeckApplication>(*args)
}
```

`api/src/main/kotlin/com/braindeck/common/IdGenerator.kt`:
```kotlin
package com.braindeck.common

import com.fasterxml.uuid.Generators
import java.util.UUID

/** 모든 도메인 ID는 여기서 생성한다 (UUIDv7). UUID.randomUUID() 사용 금지. */
object IdGenerator {
    private val generator = Generators.timeBasedEpochGenerator() // UUIDv7
    fun newId(): UUID = generator.generate()
}
```

`api/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: braindeck-api
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/braindeck}
    username: ${DB_USER:braindeck}
    password: ${DB_PASSWORD:braindeck}
  flyway:
    enabled: true
    locations: classpath:db/migration
braindeck:
  jobs:
    poll-interval-ms: 5000
    max-attempts: 3
    kinds: embed,enrich
```

- [ ] **Step 4: 스키마 마이그레이션 작성 (DDL 규칙 준수)**

`api/src/main/resources/db/migration/V1__init.sql`:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 원본 소스. raw_content는 절대 UPDATE 금지. id/타임스탬프는 앱이 주입(DB default 없음).
CREATE TABLE sources (
    id          uuid PRIMARY KEY,
    type        varchar(20) NOT NULL,
    url         text,
    title       text NOT NULL DEFAULT '',
    raw_content text,                          -- 불변
    structure   jsonb,                         -- 워커가 추출한 섹션/제목 트리
    summary     text,                          -- LLM 생성 (이후 단계)
    tags        text[] NOT NULL DEFAULT '{}',
    status      varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT sources_type_chk   CHECK (type IN ('youtube','web','pdf','note','code_snippet')),
    CONSTRAINT sources_status_chk CHECK (status IN ('pending','parsing','parsed','enriched','failed'))
);
CREATE INDEX idx_sources_status ON sources (status);

-- 청크. content는 절대 UPDATE 금지.
CREATE TABLE chunks (
    id           uuid PRIMARY KEY,
    source_id    uuid NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    seq          int  NOT NULL,
    content      text NOT NULL,                -- 불변
    section_path text,                         -- 예: "3장 > RAG 평가"
    token_count  int  NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    CONSTRAINT uq_chunks_source_seq UNIQUE (source_id, seq)
);
CREATE INDEX idx_chunks_content_trgm ON chunks USING gin (content gin_trgm_ops);

-- 임베딩. 모델 버전별 행. 재임베딩 = 새 (chunk_id, model) 행 INSERT.
CREATE TABLE chunk_embeddings (
    chunk_id   uuid NOT NULL REFERENCES chunks (id) ON DELETE CASCADE,
    model      varchar(50) NOT NULL,           -- 예: "voyage-3"
    embedding  vector(1024) NOT NULL,          -- voyage-3 차원 고정
    created_at timestamptz NOT NULL,
    PRIMARY KEY (chunk_id, model)
);
CREATE INDEX idx_chunk_embeddings_hnsw
    ON chunk_embeddings USING hnsw (embedding vector_cosine_ops);

-- 잡 큐. FOR UPDATE SKIP LOCKED로 폴링.
CREATE TABLE ingest_jobs (
    id         uuid PRIMARY KEY,
    source_id  uuid NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    kind       varchar(20) NOT NULL,
    status     varchar(20) NOT NULL,
    attempts   int  NOT NULL DEFAULT 0,
    error      text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ingest_jobs_kind_chk   CHECK (kind IN ('parse','embed','enrich')),
    CONSTRAINT ingest_jobs_status_chk CHECK (status IN ('queued','running','done','failed'))
);
-- 클레임 SELECT를 값싸게 하는 부분 인덱스.
CREATE INDEX idx_ingest_jobs_poll ON ingest_jobs (kind, created_at) WHERE status = 'queued';
```

- [ ] **Step 5: docker-compose + env 템플릿 생성**

`.env.example`:
```dotenv
POSTGRES_DB=braindeck
POSTGRES_USER=braindeck
POSTGRES_PASSWORD=braindeck
# 이후 단계에서 채움:
VOYAGE_API_KEY=
ANTHROPIC_API_KEY=
```

`docker-compose.yml` (이 태스크는 postgres만; api/worker는 Task 5에서 추가):
```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-braindeck}
      POSTGRES_USER: ${POSTGRES_USER:-braindeck}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-braindeck}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-braindeck} -d ${POSTGRES_DB:-braindeck}"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  pgdata:
```

- [ ] **Step 6: Testcontainers 베이스 클래스 작성**

`api/src/test/kotlin/com/braindeck/AbstractPostgresTest.kt`:
```kotlin
package com.braindeck

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@SpringBootTest
@Testcontainers
abstract class AbstractPostgresTest {

    @Autowired
    protected lateinit var jdbc: NamedParameterJdbcTemplate

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres")
        )
            .withDatabaseName("braindeck")
            .withUsername("braindeck")
            .withPassword("braindeck")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
```

- [ ] **Step 7: ArchUnit 경계 규칙 골격 작성**

`api/src/test/kotlin/com/braindeck/architecture/ArchitectureTest.kt`:
```kotlin
package com.braindeck.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(
    packages = ["com.braindeck"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchitectureTest {

    companion object {
        private const val ROOT = "com.braindeck"
        private val CONTEXTS = listOf("knowledge", "ingest", "retrieval", "eval")

        // 다른 컨텍스트에서 import 금지 대상: readport를 제외한 모든 내부 레이어
        private val NON_READPORT_SUFFIXES = listOf(
            ".application.service..", ".domain..", ".infrastructure..", ".presentation..",
        )

        private fun crossContextReadPortOnly(self: String): ArchRule {
            val forbidden = (CONTEXTS - self).flatMap { other ->
                NON_READPORT_SUFFIXES.map { suffix -> "$ROOT.$other$suffix" }
            }.toTypedArray()
            return noClasses().that().resideInAPackage("$ROOT.$self..")
                .should().dependOnClassesThat().resideInAnyPackage(*forbidden)
                .allowEmptyShould(true)
        }
    }

    // common 은 어떤 도메인 컨텍스트도 의존하지 않는다.
    @ArchTest
    val commonIsolated: ArchRule = noClasses().that().resideInAPackage("$ROOT.common..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(*CONTEXTS.map { "$ROOT.$it.." }.toTypedArray())
        .allowEmptyShould(true)

    @ArchTest val knowledgeReadPortOnly: ArchRule = crossContextReadPortOnly("knowledge")
    @ArchTest val ingestReadPortOnly: ArchRule = crossContextReadPortOnly("ingest")
    @ArchTest val retrievalReadPortOnly: ArchRule = crossContextReadPortOnly("retrieval")
    @ArchTest val evalReadPortOnly: ArchRule = crossContextReadPortOnly("eval")
}
```
참고: `.allowEmptyShould(true)`라 아직 없는 컨텍스트(knowledge/retrieval/eval) 패키지는 통과한다. 규칙은 4컨텍스트 전부 미리 정의해 이후 단계에서 경계를 강제한다.

- [ ] **Step 8: 실패하는 마이그레이션 테스트 작성**

`api/src/test/kotlin/com/braindeck/MigrationTest.kt`:
```kotlin
package com.braindeck

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest : AbstractPostgresTest() {

    @Test
    fun `핵심 테이블이 모두 존재한다`() {
        val tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            emptyMap<String, Any>(), String::class.java
        ).toSet()
        assertTrue(tables.containsAll(listOf("sources", "chunks", "chunk_embeddings", "ingest_jobs")))
    }

    @Test
    fun `pgvector와 pg_trgm 확장이 설치된다`() {
        val exts = jdbc.queryForList(
            "SELECT extname FROM pg_extension", emptyMap<String, Any>(), String::class.java
        ).toSet()
        assertTrue(exts.contains("vector"))
        assertTrue(exts.contains("pg_trgm"))
    }

    @Test
    fun `embedding 컬럼은 1024 차원이다`() {
        // pgvector에서 vector 차원은 atttypmod에 저장된다.
        val dim = jdbc.queryForObject(
            """
            SELECT a.atttypmod
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            WHERE c.relname = 'chunk_embeddings' AND a.attname = 'embedding'
            """.trimIndent(),
            emptyMap<String, Any>(), Int::class.java
        )
        assertEquals(1024, dim)
    }
}
```

- [ ] **Step 9: 테스트 실행 — PASS 기대 (Flyway가 부팅 시 V1 적용)**

실행:
```bash
cd api && ./gradlew test --tests com.braindeck.MigrationTest --tests com.braindeck.architecture.ArchitectureTest
```
기대: PASS. (Docker 미실행 시 Testcontainer가 못 뜬다 — Docker 먼저 실행.)

- [ ] **Step 10: docker-compose Postgres로 앱 부팅 검증**

실행:
```bash
cp .env.example .env
docker compose up -d postgres
cd api && DB_URL=jdbc:postgresql://localhost:5432/braindeck ./gradlew bootRun
```
기대: 로그에 Flyway `Successfully applied 1 migration`, 8080 포트로 기동. Ctrl-C로 종료.

- [ ] **Step 11: 커밋**

```bash
git add docker-compose.yml .env.example api/ CLAUDE.md docs/
git commit -m "feat: Kotlin 골격 + docker-compose Postgres + V1 스키마 + ArchUnit 골격"
```

---

### Task 2: ingest 컨텍스트 — 잡 클레임 리포지토리 (FOR UPDATE SKIP LOCKED)

**파일:**
- 생성: `api/src/main/kotlin/com/braindeck/ingest/domain/IngestJob.kt`
- 생성: `api/src/main/kotlin/com/braindeck/ingest/infrastructure/JobRepository.kt`
- 테스트: `api/src/test/kotlin/com/braindeck/ingest/JobRepositoryTest.kt`

**인터페이스:**
- 소비: `NamedParameterJdbcTemplate`(Spring 빈), `common.IdGenerator`; Task 1 스키마.
- 생산:
  - `enum class JobKind(val db: String) { PARSE("parse"), EMBED("embed"), ENRICH("enrich") }`
  - `data class ClaimedJob(val id: UUID, val sourceId: UUID, val kind: String, val attempts: Int)`
  - `class JobRepository`:
    - `fun claimNext(kinds: List<String>, now: Instant): ClaimedJob?` — `queued`이고 `kind` ∈ `kinds`인 잡 하나를 원자적으로 클레임(`status='running'`, `attempts=attempts+1`, `updated_at=now`), 반환 또는 `null`.
    - `fun markDone(id: UUID, now: Instant)` — `status='done'`, `updated_at=now`.
    - `fun markFailed(id: UUID, error: String, maxAttempts: Int, now: Instant)` — `attempts >= maxAttempts`면 `failed` 아니면 `queued`, 항상 `error`·`updated_at` 기록.
- DDL 규칙상 타임스탬프는 앱이 주입하므로 모든 쓰기 메서드가 `now: Instant`를 받는다.

- [ ] **Step 1: 실패하는 테스트 작성**

`api/src/test/kotlin/com/braindeck/ingest/JobRepositoryTest.kt`:
```kotlin
package com.braindeck.ingest

import com.braindeck.AbstractPostgresTest
import com.braindeck.common.IdGenerator
import com.braindeck.ingest.domain.ClaimedJob
import com.braindeck.ingest.infrastructure.JobRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JobRepositoryTest : AbstractPostgresTest() {

    @Autowired lateinit var jobs: JobRepository
    private val now: Instant = Instant.parse("2026-07-06T00:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.jdbcTemplate.update("DELETE FROM ingest_jobs")
        jdbc.jdbcTemplate.update("DELETE FROM sources")
    }

    private fun newSource(): UUID {
        val id = IdGenerator.newId()
        jdbc.update(
            """INSERT INTO sources (id, type, status, created_at, updated_at)
               VALUES (:id, 'note', 'pending', :now, :now)""",
            mapOf("id" to id, "now" to java.sql.Timestamp.from(now)),
        )
        return id
    }

    private fun newJob(sourceId: UUID, kind: String): UUID {
        val id = IdGenerator.newId()
        jdbc.update(
            """INSERT INTO ingest_jobs (id, source_id, kind, status, attempts, created_at, updated_at)
               VALUES (:id, :sid, :kind, 'queued', 0, :now, :now)""",
            mapOf("id" to id, "sid" to sourceId, "kind" to kind, "now" to java.sql.Timestamp.from(now)),
        )
        return id
    }

    private fun statusOf(id: UUID): String = jdbc.queryForObject(
        "SELECT status FROM ingest_jobs WHERE id = :id", mapOf("id" to id), String::class.java
    )!!

    @Test
    fun `claimNext는 queued 잡을 running으로 바꾸고 attempts를 증가시킨다`() {
        val s = newSource(); val j = newJob(s, "embed")
        val claimed: ClaimedJob? = jobs.claimNext(listOf("embed"), now)
        assertNotNull(claimed)
        assertEquals(j, claimed.id)
        assertEquals(1, claimed.attempts)
        assertEquals("running", statusOf(j))
    }

    @Test
    fun `claimNext는 kind 필터를 지킨다`() {
        val s = newSource(); newJob(s, "parse")
        assertNull(jobs.claimNext(listOf("embed", "enrich"), now))
    }

    @Test
    fun `queued 잡이 없으면 null`() {
        assertNull(jobs.claimNext(listOf("embed"), now))
    }

    @Test
    fun `두 번 연속 클레임하면 서로 다른 잡을 반환한다`() {
        val s = newSource()
        val a = newJob(s, "embed"); val b = newJob(s, "embed")
        val first = jobs.claimNext(listOf("embed"), now)!!
        val second = jobs.claimNext(listOf("embed"), now)!!
        assertEquals(setOf(a, b), setOf(first.id, second.id))
        assertNull(jobs.claimNext(listOf("embed"), now))
    }

    @Test
    fun `markDone은 status를 done으로 만든다`() {
        val s = newSource(); val j = newJob(s, "embed")
        jobs.claimNext(listOf("embed"), now)
        jobs.markDone(j, now)
        assertEquals("done", statusOf(j))
    }

    @Test
    fun `markFailed는 최대 시도 전엔 requeue, 이후 failed`() {
        val s = newSource(); val j = newJob(s, "embed")
        jobs.claimNext(listOf("embed"), now); jobs.markFailed(j, "boom", 3, now)
        assertEquals("queued", statusOf(j))
        jobs.claimNext(listOf("embed"), now); jobs.markFailed(j, "boom", 3, now)
        assertEquals("queued", statusOf(j))
        jobs.claimNext(listOf("embed"), now); jobs.markFailed(j, "boom", 3, now)
        assertEquals("failed", statusOf(j))
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

실행:
```bash
cd api && ./gradlew test --tests com.braindeck.ingest.JobRepositoryTest
```
기대: FAIL — `JobRepository`/`ClaimedJob` 미해결(컴파일 에러).

- [ ] **Step 3: `IngestJob.kt` 작성**

`api/src/main/kotlin/com/braindeck/ingest/domain/IngestJob.kt`:
```kotlin
package com.braindeck.ingest.domain

import java.util.UUID

enum class JobKind(val db: String) {
    PARSE("parse"), EMBED("embed"), ENRICH("enrich")
}

data class ClaimedJob(
    val id: UUID,
    val sourceId: UUID,
    val kind: String,
    val attempts: Int,
)
```

- [ ] **Step 4: `JobRepository.kt` 작성**

`api/src/main/kotlin/com/braindeck/ingest/infrastructure/JobRepository.kt`:
```kotlin
package com.braindeck.ingest.infrastructure

import com.braindeck.ingest.domain.ClaimedJob
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JobRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun claimNext(kinds: List<String>, now: Instant): ClaimedJob? {
        val rows = jdbc.query(
            """
            UPDATE ingest_jobs
            SET status = 'running', attempts = attempts + 1, updated_at = :now
            WHERE id = (
                SELECT id FROM ingest_jobs
                WHERE status = 'queued' AND kind = ANY(:kinds)
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            RETURNING id, source_id, kind, attempts
            """.trimIndent(),
            mapOf("kinds" to kinds.toTypedArray(), "now" to Timestamp.from(now)),
        ) { rs, _ ->
            ClaimedJob(
                id = rs.getObject("id", UUID::class.java),
                sourceId = rs.getObject("source_id", UUID::class.java),
                kind = rs.getString("kind"),
                attempts = rs.getInt("attempts"),
            )
        }
        return rows.firstOrNull()
    }

    fun markDone(id: UUID, now: Instant) {
        jdbc.update(
            "UPDATE ingest_jobs SET status = 'done', updated_at = :now WHERE id = :id",
            mapOf("id" to id, "now" to Timestamp.from(now)),
        )
    }

    fun markFailed(id: UUID, error: String, maxAttempts: Int, now: Instant) {
        jdbc.update(
            """
            UPDATE ingest_jobs
            SET status = CASE WHEN attempts >= :max THEN 'failed' ELSE 'queued' END,
                error = :error,
                updated_at = :now
            WHERE id = :id
            """.trimIndent(),
            mapOf("id" to id, "max" to maxAttempts, "error" to error, "now" to Timestamp.from(now)),
        )
    }
}
```
참고: `kind = ANY(:kinds)`에 `String[]`(`kinds.toTypedArray()`)를 바인딩하면 Postgres JDBC 드라이버가 text 배열 파라미터로 매핑한다.

- [ ] **Step 5: 테스트 실행 — PASS 확인**

실행:
```bash
cd api && ./gradlew test --tests com.braindeck.ingest.JobRepositoryTest
```
기대: PASS (6개).

- [ ] **Step 6: 커밋**

```bash
git add api/src/main/kotlin/com/braindeck/ingest api/src/test/kotlin/com/braindeck/ingest
git commit -m "feat(ingest): FOR UPDATE SKIP LOCKED 잡 클레임 리포지토리"
```

---

### Task 3: ingest 컨텍스트 — 폴링 스케줄러 + 스텁 핸들러

**파일:**
- 생성: `api/src/main/kotlin/com/braindeck/ingest/application/port/JobHandler.kt`
- 생성: `api/src/main/kotlin/com/braindeck/ingest/application/service/JobProcessor.kt`
- 생성: `api/src/main/kotlin/com/braindeck/ingest/application/scheduler/JobScheduler.kt`
- 생성: `api/src/main/kotlin/com/braindeck/ingest/infrastructure/handler/StubEmbedJobHandler.kt`
- 생성: `api/src/main/kotlin/com/braindeck/ingest/infrastructure/handler/StubEnrichJobHandler.kt`
- 테스트: `api/src/test/kotlin/com/braindeck/ingest/JobProcessorTest.kt`

**인터페이스:**
- 소비: `JobRepository`, `ClaimedJob`, `JobKind`; 설정 `braindeck.jobs.*`.
- 생산:
  - `interface JobHandler { val kind: JobKind; fun handle(job: ClaimedJob) }`
  - `class JobProcessor` — `fun pollOnce(): Int`(설정된 kind의 가용 잡을 전부 클레임+디스패치, 처리 건수 반환).
  - `class JobScheduler` — `@Scheduled(fixedDelayString="\${braindeck.jobs.poll-interval-ms}")`가 `JobProcessor.pollOnce()` 호출.
  - 스텁 `StubEmbedJobHandler`/`StubEnrichJobHandler`(로그 후 정상 완료).
- 참고: 이 단계에서 embed/enrich 핸들러는 ingest 내부 스텁이다. 실제 로직이 생기면 이후 단계에서 `knowledge` 컨텍스트가 `JobHandler` 포트를 구현하도록 옮긴다.

- [ ] **Step 1: 실패하는 테스트 작성**

`api/src/test/kotlin/com/braindeck/ingest/JobProcessorTest.kt`:
```kotlin
package com.braindeck.ingest

import com.braindeck.AbstractPostgresTest
import com.braindeck.common.IdGenerator
import com.braindeck.ingest.application.service.JobProcessor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class JobProcessorTest : AbstractPostgresTest() {

    @Autowired lateinit var processor: JobProcessor
    private val now: Instant = Instant.parse("2026-07-06T00:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.jdbcTemplate.update("DELETE FROM ingest_jobs")
        jdbc.jdbcTemplate.update("DELETE FROM sources")
    }

    private fun newSource(): UUID {
        val id = IdGenerator.newId()
        jdbc.update(
            """INSERT INTO sources (id, type, status, created_at, updated_at)
               VALUES (:id, 'note', 'pending', :now, :now)""",
            mapOf("id" to id, "now" to Timestamp.from(now)),
        )
        return id
    }

    private fun newJob(sourceId: UUID, kind: String) {
        jdbc.update(
            """INSERT INTO ingest_jobs (id, source_id, kind, status, attempts, created_at, updated_at)
               VALUES (:id, :sid, :kind, 'queued', 0, :now, :now)""",
            mapOf("id" to IdGenerator.newId(), "sid" to sourceId, "kind" to kind, "now" to Timestamp.from(now)),
        )
    }

    private fun statusOf(kind: String): String = jdbc.queryForObject(
        "SELECT status FROM ingest_jobs WHERE kind = :k", mapOf("k" to kind), String::class.java
    )!!

    @Test
    fun `pollOnce는 embed·enrich를 done 처리하고 parse는 남긴다`() {
        val s = newSource()
        newJob(s, "embed"); newJob(s, "enrich"); newJob(s, "parse")

        val processed = processor.pollOnce()

        assertEquals(2, processed)
        assertEquals("done", statusOf("embed"))
        assertEquals("done", statusOf("enrich"))
        assertEquals("queued", statusOf("parse")) // parse는 워커 몫
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

실행:
```bash
cd api && ./gradlew test --tests com.braindeck.ingest.JobProcessorTest
```
기대: FAIL — `JobProcessor` 미해결.

- [ ] **Step 3: `JobHandler` 포트 작성**

`api/src/main/kotlin/com/braindeck/ingest/application/port/JobHandler.kt`:
```kotlin
package com.braindeck.ingest.application.port

import com.braindeck.ingest.domain.ClaimedJob
import com.braindeck.ingest.domain.JobKind

interface JobHandler {
    val kind: JobKind
    fun handle(job: ClaimedJob)
}
```

- [ ] **Step 4: 스텁 핸들러 2개 작성**

`api/src/main/kotlin/com/braindeck/ingest/infrastructure/handler/StubEmbedJobHandler.kt`:
```kotlin
package com.braindeck.ingest.infrastructure.handler

import com.braindeck.ingest.application.port.JobHandler
import com.braindeck.ingest.domain.ClaimedJob
import com.braindeck.ingest.domain.JobKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class StubEmbedJobHandler : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val kind = JobKind.EMBED
    override fun handle(job: ClaimedJob) {
        log.info("[stub] embed 잡 {} (source {})", job.id, job.sourceId)
    }
}
```

`api/src/main/kotlin/com/braindeck/ingest/infrastructure/handler/StubEnrichJobHandler.kt`:
```kotlin
package com.braindeck.ingest.infrastructure.handler

import com.braindeck.ingest.application.port.JobHandler
import com.braindeck.ingest.domain.ClaimedJob
import com.braindeck.ingest.domain.JobKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class StubEnrichJobHandler : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val kind = JobKind.ENRICH
    override fun handle(job: ClaimedJob) {
        log.info("[stub] enrich 잡 {} (source {})", job.id, job.sourceId)
    }
}
```

- [ ] **Step 5: `JobProcessor` 작성**

`api/src/main/kotlin/com/braindeck/ingest/application/service/JobProcessor.kt`:
```kotlin
package com.braindeck.ingest.application.service

import com.braindeck.ingest.application.port.JobHandler
import com.braindeck.ingest.infrastructure.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JobProcessor(
    private val jobs: JobRepository,
    handlers: List<JobHandler>,
    @Value("\${braindeck.jobs.max-attempts:3}") private val maxAttempts: Int,
    @Value("\${braindeck.jobs.kinds:embed,enrich}") kindsCsv: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byKind: Map<String, JobHandler> = handlers.associateBy { it.kind.db }
    private val kinds: List<String> = kindsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** 설정된 kind의 가용 잡을 전부 클레임·디스패치한다. 처리 건수를 반환. */
    fun pollOnce(): Int {
        var count = 0
        while (true) {
            val now = Instant.now()
            val job = jobs.claimNext(kinds, now) ?: break
            count++
            val handler = byKind[job.kind]
            if (handler == null) {
                jobs.markFailed(job.id, "kind=${job.kind} 핸들러 없음", maxAttempts, Instant.now())
                continue
            }
            try {
                handler.handle(job)
                jobs.markDone(job.id, Instant.now())
            } catch (e: Exception) {
                log.error("잡 {} (kind={}) attempt {} 실패", job.id, job.kind, job.attempts, e)
                jobs.markFailed(job.id, e.message ?: e.javaClass.simpleName, maxAttempts, Instant.now())
            }
        }
        return count
    }
}
```

- [ ] **Step 6: `JobScheduler` 작성**

`api/src/main/kotlin/com/braindeck/ingest/application/scheduler/JobScheduler.kt`:
```kotlin
package com.braindeck.ingest.application.scheduler

import com.braindeck.ingest.application.service.JobProcessor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class JobScheduler(private val processor: JobProcessor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${braindeck.jobs.poll-interval-ms:5000}")
    fun poll() {
        try {
            val n = processor.pollOnce()
            if (n > 0) log.info("{}건 처리", n)
        } catch (e: Exception) {
            log.error("폴링 사이클 실패", e)
        }
    }
}
```

- [ ] **Step 7: 테스트 실행 — PASS 확인**

실행:
```bash
cd api && ./gradlew test --tests com.braindeck.ingest.JobProcessorTest
```
기대: PASS.

- [ ] **Step 8: 전체 Kotlin 테스트 실행**

실행:
```bash
cd api && ./gradlew test
```
기대: PASS (MigrationTest, ArchitectureTest, JobRepositoryTest, JobProcessorTest).

- [ ] **Step 9: 커밋**

```bash
git add api/src/main/kotlin/com/braindeck/ingest api/src/test/kotlin/com/braindeck/ingest/JobProcessorTest.kt
git commit -m "feat(ingest): 스케줄 폴러 + embed/enrich 스텁 핸들러"
```

---

### Task 4: Python 파싱 워커 (클레임 + 폴링 골격)

**파일:**
- 생성: `worker/pyproject.toml`
- 생성: `worker/braindeck_worker/__init__.py`, `.../db.py`, `.../queue.py`
- 생성: `worker/braindeck_worker/handlers/__init__.py`, `.../handlers/parse.py`
- 생성: `worker/braindeck_worker/worker.py`
- 생성: `worker/tests/__init__.py`
- 테스트: `worker/tests/test_queue.py`

**인터페이스:**
- 소비: 동일한 `ingest_jobs` 스키마(Kotlin/Flyway 소유); `psycopg` 연결.
- 생산:
  - `queue.claim_next(conn, kinds, now) -> dict | None` — Kotlin과 동일 클레임 SQL, `{"id","source_id","kind","attempts"}` 또는 `None`. 커밋.
  - `queue.mark_done(conn, job_id, now)` / `queue.mark_failed(conn, job_id, error, max_attempts, now)`.
  - `handlers.parse.handle(conn, job)` — 스텁, 로그만.
  - `worker.run_forever()` / `worker.poll_once(conn, kinds, max_attempts) -> int`.
- 참고: 워커는 잡을 **소비만** 하고 생성하지 않는다. 테스트에서 잡·소스 INSERT 시 id는 편의상 `uuid4`를 쓴다(프로덕션 ID는 Kotlin이 UUIDv7로 생성). 타임스탬프는 앱이 주입(`datetime.now(timezone.utc)`).

- [ ] **Step 1: Python 프로젝트 파일 생성**

`worker/pyproject.toml`:
```toml
[project]
name = "braindeck-worker"
version = "0.0.1"
requires-python = ">=3.12"
dependencies = [
    "psycopg[binary]>=3.2",
]

[project.optional-dependencies]
test = [
    "pytest>=8.0",
    "testcontainers[postgres]>=4.0",
]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.pytest.ini_options]
pythonpath = ["."]
```

`worker/braindeck_worker/__init__.py`, `worker/braindeck_worker/handlers/__init__.py`, `worker/tests/__init__.py`: (빈 파일)

- [ ] **Step 2: DB 연결 팩토리 작성**

`worker/braindeck_worker/db.py`:
```python
import os
import psycopg


def connect(dsn: str | None = None) -> psycopg.Connection:
    """psycopg 연결을 연다. DSN은 인자 또는 DB_DSN 환경변수."""
    dsn = dsn or os.environ["DB_DSN"]
    return psycopg.connect(dsn)
```

- [ ] **Step 3: 실패하는 queue 테스트 작성**

`worker/tests/test_queue.py`:
```python
import uuid
from datetime import datetime, timezone

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

from braindeck_worker import queue

# Kotlin V1 마이그레이션과 동일 규칙의 최소 DDL (복수형, VARCHAR+CHECK, 타임스탬프 앱 주입).
# 워커는 마이그레이션하지 않는다; 이 DDL은 queue.py 단위 검증 전용이다.
_DDL = """
CREATE TABLE sources (
    id uuid PRIMARY KEY,
    type varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE TABLE ingest_jobs (
    id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    kind varchar(20) NOT NULL,
    status varchar(20) NOT NULL,
    attempts int NOT NULL DEFAULT 0,
    error text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
"""

NOW = datetime(2026, 7, 6, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def conn():
    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        dsn = pg.get_connection_url().replace("postgresql+psycopg2", "postgresql")
        with psycopg.connect(dsn) as c:
            c.execute(_DDL)
            c.commit()
            yield c


@pytest.fixture(autouse=True)
def clean(conn):
    conn.execute("DELETE FROM ingest_jobs")
    conn.execute("DELETE FROM sources")
    conn.commit()


def _new_source(conn) -> str:
    sid = uuid.uuid4()
    conn.execute(
        "INSERT INTO sources (id, type, status, created_at, updated_at) "
        "VALUES (%s, 'web', 'pending', %s, %s)",
        (sid, NOW, NOW),
    )
    conn.commit()
    return sid


def _new_job(conn, source_id, kind) -> str:
    jid = uuid.uuid4()
    conn.execute(
        "INSERT INTO ingest_jobs (id, source_id, kind, status, attempts, created_at, updated_at) "
        "VALUES (%s, %s, %s, 'queued', 0, %s, %s)",
        (jid, source_id, kind, NOW, NOW),
    )
    conn.commit()
    return jid


def _status(conn, job_id) -> str:
    return conn.execute("SELECT status FROM ingest_jobs WHERE id = %s", (job_id,)).fetchone()[0]


def test_claim_next_marks_running(conn):
    s = _new_source(conn)
    j = _new_job(conn, s, "parse")
    claimed = queue.claim_next(conn, ["parse"], NOW)
    assert claimed is not None
    assert str(claimed["id"]) == str(j)
    assert claimed["attempts"] == 1
    assert _status(conn, j) == "running"


def test_claim_next_respects_kind_filter(conn):
    s = _new_source(conn)
    _new_job(conn, s, "embed")
    assert queue.claim_next(conn, ["parse"], NOW) is None


def test_claim_next_none_when_empty(conn):
    assert queue.claim_next(conn, ["parse"], NOW) is None


def test_mark_done(conn):
    s = _new_source(conn)
    j = _new_job(conn, s, "parse")
    queue.claim_next(conn, ["parse"], NOW)
    queue.mark_done(conn, j, NOW)
    assert _status(conn, j) == "done"


def test_mark_failed_requeues_then_fails(conn):
    s = _new_source(conn)
    j = _new_job(conn, s, "parse")
    for _ in range(2):
        queue.claim_next(conn, ["parse"], NOW)
        queue.mark_failed(conn, j, "boom", 3, NOW)
        assert _status(conn, j) == "queued"
    queue.claim_next(conn, ["parse"], NOW)
    queue.mark_failed(conn, j, "boom", 3, NOW)
    assert _status(conn, j) == "failed"
```

- [ ] **Step 4: 테스트 실행 — 실패 확인**

실행:
```bash
cd worker && pip install -e ".[test]" && pytest tests/test_queue.py -v
```
기대: FAIL — `braindeck_worker.queue`에 `claim_next` 없음.

- [ ] **Step 5: `queue.py` 작성**

`worker/braindeck_worker/queue.py`:
```python
"""잡 큐 클레임 헬퍼. 이 모듈이 Python 워커의 클레임 SQL을 유일하게 보유한다 —
핸들러는 ingest_jobs 행을 직접 건드리지 않는다."""
from __future__ import annotations

from datetime import datetime

import psycopg

_CLAIM_SQL = """
UPDATE ingest_jobs
SET status = 'running', attempts = attempts + 1, updated_at = %(now)s
WHERE id = (
    SELECT id FROM ingest_jobs
    WHERE status = 'queued' AND kind = ANY(%(kinds)s)
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
RETURNING id, source_id, kind, attempts
"""


def claim_next(conn: psycopg.Connection, kinds: list[str], now: datetime) -> dict | None:
    row = conn.execute(_CLAIM_SQL, {"kinds": kinds, "now": now}).fetchone()
    conn.commit()
    if row is None:
        return None
    return {"id": row[0], "source_id": row[1], "kind": row[2], "attempts": row[3]}


def mark_done(conn: psycopg.Connection, job_id, now: datetime) -> None:
    conn.execute(
        "UPDATE ingest_jobs SET status = 'done', updated_at = %s WHERE id = %s",
        (now, job_id),
    )
    conn.commit()


def mark_failed(conn: psycopg.Connection, job_id, error: str, max_attempts: int, now: datetime) -> None:
    conn.execute(
        """
        UPDATE ingest_jobs
        SET status = CASE WHEN attempts >= %(max)s THEN 'failed' ELSE 'queued' END,
            error = %(error)s,
            updated_at = %(now)s
        WHERE id = %(id)s
        """,
        {"max": max_attempts, "error": error, "now": now, "id": job_id},
    )
    conn.commit()
```

- [ ] **Step 6: 테스트 실행 — PASS 확인**

실행:
```bash
cd worker && pytest tests/test_queue.py -v
```
기대: PASS (5개).

- [ ] **Step 7: 스텁 파싱 핸들러 + 워커 루프 작성**

`worker/braindeck_worker/handlers/parse.py`:
```python
"""스텁 파싱 핸들러. 실제 파싱(web/youtube/pdf)은 스펙 5단계에서 붙는다.
파싱 전용: 정제 텍스트 + 구조를 sources에 출력하는 역할이며, 청킹/임베딩 금지."""
import logging

log = logging.getLogger(__name__)


def handle(conn, job: dict) -> None:
    log.info("[stub] parse 잡 %s (source %s)", job["id"], job["source_id"])
```

`worker/braindeck_worker/worker.py`:
```python
from __future__ import annotations

import logging
import os
import time
from datetime import datetime, timezone

from braindeck_worker import queue
from braindeck_worker.db import connect
from braindeck_worker.handlers import parse

log = logging.getLogger(__name__)

KINDS = ["parse"]
HANDLERS = {"parse": parse.handle}


def poll_once(conn, kinds: list[str], max_attempts: int) -> int:
    """kinds의 가용 잡을 전부 클레임·디스패치한다. 처리 건수 반환."""
    count = 0
    while True:
        job = queue.claim_next(conn, kinds, datetime.now(timezone.utc))
        if job is None:
            break
        count += 1
        handler = HANDLERS.get(job["kind"])
        if handler is None:
            queue.mark_failed(conn, job["id"], f"kind={job['kind']} 핸들러 없음", max_attempts, datetime.now(timezone.utc))
            continue
        try:
            handler(conn, job)
            queue.mark_done(conn, job["id"], datetime.now(timezone.utc))
        except Exception as exc:  # noqa: BLE001 - 골격: 로그 후 requeue
            log.exception("잡 %s attempt %s 실패", job["id"], job["attempts"])
            queue.mark_failed(conn, job["id"], str(exc), max_attempts, datetime.now(timezone.utc))
    return count


def run_forever() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    interval = float(os.environ.get("POLL_INTERVAL_SEC", "5"))
    max_attempts = int(os.environ.get("MAX_ATTEMPTS", "3"))
    conn = connect()
    log.info("워커 시작, %s 를 %ss 마다 폴링", KINDS, interval)
    while True:
        try:
            n = poll_once(conn, KINDS, max_attempts)
            if n:
                log.info("%s건 처리", n)
        except Exception:  # noqa: BLE001 - 루프 유지; 다음 사이클에 재연결
            log.exception("폴링 사이클 실패; 재연결")
            conn = connect()
        time.sleep(interval)


if __name__ == "__main__":
    run_forever()
```

- [ ] **Step 8: poll_once 테스트 추가 후 실행**

`worker/tests/test_queue.py` 하단에 추가:
```python
from braindeck_worker import worker


def test_poll_once_processes_parse_only(conn):
    s = _new_source(conn)
    p = _new_job(conn, s, "parse")
    e = _new_job(conn, s, "embed")
    n = worker.poll_once(conn, ["parse"], max_attempts=3)
    assert n == 1
    assert _status(conn, p) == "done"
    assert _status(conn, e) == "queued"  # embed는 Kotlin 몫
```

실행:
```bash
cd worker && pytest tests/ -v
```
기대: PASS (6개).

- [ ] **Step 9: 커밋**

```bash
git add worker/
git commit -m "feat(worker): Python 파싱 워커 클레임/폴링 골격 + 스텁 핸들러"
```

---

### Task 5: 전체 docker-compose 연결 + E2E 스모크 테스트

**파일:**
- 생성: `api/Dockerfile`, `worker/Dockerfile`
- 수정: `docker-compose.yml` (`api`·`worker` 서비스 추가)
- 생성: `scripts/smoke.sh`

**인터페이스:**
- 소비: Task 1~4 전부.
- 생산: `docker compose up`으로 postgres + api(마이그레이션·embed/enrich 폴링) + worker(parse 폴링) 기동; `scripts/smoke.sh`가 소스 1개 + 3종 잡을 넣고 모두 `done` 도달을 검증.

- [ ] **Step 1: API Dockerfile 작성**

`api/Dockerfile`:
```dockerfile
# 빌드
FROM gradle:8.10-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon

# 실행
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: 워커 Dockerfile 작성**

`worker/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml .
COPY braindeck_worker ./braindeck_worker
RUN pip install --no-cache-dir .
CMD ["python", "-m", "braindeck_worker.worker"]
```

- [ ] **Step 3: docker-compose에 `api`·`worker` 서비스 추가**

`docker-compose.yml`의 `services:` 아래에 추가(기존 `postgres`·`volumes:` 유지):
```yaml
  api:
    build: ./api
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-braindeck}
      DB_USER: ${POSTGRES_USER:-braindeck}
      DB_PASSWORD: ${POSTGRES_PASSWORD:-braindeck}
    ports:
      - "8080:8080"

  worker:
    build: ./worker
    depends_on:
      postgres:
        condition: service_healthy
      api:
        condition: service_started   # api가 마이그레이션 소유 → 먼저 기동
    environment:
      DB_DSN: postgresql://${POSTGRES_USER:-braindeck}:${POSTGRES_PASSWORD:-braindeck}@postgres:5432/${POSTGRES_DB:-braindeck}
      POLL_INTERVAL_SEC: "5"
      MAX_ATTEMPTS: "3"
```

- [ ] **Step 4: 스모크 테스트 스크립트 작성**

`scripts/smoke.sh`:
```bash
#!/usr/bin/env bash
# E2E 골격 스모크: 3종 잡을 넣고 모두 'done' 도달을 검증.
# 참고: id는 gen_random_uuid()(v4)로 넣지만 프로덕션 ID는 앱이 UUIDv7로 생성한다(테스트 편의).
set -euo pipefail

DB="${POSTGRES_DB:-braindeck}"
USER="${POSTGRES_USER:-braindeck}"
PSQL=(docker compose exec -T postgres psql -U "$USER" -d "$DB" -tAc)

echo "소스 + parse/embed/enrich 잡 등록..."
SID=$("${PSQL[@]}" "INSERT INTO sources (id, type, status, created_at, updated_at) VALUES (gen_random_uuid(),'note','pending', now(), now()) RETURNING id;")
for kind in parse embed enrich; do
  "${PSQL[@]}" "INSERT INTO ingest_jobs (id, source_id, kind, status, attempts, created_at, updated_at) VALUES (gen_random_uuid(),'$SID','$kind','queued',0, now(), now());" >/dev/null
done

echo "모든 잡이 'done'이 될 때까지 최대 30초 대기..."
for _ in $(seq 1 15); do
  DONE=$("${PSQL[@]}" "SELECT count(*) FROM ingest_jobs WHERE source_id='$SID' AND status='done';")
  if [ "$DONE" = "3" ]; then
    echo "PASS: 3개 잡 모두 done."
    exit 0
  fi
  sleep 2
done

echo "FAIL: 잡이 다 완료되지 않음:"
"${PSQL[@]}" "SELECT kind, status, attempts, error FROM ingest_jobs WHERE source_id='$SID';"
exit 1
```

- [ ] **Step 5: 전체 스택 빌드·기동**

실행:
```bash
cp .env.example .env
docker compose up -d --build
```
기대: `postgres`·`api`·`worker` 모두 기동; `docker compose logs api`에 Flyway `Successfully applied 1 migration`.

- [ ] **Step 6: 스모크 테스트 실행**

실행:
```bash
chmod +x scripts/smoke.sh && ./scripts/smoke.sh
```
기대: `PASS: 3개 잡 모두 done.` (워커가 `parse`, api가 `embed`+`enrich` 완료.)

- [ ] **Step 7: 종료**

실행:
```bash
docker compose down
```
기대: 전체 서비스 중지.

- [ ] **Step 8: 커밋**

```bash
git add api/Dockerfile worker/Dockerfile docker-compose.yml scripts/smoke.sh
git commit -m "feat: 전체 docker-compose 연결 + E2E 잡 스모크 테스트"
```

---

## 자기 검토 (Self-Review)

**스펙 §9-1 커버리지 ("docker-compose + Flyway 마이그레이션 + 잡큐 폴링 골격"):**
- docker-compose(postgres + kotlin-api + python-worker) → Task 1(postgres) + Task 5(api, worker). ✔
- Flyway 스키마(테이블 4개 + pgvector·pg_trgm + HNSW·GIN 인덱스, DDL 규칙 준수) → Task 1(`V1__init.sql`, `MigrationTest`). ✔
- 잡 큐(Postgres + `FOR UPDATE SKIP LOCKED` 폴링) → Task 2(Kotlin 클레임), Task 3(Kotlin 폴링), Task 4(Python 클레임+폴링). ✔
- 역할 분리(Python 파싱 전용; Kotlin embed/enrich) → Task 3=embed/enrich, Task 4=parse; 스텁에 도메인 로직 금지 주석. ✔
- Testcontainers 통합 테스트 → Task 1/2/3(JUnit5 + pgvector), Task 4(`testcontainers-python`). ✔
- ArchUnit 경계 골격 → Task 1(`ArchitectureTest`, 4컨텍스트 규칙 + `allowEmptyShould`). ✔
- 5초 폴링 / attempts 3회 → `application.yml` + env; `markFailed`/`mark_failed` 검증. ✔
- 스키마 소유자 = Kotlin 전용, 워커 미마이그레이션 → `worker` `depends_on` api; Python 테스트 DDL은 격리 전용 + 주석. ✔
- DDL 규칙(복수형, VARCHAR+CHECK, TIMESTAMPTZ 앱주입, UUIDv7 앱생성) → V1 스키마 + IdGenerator + 앱 타임스탬프 주입. ✔
- **이 단계 범위 아님(올바르게 연기):** 평가 하네스(§9-2), 청킹/임베딩/RAG/검색(§9-3~4), 실제 파서(§9-5), enrich(§9-6). 어떤 태스크도 구현하지 않음. ✔

**Placeholder 스캔:** "TBD"/"적절한 에러 처리"/"Task N과 유사" 없음. 모든 코드 스텝은 완전한 파일 내용 또는 정확한 추가 지점 제시. 스텁 핸들러는 스펙 §9-1의 "골격"에 해당하며 `[stub]`으로 명시, 실제 작업은 이후 단계에 배정.

**타입 일관성:** `ClaimedJob(id, sourceId, kind, attempts)` ↔ Python `{"id","source_id","kind","attempts"}` ↔ 클레임 SQL `RETURNING id, source_id, kind, attempts` 일치. `JobKind.db`(`parse`/`embed`/`enrich`)가 전역 `kind` 문자열과 일치. `claimNext(kinds, now)` ↔ `claim_next(conn, kinds, now)`, `markFailed(id, error, maxAttempts, now)` ↔ `mark_failed(conn, job_id, error, max_attempts, now)` — 모든 쓰기 메서드가 `now`를 받음(DDL "DB default now() 미사용" 규칙 반영). 테이블명 복수형(`sources`/`chunks`/`chunk_embeddings`/`ingest_jobs`)이 V1·리포지토리·테스트·스모크 전반 일치. `vector(1024)` ↔ `MigrationTest` 차원 검증(1024) 일치. 패키지 경로가 ArchUnit 규칙(`com.braindeck.ingest..`)과 실제 배치(`ingest/domain`, `ingest/application/{port,service,scheduler}`, `ingest/infrastructure`) 일치.
