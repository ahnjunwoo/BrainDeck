# ADR-0003 — 영속성: JPA + JdbcTemplate 하이브리드

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

SignalDeck은 JPA(`@Entity` = 도메인 모델)를 전면 사용한다. BrainDeck도 그 컨벤션을 계승하고 싶지만, [[mvp-spec]]의 핵심 기능은 JPA/JPQL로 표현하기 어려운 native SQL을 요구한다:

- pgvector 유사도 검색(`<=>` cosine, HNSW).
- 잡 큐 `FOR UPDATE SKIP LOCKED` 클레임.
- pg_trgm 키워드 검색, RRF(Reciprocal Rank Fusion) 융합 쿼리.

## Decision

**하이브리드 영속성**을 채택한다.

1. **엔티티 CRUD = JPA**: `@Entity`가 곧 도메인 모델(도메인 메서드 포함, anemic 금지). Aggregate Root 단위 저장/조회. → [[ADR-0006-aggregate-boundaries]] 성격의 규칙은 필요 시 추가.
2. **native SQL 영역 = `NamedParameterJdbcTemplate`**:
   - pgvector 벡터 검색, `SKIP LOCKED` 잡 클레임, pg_trgm, RRF.
   - 각 native 쿼리는 해당 컨텍스트 `infrastructure`의 전용 리포지토리에 둔다(예: `ingest.infrastructure.JobRepository`).
3. **리포지토리 위치**: `infrastructure`. cross-context 읽기는 `readport` 어댑터를 통한다.
4. **1단계 범위**: 잡 큐만 다루므로 **JdbcTemplate만** 사용한다. JPA 의존성(`spring-boot-starter-data-jpa`)은 엔티티가 생기는 3단계에서 추가한다(YAGNI).

## Consequences

**좋은 점**
- SignalDeck의 JPA 도메인 컨벤션을 최대한 유지하면서, 벡터/큐/검색은 완전한 SQL 제어를 얻는다.
- 잡 클레임의 원자성(`UPDATE ... RETURNING`)을 손실 없이 구현.

**감수하는 비용**
- 한 컨텍스트 안에 JPA 리포지토리와 JdbcTemplate 리포지토리가 공존 → 트랜잭션/영속성 컨텍스트 경계에 주의(특히 같은 트랜잭션에서 JPA flush 순서).
- 두 접근의 매핑 코드가 중복될 수 있다. native는 벡터/큐/검색으로 한정해 최소화한다.

## 관련
- [[ADR-0001-architecture]]
- [[ADR-0005-ddl-naming-and-policy]]
- [[ADR-0007-job-queue-skip-locked]]
