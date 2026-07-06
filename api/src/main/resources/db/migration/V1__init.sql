CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 원본 소스. raw_content는 절대 UPDATE 금지. id/타임스탬프는 앱이 주입(DB default 없음).
CREATE TABLE sources (
    id          uuid PRIMARY KEY,
    type        varchar(20) NOT NULL,
    url         text,
    title       text NOT NULL DEFAULT '',
    raw_content text,                          -- 불변
    structure   jsonb,                         -- 워커가 추출한 섹션/제목 트리
    summary     text,                          -- LLM 생성 (이후 단계)
    tags        text[] NOT NULL DEFAULT '{}',
    status      varchar(20) NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT sources_type_chk   CHECK (type IN ('youtube','web','pdf','note','code_snippet')),
    CONSTRAINT sources_status_chk CHECK (status IN ('pending','parsing','parsed','enriched','failed'))
);
CREATE INDEX idx_sources_status ON sources (status);

-- 청크. content는 절대 UPDATE 금지.
CREATE TABLE chunks (
    id           uuid PRIMARY KEY,
    source_id    uuid NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    seq          int  NOT NULL,
    content      text NOT NULL,                -- 불변
    section_path text,                         -- 예: "3장 > RAG 평가"
    token_count  int  NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL,
    CONSTRAINT uq_chunks_source_seq UNIQUE (source_id, seq)
);
CREATE INDEX idx_chunks_content_trgm ON chunks USING gin (content gin_trgm_ops);

-- 임베딩. 모델 버전별 행. 재임베딩 = 새 (chunk_id, model) 행 INSERT.
CREATE TABLE chunk_embeddings (
    chunk_id   uuid NOT NULL REFERENCES chunks (id) ON DELETE CASCADE,
    model      varchar(50) NOT NULL,           -- 예: "voyage-3"
    embedding  vector(1024) NOT NULL,          -- voyage-3 차원 고정
    created_at timestamptz NOT NULL,
    PRIMARY KEY (chunk_id, model)
);
CREATE INDEX idx_chunk_embeddings_hnsw
    ON chunk_embeddings USING hnsw (embedding vector_cosine_ops);

-- 잡 큐. FOR UPDATE SKIP LOCKED로 폴링.
CREATE TABLE ingest_jobs (
    id         uuid PRIMARY KEY,
    source_id  uuid NOT NULL REFERENCES sources (id) ON DELETE CASCADE,
    kind       varchar(20) NOT NULL,
    status     varchar(20) NOT NULL,
    attempts   int  NOT NULL DEFAULT 0,
    error      text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ingest_jobs_kind_chk   CHECK (kind IN ('parse','embed','enrich')),
    CONSTRAINT ingest_jobs_status_chk CHECK (status IN ('queued','running','done','failed'))
);
-- 클레임 SELECT를 값싸게 하는 부분 인덱스.
CREATE INDEX idx_ingest_jobs_poll ON ingest_jobs (kind, created_at) WHERE status = 'queued';
