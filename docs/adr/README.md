# ADR 인덱스 (Architecture Decision Records)

BrainDeck의 아키텍처 결정 원문. 문서와 코드가 다르면 코드·DDL·Accepted ADR을 확인하고, 불일치는 숨기지 말고 "확인 필요"로 남긴다. 새 결정은 여기에 ADR을 추가한다.

| ADR | 제목 | 상태 | 핵심 결정 |
|---|---|---|---|
| [0001](ADR-0001-architecture.md) | 초기 아키텍처 | Accepted | Kotlin + **Spring Boot 3.x** + JDK 21, Modular Monolith + DDD + Hexagonal, Postgres 16 + pgvector 단일 DB, Python 파싱 워커 분리 |
| [0002](ADR-0002-domain-contexts.md) | 도메인 컨텍스트 + ArchUnit | Accepted | 4컨텍스트(`knowledge`/`ingest`/`retrieval`/`eval`), cross-context는 `readport`만, ArchUnit 강제 |
| [0003](ADR-0003-persistence-hybrid.md) | 영속성 하이브리드 | Accepted | 엔티티 CRUD=JPA, 벡터/큐/RRF=JdbcTemplate native |
| [0004](ADR-0004-id-uuid-v7.md) | 도메인 ID | Accepted | 앱 생성 **UUIDv7**(`common.IdGenerator`), DB default 없음, `UUID.randomUUID()` 금지 |
| [0005](ADR-0005-ddl-naming-and-policy.md) | DDL 네이밍/정책 | Accepted | 테이블 복수형, enum=VARCHAR+CHECK, TIMESTAMPTZ 앱주입, 인덱스/FK 규칙 |
| [0006](ADR-0006-embedding-provider-abstraction.md) | 임베딩 provider 추상화 | Accepted | `EmbeddingProvider` 포트, 1차 Voyage `voyage-3`/`vector(1024)`, 재임베딩=새 model 행, RAG 프레임워크 금지 |
| [0007](ADR-0007-job-queue-skip-locked.md) | 잡 큐 | Accepted | `ingest_jobs` + `FOR UPDATE SKIP LOCKED` 폴링(5초), attempts 3회 후 failed, Kotlin이 스키마 소유 |

> 참고: `[[...]]` 위키 링크의 `[[mvp-spec]]`은 `docs/spec/mvp-spec.md`, `[[ADR-xxxx-...]]`는 이 디렉토리의 해당 파일을 가리킨다.
