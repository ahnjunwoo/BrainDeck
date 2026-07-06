# BrainDeck 작업 핸드오프 — 2026-07-07

다음 세션에서 이 문서부터 읽고 이어서 진행. 단일 기준은 `docs/spec/mvp-spec.md`, 규칙은 `CLAUDE.md`, 결정은 `docs/adr/`.

## 1. 전체 상태 한눈에

| 항목 | 상태 |
|---|---|
| 하네스 세팅 | ✅ 완료 (`main`, 커밋 `1894b09`) |
| 1단계 계획 | ✅ `docs/superpowers/plans/2026-07-06-step1-foundation.md` (5 태스크) |
| Task 1 (골격+스키마+ArchUnit+Testcontainers) | ✅ **완료 — 코드·정적리뷰·실테스트 all green** (`feat/step1-foundation`) |
| Docker/Testcontainers 블로커 | ✅ **해결됨** (아래 4절) |
| Task 2~5 | ⬜ 미착수 (Task 2 brief는 `.superpowers/sdd/task-2-brief.md`에 준비됨) |

작업 방식: **superpowers:subagent-driven-development**. ledger = `.superpowers/sdd/progress.md`, BASE = `1894b09`, 브랜치 = `feat/step1-foundation`.

## 2. 다음 세션 할 일 (바로 시작)

1. Docker 확인: `sg docker -c 'docker info' ` 또는 WSL 재시작 후 `docker info`.
2. **Task 2 구현 SA dispatch** — brief `.superpowers/sdd/task-2-brief.md` (ingest 컨텍스트 잡 클레임 리포지토리, FOR UPDATE SKIP LOCKED). 구현/리뷰 SA에게 **4절 실행 패턴** 지시.
3. Task 2 완료 → Task 3(폴러+스텁 핸들러) → Task 4(Python 워커) → Task 5(compose+스모크).
4. 5개 태스크 후 **전체 브랜치 최종 리뷰** → `superpowers:finishing-a-development-branch` → main 병합 결정.

## 3. Task 1 정적 리뷰 Minor (최종 리뷰에서 정리)

1. `ArchitectureTest`에 `health` 격리 규칙 누락(현재 `common`만). 글로벌 제약은 common/health 둘 다 도메인 의존 금지. → 추가 권장:
   ```kotlin
   @ArchTest val healthIsolated: ArchRule = noClasses().that().resideInAPackage("$ROOT.health..")
       .should().dependOnClassesThat().resideInAnyPackage(*CONTEXTS.map { "$ROOT.$it.." }.toTypedArray())
       .allowEmptyShould(true)
   ```
2. `MigrationTest`가 exact-set이 아닌 `containsAll` 비교(허용 가능; `flyway_schema_history` 존재).

## 4. Docker/Testcontainers 실행 패턴 (중요 — SA에게도 지시)

```bash
sg docker -c 'cd /mnt/c/Users/cutes/dev/BrainDeck/api && ./gradlew <task> --no-daemon --console=plain'
```
- **`sg docker`**: `codegun`이 `docker` 그룹에 추가됐으나 셸 세션 미반영 → 소켓 접근 위해 필요. **WSL 재시작(`wsl --shutdown`)하면 불필요**해질 수 있음.
- **`--no-daemon`**: gradle 데몬이 docker 그룹을 상속 못 하는 문제 회피.

### 겪은 블로커와 해결 (재발 시 참고)
- 증상: Testcontainers 테스트가 `Could not find a valid Docker environment` / docker-java가 `/var/run/docker.sock`에 raw `/info` 시 **Status 400**.
- 진짜 원인: **Spring Boot 3.3의 dependency-management가 Testcontainers `core`를 1.19.8로 강제**. `junit-jupiter`/`postgresql`만 2.0.5로 명시하면 core만 구버전 → docker-java 구버전이 Docker Desktop 29.x 소켓과 불화(Status 400).
- **해결**: `build.gradle.kts`에 `extra["testcontainers.version"] = "2.0.5"` 추가 → core 포함 전체 2.0.5로 통일. (`org.testcontainers:testcontainers:2.0.5`, 더 이상 `-> 1.19.8` 안 됨.)
- 부수 수정: `AbstractPostgresTest`를 Testcontainers 2.x API(싱글턴 `.also { it.start() }`, `org.testcontainers.postgresql.PostgreSQLContainer`)로.
- 판별법: SignalDeck `com.signaldeck.persistence.FlywayJpaIntegrationTest`가 같은 환경서 통과 → 환경 무죄, BrainDeck 설정 차이임을 확인했음.

## 5. 참고
- 원격: `github.com/ahnjunwoo/BrainDeck.git` (origin). `main`, `feat/step1-foundation` 푸시됨.
- git author(로컬): `ahnjunwoo <cutesboy2@gmail.com>` (SignalDeck과 동일).

## 6. macOS에서 이어받기 (플랫폼 전환)

- **clone & 브랜치**: `git clone https://github.com/ahnjunwoo/BrainDeck.git && cd BrainDeck && git checkout feat/step1-foundation`
- **설치**: JDK 21, Docker Desktop for Mac(실행해두기).
- **git author 재설정**(repo local config는 WSL에만 있고 clone에 안 옴):
  `git config user.name "ahnjunwoo" && git config user.email "cutesboy2@gmail.com"`
- **WSL 전용 패턴은 macOS에서 전부 불필요**: `sg docker`, `--no-daemon` 빼고 그냥 `cd api && ./gradlew test`. (그룹/데몬 이슈는 WSL 한정)
- **Testcontainers 400 fix는 플랫폼 무관하게 유효**(`extra["testcontainers.version"]="2.0.5"` 이미 커밋). macOS Docker Desktop에서도 그대로 동작.
- **gradlew 실행권한**: 이 커밋에서 `+x`로 수정함 → macOS clone 후 바로 `./gradlew` 실행 가능.
- **`.superpowers/`(ledger·task brief)는 gitignore라 clone에 없음**: 이 HANDOFF와 계획서로 재개. Task 2 brief는 스크립트로 재생성:
  `<superpowers>/skills/subagent-driven-development/scripts/task-brief docs/superpowers/plans/2026-07-06-step1-foundation.md 2`
- **첫 검증**: `cd api && ./gradlew test` 가 green이면 환경 정상 → Task 2 진행.
