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
