# ADR-0004 — 도메인 ID: 애플리케이션 생성 UUIDv7

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

모든 도메인 엔티티(`sources`, `chunks`, `ingest_jobs` 등)에 식별자가 필요하다. SignalDeck([[ADR-0010]] 성격)은 앱에서 생성하는 UUIDv7을 표준으로 삼는다. UUIDv7은 시간순 정렬성이 있어 인덱스 지역성과 페이지네이션에 유리하다.

## Decision

1. 모든 도메인 ID = **애플리케이션 생성 UUIDv7**.
2. 생성 지점은 `com.braindeck.common.IdGenerator` **한 곳**으로 한정한다.
   - 구현: `java-uuid-generator`(`com.fasterxml.uuid:java-uuid-generator`)의 `Generators.timeBasedEpochGenerator()`.
   - `UUID.randomUUID()`(v4) 사용 **금지**.
3. **DB에 default 없음** — INSERT 시 앱이 명시적으로 ID를 넣는다. → [[ADR-0005-ddl-naming-and-policy]]
4. **예외(테스트/스모크 편의)**: Python 워커는 잡을 **소비만** 하고 생성하지 않는다. 워커 단위 테스트·스모크 스크립트의 INSERT는 편의상 `uuid4`/`gen_random_uuid()`를 써도 된다(프로덕션 ID 생성 경로는 전부 Kotlin `IdGenerator`).

## Consequences

**좋은 점**
- 시간순 정렬 ID → B-tree/HNSW 인덱스 지역성, `ORDER BY created_at` 근사.
- ID 생성을 앱이 통제 → 저장 전에 ID를 알 수 있고, 테스트가 결정적.

**감수하는 비용**
- INSERT마다 ID를 명시해야 한다(DB default 미사용).
- Python 테스트가 v4를 쓰므로 "모든 ID가 v7"이라는 불변식은 프로덕션 경로에만 성립. 문서로 명시.

## 관련
- [[ADR-0005-ddl-naming-and-policy]]
- [[ADR-0001-architecture]]
