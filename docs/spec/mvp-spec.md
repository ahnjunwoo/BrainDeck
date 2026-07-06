# Personal Knowledge OS — MVP 구현 스펙

이 문서는 프로젝트의 단일 기준(source of truth)이다. 여기 명시된 결정 사항을 임의로 변경하지 말 것. 애매한 부분은 구현 전에 질문할 것.

## 1. 프로젝트 목표

내가 학습한 지식(유튜브, 블로그, 공식 문서, PDF, 책 메모, 내 생각, 회고, 코드 스니펫)을 하나의 저장소에 모으고, **저장된 지식만을 기반으로** 검색·질의응답하는 개인용 Second Brain 서비스.

핵심 원칙: 인터넷 검색 기반 답변이 아니라, 내 저장소에 근거(citation)가 있는 답변만 허용한다.

## 2. 확정된 기술 결정 (변경 금지)

- 메인 백엔드: **Kotlin + Spring Boot 3.x** (JDK 21, Gradle Kotlin DSL)
- 수집 워커: **Python 3.12 + FastAPI 없이 단순 폴링 워커** (파싱 전용, 도메인 로직 금지)
- 저장소: **PostgreSQL 16 + pgvector** 단일 DB (메타데이터, 원본, 청크, 벡터, 잡 큐 전부)
- 메시지 큐 도입 금지. 잡 큐는 Postgres 테이블 + `FOR UPDATE SKIP LOCKED` 폴링
- LLM/임베딩: 호스팅 API 사용 (Anthropic Claude로 답변 생성, 임베딩은 provider 인터페이스로 추상화하여 교체 가능하게)
- RAG 프레임워크(LangChain, LangChain4j, Spring AI 등) 사용 금지. HTTP 클라이언트로 직접 구현
- 로컬 개발: docker-compose (postgres + kotlin-api + python-worker)

## 3. 아키텍처

```
[Client/API 호출]
      │
      ▼
Kotlin/Spring API ──────────────► LLM · 임베딩 API (호스팅)
  검색 · RAG · 청킹 · 도메인 로직        ▲
      │                              │
      ▼                              │
PostgreSQL + pgvector ◄── 폴링 ── Python 수집 워커
  원본 · 청크 · 벡터 · 잡 큐           파싱 · 자막 · 스크래핑
```

역할 분리 규칙:
- Python 워커는 "입력(URL/파일) → 정제된 텍스트 + 구조(제목/섹션)"만 출력한다. 청킹, 임베딩, 태깅 로직을 절대 넣지 않는다.
- 청킹 정책, 임베딩 호출, RAG 오케스트레이션, LLM 보강은 전부 Kotlin에 둔다.

## 4. 데이터 모델 (초안 — 구현 시 이 구조 유지)

```sql
-- 원본 소스 (불변 원칙: raw_content는 수정하지 않는다)
source (
  id uuid PK,
  type text,             -- youtube | web | pdf | note | code_snippet
  url text NULL,
  title text,
  raw_content text,      -- 파싱된 전체 텍스트, 불변
  structure jsonb,       -- 섹션/제목 트리 (워커가 추출)
  summary text NULL,     -- LLM 생성
  tags text[] DEFAULT '{}',
  status text,           -- pending | parsing | parsed | enriched | failed
  created_at, updated_at
)

-- 청크 (원본 불변, 임베딩과 분리)
chunk (
  id uuid PK,
  source_id uuid FK,
  seq int,
  content text,          -- 불변
  section_path text NULL, -- 예: "3장 > RAG 평가"
  token_count int
)

-- 임베딩 (모델 버전 관리 — 재임베딩 대비 핵심 설계)
chunk_embedding (
  chunk_id uuid FK,
  model text,            -- 예: "voyage-3", "text-embedding-3-large"
  embedding vector(N),
  PRIMARY KEY (chunk_id, model)
)
-- HNSW 인덱스 필수

-- 잡 큐
ingest_job (
  id uuid PK,
  source_id uuid FK,
  kind text,             -- parse | embed | enrich
  status text,           -- queued | running | done | failed
  attempts int,
  error text NULL,
  created_at, updated_at
)
```

## 5. MVP 기능 범위 (이것만 구현, 추가 금지)

1. **소스 등록 API**: URL(웹/유튜브), PDF 업로드, 직접 텍스트 입력
2. **수집 파이프라인**: 등록 → parse 잡 → Python 워커 파싱 → Kotlin이 청킹 → embed 잡 → 임베딩 저장 → enrich 잡 → 요약 + 태깅 (문서당 LLM 호출 1회로 요약과 태그를 동시 생성)
3. **하이브리드 검색 API**: pgvector 유사도 검색 + pg_trgm 키워드 검색 → RRF로 융합 → 상위 K 반환
4. **RAG 질의응답 API**: 하이브리드 검색 → 상위 청크를 컨텍스트로 Claude 호출 → 답변
   - 모든 문장에 청크 인용 ID 포함 강제
   - 검색 최고 점수가 임계값 미만이면 LLM 호출 없이 "저장된 지식에 없습니다" 반환
   - 시스템 프롬프트에 "제공된 컨텍스트 외 지식으로 답하지 말 것" 명시
5. **평가 하네스**: `eval/golden.yaml`(질문-기대근거 쌍, 내가 채움)을 읽어 retrieval hit rate(top-5, top-10)와 인용 정확도를 출력하는 CLI. **기능 구현보다 이 하네스를 먼저 만든다.**

## 6. 명시적 비목표 (구현하지 말 것)

- Knowledge Graph / GraphRAG / 그래프 DB
- 챕터 생성, Action Item 생성 (성장 단계로 연기)
- 지식 연결/관련 노트 추천 (성장 단계)
- 리랭커, 쿼리 재작성 (성장 단계)
- 메시지 큐(Redis/RabbitMQ), Elasticsearch/OpenSearch, 별도 벡터 DB
- 프론트엔드 UI (API까지만. 테스트는 http 파일 또는 curl)
- 인증/멀티유저 (단일 사용자 전제)

## 7. Python 워커 상세

- 유튜브: youtube-transcript-api → 자막 획득 → **LLM으로 자막 정제 단계 필수** (한국어 자동 자막의 오탈자/문장 경계 복원. 정제 프롬프트는 "내용 추가·요약 금지, 문장 복원만" 명시)
- 웹: trafilatura로 본문 추출
- PDF: PyMuPDF 기본, 표/2단 레이아웃은 1차에서 best-effort (marker 도입은 이후)
- 실패 시 ingest_job.error에 원인 기록, attempts 3회 후 failed
- 워커는 stateless, DB 폴링 주기 5초

## 8. 청킹 정책 (Kotlin)

- 구조 인식 우선: 워커가 준 섹션 경계를 넘지 않게 분할
- 목표 400~600 토큰, 오버랩 없음
- 각 청크 임베딩 입력에 `[문서제목 > 섹션경로]` 프리픽스 포함
- 청크 content 자체에는 프리픽스를 저장하지 않음 (section_path 컬럼으로 분리)

## 9. 작업 순서

1. docker-compose + 스키마 마이그레이션(Flyway) + 잡 큐 폴링 골격
2. 평가 하네스 CLI (더미 검색으로라도 먼저 동작하게)
3. 직접 텍스트 입력 소스 → 청킹 → 임베딩 → 벡터 검색 (최소 수직 슬라이스)
4. 하이브리드 검색(RRF) + RAG 질의응답 + 인용 강제
5. Python 워커: 웹 → 유튜브 → PDF 순서로 파서 추가
6. enrich(요약/태깅) 파이프라인

각 단계는 독립적으로 동작 확인 후 다음 단계로. 한 번에 전체를 만들지 말 것.

## 10. 품질 규칙

- 임베딩/LLM provider는 인터페이스로 추상화 (모델 교체 대비)
- raw_content와 chunk.content는 생성 후 절대 UPDATE 금지
- 재임베딩은 chunk_embedding에 새 model 행 추가 방식으로만
- LLM 호출은 전부 로깅 (프롬프트 해시, 토큰 수, 비용 추정)
- 통합 테스트는 Testcontainers(postgres + pgvector)
