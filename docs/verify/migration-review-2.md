# 이관 변경분 2차 검증 — 레거시 문항 검색(`search.php`) → `GET /api/items/search`

- 검증 대상: `git diff upstream/main...HEAD` (커밋 4개: `da44aef`, `2b02054`, `aea9b34`, `cfd7752`, 파일 26개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` (v0), `CLAUDE.md`
- 1차 검증: `docs/verify/migration-review-1.md` (반려). 이번은 `cfd7752`(1차 리뷰 반영) 이후의 재검증이다.
- 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test --rerun-tasks` → 54건, 실패 0, 오류 0, skip 0 (이번에는 `UP-TO-DATE` 재사용 없이 전부 재실행, 결과 XML 집계). `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` → 38 통과, 1 skip, 실패 0 (skip 은 `example-units.test.js:21` 의 `it.skipIf(legacyOnly)`, 이번 변경 이전부터). 단 8080 서버가 `cfd7752` 보다 먼저 떠 있어 이 실행은 1차 수정 이전 빌드를 대상으로 했을 수 있음 → 확인 필요 1번. 레거시(8081) 대상은 서버가 꺼져 있어 실행 못 함 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 없음. SQL 은 파라미터 바인딩(`ItemSearchRepository.java:29`, `:37-38` 의 문자열 결합은 고정 조각과 enum 상수 `ORDER BY` 뿐). 데이터를 쓰거나 지우는 코드 없음(`@Transactional(readOnly = true)`) |
| 요청 범위 이탈 없음 | 미충족 | 요청은 "엔드포인트 1개 이관". 1차에서 지적한 #2~#4 가 그대로이고, 수용 · 기각 기록도 없다(이슈 #1~#3). 이번 라운드에 `docs/verify/migration-review-1.md` 가 추가됨(이슈 #4) |
| 컨벤션 준수 | 충족 | 1차 #1(`catch` 에 로그 · `throw` 없음)은 `ItemSearchCondition.java:165-173` 에서 `catch` 자체를 없애 해소됨. 이 변경분에 `catch` 블록 · `System.out` · `printStackTrace` · 인자 없는 `now()` 없음. Controller 는 Service 호출만(`ItemSearchController.java:26`), 반환 타입은 record DTO. 새 Service 메서드 · 엔드포인트마다 테스트 있음. 이슈 #5 는 제안 수준 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/resources/application.yml:30-32`, `modern/api/src/main/java/com/example/config/RequestLineLimitInterceptor.java:14-29`, `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:47-56` | (1차 #3 미해소) `approval-checklist.md` §3 "설정 파일 변경이 있다면 요청에 포함돼 있었다." 요청 문장에 `server.max-http-request-header-size: 32KB` 와 414 흉내가 없다. 이 설정은 검색 외 모든 경로의 헤더 한도를 8KB → 32KB 로 4배 올린다. `spring.datasource` 는 건드리지 않아 `CLAUDE.md` §3 금지 조항에는 걸리지 않는다 | 사람이 "414 재현도 이관 범위"임을 확인하고 승인 기록을 남긴다. 승인되지 않으면 설정 · 인터셉터 · 예외 · 핸들러를 빼고 414 차이를 "의심 동작 목록"으로만 보고한다(제거 시 characterization #15a 재실행 필요) |
| 2 | 경고 | `modern/api/src/main/java/com/example/config/WebConfig.java:16`, `:29-33` | (1차 #4 미해소) 운영 코드에 `LEGACY_SEARCH_PATH = "/search.php"` 를 인터셉터 대상으로 등록했다. modern 에 `/search.php` 핸들러가 없어 404 인 경로이고, 테스트 목적("이관 전 테스트가 먼저 두드림")이 운영 코드에 남아 있다. 이 경로 전용 테스트(`ItemSearchControllerTest.java:98`)도 같이 묶여 있다 | `characterization/lib/target.mjs` 의 `PATH_ALIASES` 로 해결되는 영역인지 확인하고, `/search.php` 등록과 해당 테스트를 제거해 `ITEM_SEARCH_PATH` 만 남긴다. 제거 후 characterization 재실행 |
| 3 | 경고 | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `docs/item-bank/*`(4개), `characterization/*` | (1차 #2 미해소) 요청 문장("엔드포인트 1개 이관")만으로 설명되지 않는 파일 9개가 `upstream/main...HEAD` 에 포함된다. `characterization/` 2개는 동작 보존 근거라 설명되지만 나머지는 아니다 | 사람이 이번 PR 범위를 정한다: 문서 · Skill 변경을 별도 PR 로 나누거나 "이관 + 근거 문서"를 한 PR 로 본다고 기록. 필요하면 검증 범위를 `aea9b34` 로 좁혀 다시 돌린다 |
| 4 | 제안 | `docs/verify/migration-review-1.md` | 검증 산출물이 이관 PR diff 에 들어간다. 요청 범위 설명에는 필요 없지만 검증 루프 기록으로는 의미가 있다 | 이관 PR 에 포함할지 별도로 둘지 정한다. 포함한다면 `migration-review-2.md` 도 같은 기준으로 처리 |
| 5 | 제안 | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:54` | (1차 #5) `CLAUDE.md` §2 로깅 "요청을 실패시키는 예외는 `ERROR`", 414 는 `INFO`. 기존 4xx 핸들러도 `INFO` 라 일관성은 있으나 문서 규칙과 다르다 | 4xx 를 `INFO` 로 두기로 했다면 `CLAUDE.md` 로깅 규칙에 예외로 적는다 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:38` | (1차 #6) `ORDER BY` 문자열 결합. 지금은 `ItemSearchSort` 상수뿐이라 인젝션 위험은 없다 | `ItemSearchSort` 가 enum 값만 반환한다는 점을 주석이나 테스트로 고정 |

## 1차 이슈 처리 현황

| 1차 # | 상태 | 비고 |
|---|---|---|
| 1 (`catch` 컨벤션) | 해소 | `ItemSearchCondition.java:165-173` BigInteger 비교로 대체. 포화 동작은 단위 테스트 `ItemSearchConditionTest.java:90-91`, `:141` 이 고정 |
| 2 (범위 밖 파일) | 미해소 | 이번 #3 |
| 3 (`application.yml` 설정) | 미해소 | 이번 #1 |
| 4 (`/search.php` 인터셉터) | 미해소 | 이번 #2 |
| 5 · 6 (제안) | 미반영 | 이번 #5 · #6 |

## 확인 필요

1. 8080 서버가 `cfd7752` 이전 빌드일 수 있다. 서버 프로세스 시작이 12:47:20 이고 `cfd7752` 커밋이 12:49:11 이다(`ps`, `git log`). 이번 characterization 38 통과는 `phpIntval` 수정 전 코드를 두드린 결과일 수 있다. 수정은 오버플로 입력(`level`, `page` 의 20자리 숫자)에 관한 것이므로, 사람이 8080 을 `./gradlew bootRun` 으로 재기동한 뒤 `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` 를 다시 돌려야 최종 확인이 된다.
2. 레거시(8081) 대상 `characterization` 실행 — 서버가 꺼져 있어 실행 못 함. 스냅샷이 레거시 응답이라 새 API 가 그것과 같다는 것까지만 확인했다.
3. `ItemSearchCondition.java:176-178` 은 무한대 · NaN 이면 0 을 반환한다(`level=1e999` 등). PHP 7.4 의 문자열 `(int)` 변환이 이 경우 0 인지 포화값인지는 PHP 소스로 확인하지 못했다. 어느 쪽이든 `level` 이 1~5 밖이라 결과는 0건으로 같고 `page` 는 숫자만 허용해 해당 없음 → 관찰 가능한 차이는 없을 것으로 보지만, 레거시 서버를 띄워 `level=1e999` 로 비교해 보면 확정된다.
4. `modern/web`, `legacy/`, `db/` 변경 없음(`git diff --stat` 확인). `.claude/skills/verify/` 는 미추적 파일이라 이 diff 에 포함되지 않았다.

## 반려 사유 요약

- 요청 범위 이탈 없음 미충족(#1~#3): 1차 지적 3건이 사람의 수용 · 기각 결정 없이 남아 있다. 치명 이슈는 없고, 컨벤션 · 테스트 기준은 충족했다.
- 반영 순서: #1~#3 에 대해 사람이 수용(승인 기록) 또는 기각(코드 제거) 결정 → 8080 재기동 후 `./gradlew test` 와 `characterization` 재실행 → 3차 검증.
