# ADR-0002 — 도메인 컨텍스트 경계와 ArchUnit 의존 규칙

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

[[ADR-0001-architecture]]에서 Modular Monolith + DDD를 정했다. [[mvp-spec]]의 기능(소스 등록·수집 파이프라인·하이브리드 검색·RAG·평가 하네스)을 bounded context로 나누고, 컨텍스트 간 의존을 강제할 방법이 필요하다. 단일 사용자 MVP이므로 과도한 세분화(YAGNI)를 피한다.

## Decision

1. **1차 컨텍스트 4개** (최상위 패키지 `com.braindeck.<context>`):
   - `knowledge` — 소스 등록·원본(`raw_content`)·구조·청크·임베딩 저장. Aggregate Root = `Source`, 자식 = `Chunk` / `ChunkEmbedding`.
   - `ingest` — `ingest_jobs` 잡 큐 + 파이프라인 오케스트레이션(parse/embed/enrich). → [[ADR-0007-job-queue-skip-locked]]
   - `retrieval` — 하이브리드 검색(pgvector + pg_trgm → RRF) + RAG 질의응답(인용 강제·임계값 게이트).
   - `eval` — 평가 하네스(`eval/golden.yaml` → retrieval hit rate·인용 정확도).
2. **공용**: `common`(응답/에러/식별자/설정), `health`. 도메인 컨텍스트를 의존하지 않는다.
3. **패키지 컨벤션**: `presentation` / `application{readport, port, service, scheduler}` / `domain` / `infrastructure`.
   - `application.readport` = cross-context **inbound read 계약**(다른 컨텍스트가 import 가능한 유일 통로). 구현은 `infrastructure/*ReaderAdapter`.
   - `application.port` = **outbound port**(LLM·임베딩·파싱 등 교체 가능 의존). 구현은 `infrastructure`.
4. **의존 방향**: `knowledge`(저장) → `ingest`(파이프라인) → `retrieval`(검색/RAG). `eval`은 `retrieval`을 읽는다. **역방향 금지.**
5. **ArchUnit 강제** (`com.braindeck.architecture.ArchitectureTest`):
   - 컨텍스트 간에는 상대 `application.readport`만 import. 상대의 `domain`/`application.service`/`infrastructure`/`presentation` import 금지.
   - `common`/`health`는 도메인 컨텍스트 의존 금지.
   - 미구현 컨텍스트 패키지는 `allowEmptyShould(true)`로 통과시키되 규칙은 4컨텍스트 전부 미리 정의한다.
6. **미리 만들지 않는다**: 컨텍스트가 실제로 필요해질 때 패키지를 채운다. 1단계에선 `ingest` + 스키마(`knowledge` 테이블)만 구현.

## Consequences

**좋은 점**
- 읽기는 자유(readport), 쓰기·내부는 차단 → 결합도 통제.
- 컨텍스트 의존 위반이 컴파일이 아닌 테스트로 조기 검출.

**감수하는 비용**
- 잡 핸들러(embed/enrich)의 소속 이동이 필요할 수 있다. 1단계는 `ingest` 내부 스텁, 실제 로직 생기면 `knowledge`가 `ingest.application.port.JobHandler`를 구현하도록 이동.
- 4컨텍스트가 단일 사용자엔 다소 많을 수 있으나, SignalDeck 대비 절반 수준으로 억제했다.

## 관련
- [[ADR-0001-architecture]]
- [[ADR-0007-job-queue-skip-locked]]
