# ADR-0006 — 임베딩 provider 추상화와 벡터 차원

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

[[mvp-spec]] §2·§10은 임베딩을 provider 인터페이스로 추상화해 모델 교체에 대비하라고 요구한다. 데이터 모델은 `chunk_embedding(chunk_id, model, embedding vector(N))`으로 모델 버전을 관리한다. pgvector의 `vector` 컬럼은 **고정 차원**이라 첫 모델의 차원을 정해야 한다.

## Decision

1. **provider 추상화**: 임베딩 호출은 `EmbeddingProvider` 인터페이스(outbound port)로 감싼다. 구현체가 실제 API를 호출한다.
2. **1차 모델**: **Voyage `voyage-3`**, 차원 **`vector(1024)`**. `chunk_embeddings.model = 'voyage-3'`.
3. **재임베딩**: `chunk_embeddings`에 **새 `(chunk_id, model)` 행 INSERT**로만. 기존 임베딩 UPDATE 금지. → [[ADR-0005-ddl-naming-and-policy]]
4. **인덱스**: HNSW(`vector_cosine_ops`).
5. **LLM/임베딩 호출 로깅**: 프롬프트 해시·토큰 수·비용 추정을 기록([[mvp-spec]] §10).
6. **RAG 프레임워크 금지**: LangChain/LangChain4j/Spring AI 미사용. HTTP 클라이언트로 직접 구현.

## Consequences

**좋은 점**
- 모델 교체가 provider 구현 교체 + 새 model 행으로 격리된다.
- 임베딩이 원본/청크와 분리되어 원본 불변성 유지.

**감수하는 비용 / 알려진 제약**
- `vector(1024)` **단일 고정 차원 컬럼**이므로, 다른 차원 모델(예: OpenAI `text-embedding-3-large` = 3072)은 이 컬럼을 공유할 수 없다. cross-dim 교체는 **향후 스키마 마이그레이션**(별도 컬럼/테이블 또는 차원별 partial 처리)이 필요하다. MVP에서 수용한다.
- 1024차원 모델끼리(voyage-3 계열)의 교체는 새 model 행으로 문제없이 공존.

## 관련
- [[ADR-0005-ddl-naming-and-policy]]
- [[mvp-spec]]
