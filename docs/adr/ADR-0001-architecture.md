# ADR-0001 — 초기 아키텍처: Kotlin + Spring Boot 3.x 기반 Modular Monolith + DDD

- **상태**: Accepted
- **일자**: 2026-07-06
- **결정자**: 프로젝트 오너

## Context

BrainDeck은 [[mvp-spec]]에 정의된 개인용 Second Brain 서비스다. 저장된 지식(근거 인용)만으로 검색·질의응답한다. 초기 제약:

- **1인 개발/운영** 가능한 단순한 모델.
- 자매 프로젝트 **SignalDeck-be**의 아키텍처 컨벤션(Modular Monolith + DDD + Hexagonal)을 계승해 학습 비용을 줄인다.
- RAG·검색·청킹 등 도메인 로직이 명확히 응집되어야 하며, 파싱 같은 I/O 부수 작업은 분리한다.

## Decision

1. **언어/프레임워크**: Kotlin + **Spring Boot 3.x** + JDK 21, Gradle Kotlin DSL.
   - SignalDeck은 Boot 4.x지만, BrainDeck은 [[mvp-spec]] §2에서 **3.x가 확정**이다.
2. **아키텍처 스타일**: **Modular Monolith + DDD bounded context + Hexagonal(포트/어댑터)**.
   - 마이크로서비스로 시작하지 않는다. 단일 Gradle 모듈, 최상위 패키지 = 컨텍스트.
   - 컨텍스트 간 호출은 명시적 인터페이스(`application.readport`)를 통한다. → [[ADR-0002-domain-contexts]]
3. **레이어링**: 컨텍스트마다 `presentation` / `application{readport,port,service,scheduler}` / `domain` / `infrastructure`.
   - 순수 Clean Architecture의 전 레이어를 기계적으로 강제하지 않는다. JPA `@Entity`가 곧 도메인 모델이며 도메인 메서드를 가진다(anemic 금지). → [[ADR-0003-persistence-hybrid]]
4. **데이터 저장**: **PostgreSQL 16 + pgvector 단일 DB** + Flyway. 메타·원본·청크·벡터·잡큐 전부 한 DB. MQ·별도 벡터DB·ES 금지.
5. **수집 워커 분리**: Python 3.12 폴링 워커는 **파싱 전용**(입력 → 정제 텍스트 + 구조). 청킹/임베딩/태깅/도메인 로직은 전부 Kotlin.
6. **보류**: Gradle 서브모듈 분리, 인증/멀티유저, 프론트엔드 UI(비목표).

## Consequences

**좋은 점**
- 초기 운영·디버깅이 단순하다. 단일 배포 단위.
- SignalDeck과 동일 멘탈모델 → 컨벤션·도구(ArchUnit, ADR) 재사용.
- 도메인 경계가 흔들려도 패키지 이동 비용이 작다.

**감수하는 비용**
- 패키지 경계는 언어가 강제하지 않으므로 컨텍스트 간 직접 참조가 새어들 수 있다 → **ArchUnit으로 검증**([[ADR-0002-domain-contexts]]).
- Boot 3.x 고정으로 SignalDeck(4.x)과 일부 의존성 버전이 갈린다. 스펙 우선.

## 관련
- [[mvp-spec]]
- [[ADR-0002-domain-contexts]]
- [[ADR-0003-persistence-hybrid]]
