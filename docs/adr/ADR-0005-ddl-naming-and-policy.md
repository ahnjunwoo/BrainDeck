# ADR-0005 — DDL 네이밍과 정책

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

[[mvp-spec]] §4의 데이터 모델(`source`/`chunk`/`chunk_embedding`/`ingest_job`)을 Flyway 마이그레이션으로 구현한다. SignalDeck([[ADR-0007]] 성격)의 DDL 규칙을 계승해 일관성을 확보한다. 스펙의 테이블명은 단수형 초안이나, 실제 DDL은 아래 규칙을 따른다.

## Decision

1. **테이블**: snake_case **복수형** — `sources`, `chunks`, `chunk_embeddings`, `ingest_jobs`.
2. **컬럼**: snake_case. 도메인 camelCase와 1:1 매핑.
3. **enum**: PG ENUM 대신 **`VARCHAR + CHECK`** 제약. 제약명 `<table>_<col>_chk`.
   - 예: `sources_status_chk CHECK (status IN ('pending','parsing','parsed','enriched','failed'))`.
4. **timestamp**: 모두 `TIMESTAMPTZ`. **DB default `now()` 미사용** — 앱이 값을 주입한다.
   - 잡 큐 클레임/완료/실패 SQL도 `updated_at = :now`처럼 앱이 넘긴 시각을 쓴다.
5. **ID**: `uuid PRIMARY KEY`, **DB default 없음**. 앱이 UUIDv7 주입. → [[ADR-0004-id-uuid-v7]]
6. **인덱스**: `idx_<table>_<cols>`, unique `uq_<table>_<cols>`.
   - 잡 큐 폴링용 부분 인덱스: `idx_ingest_jobs_poll ON ingest_jobs (kind, created_at) WHERE status = 'queued'`.
7. **FK/삭제**: 자식만 `ON DELETE CASCADE`(`chunks`→`sources`, `chunk_embeddings`→`chunks`, `ingest_jobs`→`sources`).
8. **벡터/검색 인덱스**: `chunk_embeddings.embedding`에 HNSW(`vector_cosine_ops`), `chunks.content`에 pg_trgm GIN(`gin_trgm_ops`). → [[ADR-0006-embedding-provider-abstraction]]
9. **불변 컬럼**: `sources.raw_content`, `chunks.content`는 생성 후 UPDATE 금지(코드 규칙, 리뷰로 강제).

## Consequences

**좋은 점**
- PG ENUM 대비 CHECK는 값 추가/변경이 마이그레이션으로 유연.
- 타임스탬프·ID를 앱이 통제 → 테스트 결정성, 시간 왜곡 디버깅 용이.

**감수하는 비용**
- INSERT 구문이 길어진다(id·created_at·updated_at 명시). 리포지토리/테스트 헬퍼로 흡수.
- `now()` DB default 미사용이라, 순수 SQL 스모크 스크립트에서는 명시적으로 `now()`를 호출한다(규칙은 "DB default 미사용"이지 SQL 함수 호출 금지가 아님).

## 관련
- [[ADR-0004-id-uuid-v7]]
- [[ADR-0006-embedding-provider-abstraction]]
- [[ADR-0003-persistence-hybrid]]
