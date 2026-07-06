package com.braindeck

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL(pgvector) Testcontainers 베이스. Testcontainers 2.x.
 *
 * 싱글턴 컨테이너 패턴 — JUnit5의 @Testcontainers/@Container 라이프사이클을 쓰지 않고
 * JVM 전역으로 한 번만 start. Spring context cache가 여러 테스트 클래스에서 같은
 * datasource URL을 재사용해도 컨테이너가 살아있다. 종료는 JVM 종료 시 Ryuk가 처리.
 */
@SpringBootTest
abstract class AbstractPostgresTest {

    @Autowired
    protected lateinit var jdbc: NamedParameterJdbcTemplate

    companion object {
        @JvmStatic
        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("braindeck")
                .withUsername("braindeck")
                .withPassword("braindeck")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
