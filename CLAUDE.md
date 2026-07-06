# BrainDeck — Claude 작업 가이드 (CLAUDE.md)

## 0. 이 문서와 우선순위

- **단일 기준(SoT):** `docs/spec/mvp-spec.md` (Personal Knowledge OS MVP 구현 스펙). 여기 결정은 임의 변경 금지. 애매하면 구현 전에 질문한다.
- **아키텍처 결정 원문:** `docs/adr/` (ADR). 문서와 코드가 다르면 코드·DDL·Accepted ADR을 확인하고, 불일치는 숨기지 말고 "확인 필요"로 남긴다.
- 임시 분석/메모는 `.scratch/` (git 제외)에 남긴다.
- 아키텍처 컨벤션은 자매 프로젝트 **SignalDeck-be** (`/mnt/c/Users/cutes/dev/SignalDeck-be`)를 차용한다: Modular Monolith + DDD + Hexagonal.

## 1. 프로젝트 개요

학습한 지식을 한 저장소에 모으고, **저장된 지식(근거 인용)만으로** 검색·질의응답하는 개인용 Second Brain. 인터넷 검색 기반 답변 금지, citation 없는 답변 금지.

## 2. 아키텍처: Modular Monolith + DDD + Hexagonal

순수 클린아키텍처의 전 레이어를 기계적으로 강제하지는 않는다(SignalDeck ADR-0001 방침 계승). JPA `@Entity`가 곧 도메인 모델이며 도메인 메서드를 가진다(anemic 금지).

**최상위 패키지 = bounded context**, 그 안에 레이어를 둔다. 컨텍스트는 4개:

- `knowledge` — 소스 등록·원본(`raw_content`)·구조·청크·임베딩 저장. Aggregate Root = `Source`, 자식 = `Chunk` / `ChunkEmbedding`.
- `ingest` — `ingest_jobs` 잡 큐 + 파이프라인 오케스트레이션(parse/embed/enrich). `FOR UPDATE SKIP LOCKED` 폴링.
- `retrieval` — 하이브리드 검색(pgvector + pg_trgm → RRF) + RAG 질의응답(인용 강제·임계값 게이트).
- `eval` — 평가 하네스(`eval/golden.yaml` → retrieval hit rate·인용 정확도).
- `common`(응답/에러/식별자/설정), `health`는 도메인 컨텍스트를 의존하지 않는다.

패키지 컨벤션:
```
com.braindeck.<context>.{
  presentation,                 # REST 컨트롤러 (자기 컨텍스트만)
  application/{
    readport,                   # cross-context inbound read 계약(다른 컨텍스트가 import 가능한 유일 통로)
    port,                       # outbound port (LLM·임베딩·파싱 등 교체 가능한 외부 의존)
    service,                    # write/command 유스케이스 (외부 컨텍스트 노출 금지)
    scheduler                   # @Scheduled 배치
  },
  domain,                       # 엔티티(@Entity) + enum + 도메인 정책
  infrastructure                # JpaRepository, JdbcTemplate, ReaderAdapter, 외부 어댑터
}
```

**의존 규칙 (ArchUnit 강제 — `com.braindeck.architecture.ArchitectureTest`):**
- 컨텍스트 간 읽기는 상대 컨텍스트의 `application.readport`만 import한다. 상대의 `domain` / `application.service` / `infrastructure` / `presentation`을 직접 import 금지.
- `common` / `health`는 도메인 컨텍스트를 의존 금지.
- 컨텍스트 의존 관계를 바꾸면 `ArchitectureTest`를 포함해 검증한다.

## 3. 영속성 (하이브리드)

- **엔티티 CRUD = JPA** (`@Entity` = 도메인 모델, 도메인 메서드 포함).
- **pgvector 유사도검색 / `FOR UPDATE SKIP LOCKED` 잡큐 / pg_trgm / RRF = `NamedParameterJdbcTemplate` native SQL.** (JPA만으로 부적합한 영역)
- 리포지토리는 `infrastructure`에 둔다. cross-context 읽기는 `readport` 어댑터를 통한다.

## 4. 기술 스택 (스펙 §2 — 변경 금지)

- Kotlin + **Spring Boot 3.x**, JDK 21, Gradle Kotlin DSL. (SignalDeck은 Boot 4.x지만 BrainDeck 스펙은 3.x가 확정이다.)
- **PostgreSQL 16 + pgvector 단일 DB** (메타·원본·청크·벡터·잡큐 전부). MQ 금지, 별도 벡터DB/ES 금지.
- **Flyway**가 스키마 기준. 기존 migration을 임의로 되돌리지 않는다.
- LLM = Anthropic Claude(답변 생성). **임베딩 = provider 인터페이스로 추상화**(1차 Voyage `voyage-3`, `vector(1024)`). 재임베딩 = `chunk_embeddings`에 새 `model` 행 추가.
- RAG 프레임워크(LangChain / LangChain4j / Spring AI) 금지. HTTP 클라이언트로 직접 구현.
- Python 3.12 폴링 워커: **파싱 전용**(입력 → 정제 텍스트 + 구조). 청킹/임베딩/태깅/도메인 로직 절대 금지.

## 5. DDL 규칙 (SignalDeck ADR-0007 차용)

- 테이블 snake_case **복수형**(`sources`, `chunks`, `chunk_embeddings`, `ingest_jobs`). 컬럼 snake_case(도메인 camelCase와 1:1).
- enum은 PG ENUM 대신 **VARCHAR + CHECK 제약**(`<table>_<col>_chk`).
- 모든 timestamp **TIMESTAMPTZ**. **DB default `now()` 미사용** — 앱이 값을 주입한다.
- 도메인 ID = **애플리케이션 생성 UUIDv7**(`common.IdGenerator`, `java-uuid-generator`). **DB default 없음**, `UUID.randomUUID()` 금지.
- 인덱스 `idx_<table>_<cols>`, unique `uq_<table>_<cols>`, 자식만 `ON DELETE CASCADE`.

## 6. 핵심 도메인 규칙 (불변식)

- `sources.raw_content`, `chunks.content`는 생성 후 **절대 UPDATE 금지**.
- **RAG:** 모든 문장에 청크 인용 ID 강제. 검색 최고 점수 < 임계값이면 LLM 호출 없이 "저장된 지식에 없습니다" 반환. 시스템 프롬프트에 "제공된 컨텍스트 외 지식 사용 금지" 명시.
- **청킹(Kotlin):** 섹션 경계 존중, 400~600 토큰, 오버랩 없음. 임베딩 입력에 `[문서제목 > 섹션경로]` 프리픽스 포함(청크 `content`엔 미저장, `section_path` 컬럼으로 분리).
- **LLM 호출 전부 로깅**(프롬프트 해시, 토큰 수, 비용 추정).
- **잡 큐:** `attempts` 3회 후 `failed`. 폴링 주기 5초.

## 7. 코딩 원칙 (SignalDeck AGENTS.md 차용)

1. **먼저 생각하고 코딩:** 모호하면 멈추고 질문. placeholder / mock 금지.
2. **YAGNI:** 두 번째 실사용처에서 추상화한다.
3. **최소 diff:** 무관한 리팩토링/포맷/리네이밍/import 정리 금지.
4. **목표지향:** 버그는 재현 테스트를 먼저 쓴다.

## 8. 테스트

- 통합 테스트 = **Testcontainers**(`pgvector/pgvector:pg16`).
- **ArchUnit**으로 컨텍스트 경계 검증.
- 버그는 재현 테스트 먼저. API 변경은 controller/application 검증. DB/마이그레이션은 통합 테스트.
- 스펙 우선순위상 **평가 하네스를 기능 구현보다 먼저** 만든다(§9-2).

## 9. 작업 순서 (스펙 §9)

1. docker-compose + Flyway 스키마 + 잡큐 폴링 골격 (+ ArchUnit 골격) ← **현재 단계**
2. 평가 하네스 CLI (더미 검색으로라도 먼저 동작)
3. 텍스트 입력 → 청킹 → 임베딩 → 벡터 검색 (최소 수직 슬라이스)
4. 하이브리드 검색(RRF) + RAG + 인용 강제
5. Python 워커: 웹 → 유튜브 → PDF
6. enrich(요약/태깅)

각 단계는 독립 동작 확인 후 다음으로. 한 번에 전체를 만들지 않는다.

## 10. 금지 / 비목표 (스펙 §6)

- 인터넷 검색 기반 답변, 출처 없는 답변.
- Knowledge Graph / GraphRAG / 그래프DB, 챕터·액션아이템 생성, 지식연결 추천, 리랭커·쿼리재작성.
- MQ(Redis/RabbitMQ), Elasticsearch/OpenSearch, 별도 벡터DB.
- 프론트엔드 UI(API까지만), 인증/멀티유저.

## 11. 완료 조건 & 커밋

- 보고에 변경 파일·검증(테스트) 실행 결과·남은 질문을 포함한다. 새 아키텍처 결정은 `docs/adr/`에 기록.
- 커밋/푸시는 사용자가 요청·승인했을 때만. 한 커밋은 한 목적.
- 커밋 메시지 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
