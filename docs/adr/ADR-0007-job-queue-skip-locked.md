# ADR-0007 — 잡 큐: Postgres 테이블 + FOR UPDATE SKIP LOCKED

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

[[mvp-spec]] §2는 메시지 큐(Redis/RabbitMQ) 도입을 금지하고, 잡 큐를 **Postgres 테이블 + `FOR UPDATE SKIP LOCKED` 폴링**으로 구현하라고 확정했다. 수집 파이프라인은 `parse`(Python 워커) → `embed`(Kotlin) → `enrich`(Kotlin) 잡으로 진행된다. 두 프로세스(Kotlin API, Python 워커)가 같은 `ingest_jobs` 테이블을 폴링한다.

## Decision

1. **큐 = `ingest_jobs` 테이블**. `kind ∈ {parse, embed, enrich}`, `status ∈ {queued, running, done, failed}`.
2. **클레임 = 원자적 UPDATE**:
   ```sql
   UPDATE ingest_jobs
   SET status='running', attempts=attempts+1, updated_at=:now
   WHERE id = (
     SELECT id FROM ingest_jobs
     WHERE status='queued' AND kind = ANY(:kinds)
     ORDER BY created_at
     FOR UPDATE SKIP LOCKED
     LIMIT 1
   )
   RETURNING id, source_id, kind, attempts;
   ```
3. **역할 분리**: Kotlin `ingest` 컨텍스트가 `embed`/`enrich`를 폴링, Python 워커가 `parse`를 폴링. 폴링 주기 **5초**.
4. **재시도**: 실패 시 `attempts >= 3`이면 `failed`, 아니면 `queued`로 되돌리고 `error` 기록. (`attempts`는 클레임 시점에 증가하므로 3번째 실패가 `failed`.)
5. **구현**: 클레임 SQL은 각 언어에서 **한 곳**만 보유 — Kotlin `ingest.infrastructure.JobRepository`, Python `braindeck_worker.queue`. 핸들러는 잡 행 SQL을 직접 만지지 않는다.
6. **스키마 소유**: **Kotlin/Flyway가 유일 스키마 소유자.** Python 워커는 마이그레이션하지 않고 행만 읽고 쓴다.
7. **타임스탬프**: `updated_at`은 앱이 넘긴 `:now`를 사용([[ADR-0005-ddl-naming-and-policy]]).

## Consequences

**좋은 점**
- 인프라 추가(브로커) 없이 단일 DB로 큐 구현. 운영 단순.
- `SKIP LOCKED`로 다중 워커가 경합 없이 서로 다른 잡을 집는다.
- 잡 상태·재시도·에러가 한 테이블에 관측 가능(디버깅 용이).

**감수하는 비용**
- 폴링 지연(최대 5초). MVP 처리량엔 충분.
- 고빈도·초저지연 요구가 생기면 브로커 재검토 필요(현재 비목표).
- `running`에서 프로세스가 죽으면 잡이 멈출 수 있다 → 향후 `running` 타임아웃 재큐 정책은 별도 ADR로(현재 골격 범위 밖).

## 관련
- [[ADR-0002-domain-contexts]]
- [[ADR-0005-ddl-naming-and-policy]]
- [[mvp-spec]]
