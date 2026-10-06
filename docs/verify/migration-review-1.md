# 이관 변경분 검증 — 레거시 문항 검색(`search.php`) → `GET /api/items/search`

- 검증 대상: `git diff upstream/main...HEAD` (커밋 3개: `da44aef`, `2b02054`, `aea9b34`, 파일 25개)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` (v0), `CLAUDE.md`
- 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test` → 54건, 실패 0, 오류 0, skip 0 (Gradle 이 `UP-TO-DATE` 로 직전 실행 결과를 재사용했고, 결과 XML 의 건수를 집계함). `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` → 38 통과, 1 skip, 실패 0 (skip 은 `example-units.test.js:21` 의 `it.skipIf(legacyOnly)` — 이번 변경 이전부터 있던 레거시 전용 케이스). 레거시 대상(8081) 실행은 서버가 꺼져 있어 못 함 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 비밀값 리터럴 없음, SQL 은 파라미터 바인딩(`ItemSearchRepository.java:29`, `:38` 의 문자열 결합은 고정 조각 · enum 상수만 이어 붙임) |
| 요청 범위 이탈 없음 | 미충족 | 요청은 "엔드포인트 1개 이관". 변경 25개 중 이관 코드(`modern/api` 소스 · 테스트 19개)와 동작 보존 테스트(`characterization/` 2개)는 설명되나, `CLAUDE.md`, `.claude/skills/*`, `docs/item-bank/*` 5개는 요청 문장으로 설명되지 않음(이슈 #2). `application.yml` 설정 변경과 `/search.php` 경로 인터셉터도 요청에 명시되지 않음(이슈 #3, #4) |
| 컨벤션 준수 | 미충족 | 이슈 #1 (`catch` 블록에 로그 · `throw` 없음) |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:165` | `CLAUDE.md` §2 "모든 `catch` 블록은 로그 호출(`log.warn`/`log.error`) 또는 `throw` 를 하나 이상 포함한다." `catch (NumberFormatException overflow)` 는 값을 포화시켜 반환만 하고 로그도 `throw` 도 없다. 컨벤션 기준 미충족의 직접 원인 | 범위 초과 숫자를 정규식으로 미리 판별해 `catch` 자체를 없애거나(예: 자릿수 비교), `catch` 안에 `log.debug`/`log.warn` 을 둔다. 로그 인자에는 사용자 입력 원문을 넣지 않는다. 동작(포화값)은 characterization 에 걸려 있으므로 그대로 유지 |
| 2 | 경고 | `CLAUDE.md`, `.claude/skills/convention-check/SKILL.md`, `.claude/skills/document-module/SKILL.md`, `docs/item-bank/*` | 요청 문장("엔드포인트 1개 이관")으로 설명되지 않는 파일. 이전 커밋(`2b02054` 등)이 `upstream/main` 이후 쌓인 것이라 diff 범위에 같이 잡힌 것으로 보임 | 사람이 확인: 이번 이관 PR 에 포함할 변경인지, 별도 PR 로 나눌지 결정. 검증 범위를 이관 커밋(`aea9b34`)만으로 좁혀 다시 돌릴 수도 있다 |
| 3 | 경고 | `modern/api/src/main/resources/application.yml:30-32`, `modern/api/src/main/java/com/example/config/WebConfig.java:16,32` | `approval-checklist.md` §3 "설정 파일 변경이 있다면 요청에 포함돼 있었다." 요청에 이 설정(`max-http-request-header-size: 32KB`)이 없다. `spring.datasource` 는 건드리지 않아 `CLAUDE.md` §3 금지 조항에는 걸리지 않음. 다만 서버 전체의 헤더 한도가 4배로 늘어나 검색 외 경로에도 적용된다 | 414 재현이 요청 범위의 일부임을 사람이 확인하고 승인 기록을 남긴다. 승인되지 않으면 설정과 인터셉터를 빼고 414 차이를 "의심 동작 목록"으로만 보고한다 |
| 4 | 경고 | `modern/api/src/main/java/com/example/config/WebConfig.java:16,32` | 새 API 에 `/search.php` 라는 레거시 경로를 인터셉터 대상으로 등록했다(주석: "이관 전 테스트가 먼저 두드림"). `/search.php` 는 modern 에 핸들러가 없어 404 이며, 인터셉터는 운영 코드에 테스트 목적의 경로를 남긴다 | `characterization/lib/target.mjs` 의 `PATH_ALIASES` 로 해결되는 영역이므로, 레거시 경로 등록을 제거하고 `ITEM_SEARCH_PATH` 만 남기는 방향을 검토 (제거 시 characterization 재실행 필요) |
| 5 | 제안 | `modern/api/src/main/java/com/example/common/GlobalExceptionHandler.java:54` | `CLAUDE.md` §2 로깅 "요청을 실패시키는 예외는 `ERROR`". 414 는 `INFO`. 기존 4xx 핸들러(`:26`, `:43`)도 `INFO` 라 일관성은 있으나 문서 규칙과는 다르다 | 팀이 4xx 는 `INFO` 로 두기로 했다면 `CLAUDE.md` 의 로깅 규칙에 그 예외를 적는다 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRepository.java:38` | `ORDER BY` 를 문자열 결합으로 만든다. 현재는 `ItemSearchSort` 의 상수만 들어가므로 인젝션 위험은 없다. 이후 `sort` 값이 외부 입력이 되면 위험해진다 | `ItemSearchSort` 가 enum 만 반환한다는 점을 주석이나 테스트로 고정해 둔다 |

## 확인 필요

- 레거시(8081) 대상 `characterization` 실행 — 서버가 꺼져 있어 실행 못 함. 스냅샷은 레거시 응답이므로, 새 API(8080)가 그것과 같다는 것은 이번에 확인했다(38 통과). 8080 서버가 `HEAD` 빌드인지는 확인하지 못했다(`/api/items/search` 가 응답했으므로 이관 코드가 들어 있는 것은 확인됨).
- `./gradlew test` 가 `UP-TO-DATE` 였다. 소스 변경 없이 직전 결과를 재사용한 것이며, 강제 재실행(`--rerun-tasks`)은 하지 않았다.
- `modern/web` 변경 없음. `legacy/`, `db/` 변경 없음(`git diff --stat` 확인).
- 이슈 #2: 이번 PR 의 의도된 범위가 `aea9b34` 한 커밋인지 사람 확인 필요.

## 반려 사유 요약

- 컨벤션 준수 미충족(#1), 요청 범위 이탈 없음 미충족(#2~#4). 치명 이슈는 없다.
- 반영 순서: #1 수정 → #2~#4 에 대해 사람이 수용 · 기각 결정 → `./gradlew test` 와 `characterization` 재실행 → 다시 검증.
