---
name: convention-check
description: CLAUDE.md 의 코딩 컨벤션과 금지 사항 가운데 코드만 보고 판정할 수 있는 8개 항목으로 변경 파일(또는 지정한 경로)을 점검하고, 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있나 봐 줘" 같은 요청이나 커밋 · PR 직전에 쓴다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일 전체)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- 인자(`$ARGUMENTS`)가 있으면 그 경로만 본다. 디렉터리면 Glob 으로 그 아래 파일을 모은다.
- 인자가 없으면 아래 두 명령의 결과를 합친다. 삭제된 파일은 뺀다.
  - `git status --porcelain` — `??` 로 표시되는 아직 add 하지 않은 새 파일도 넣는다
  - `git diff HEAD --name-only` — staged · unstaged 변경을 모두 잡는다
- 대상이 없으면 "점검할 변경 없음"이라고만 답하고 끝낸다.

## 2. 점검 항목 (예 = 통과)

| # | 항목 | 대상 |
|---|---|---|
| C1 | Controller 의 필드 · 생성자 인자에 `*Repository`, `EntityManager`, `JdbcTemplate` 타입이 없다 | `*Controller.java` |
| C2 | Controller 메서드 반환 타입이 `*Response` record 이며 `@Entity` 클래스가 아니다 | `*Controller.java` + 반환 타입 클래스 |
| C3 | Controller 메서드 안에 `try`/`catch` 가 없다 | `*Controller.java` |
| C4 | 모든 `catch` 블록에 `log.warn`/`log.error` 또는 `throw` 가 있다 | `modern/api/**/*.java` |
| C5 | `System.out.println`, `System.err.println`, `.printStackTrace()` 가 없다 | `modern/api/**/*.java` |
| C6 | Service 에 인자 없는 `now()` 가 없다(`now(clock)` 만 쓴다) | `*Service.java` |
| C7 | 모든 테스트 클래스에 `@ActiveProfiles("test")` 가 있다 | `modern/api/src/test/**/*Test.java` |
| C8 | 확인이 필요한 파일을 바꾸지 않았다: `build.gradle` 의 `dependencies`, `package.json` 의 `dependencies` · `devDependencies`, `db/**/init/*.sql`, `application.yml` 의 `spring.datasource` | 해당 파일의 `git diff` |

- 항목의 대상에 해당하는 파일이 없으면 그 항목은 "해당 없음"으로 판정한다.
- Grep 으로 후보를 찾고, 위반으로 적기 전에 Read 로 그 줄과 앞뒤 문맥을 직접 확인한다.
- 인자로 받은 경로를 점검할 때도 파일 전체를 본다. 변경 파일 모드에서는 파일 전체를 보되, C8 은 diff 에 나온 줄만 본다.
- C2 에서 반환 타입 클래스를 찾을 수 없으면 위반이 아니라 "확인 필요"로 적는다.

## 3. 출력 형식 (순서 고정)

### 판정 요약
점검 대상: 파일 N개 (파일 목록)

| # | 판정 |
|---|---|
| C1 | 통과 / 위반 n건 / 해당 없음 / 확인 필요 |
| … | … |

### 위반 목록
| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../FooController.java:23` | C1 Controller 에 Repository 주입 | `FooService` 를 주입하고 조회를 Service 로 옮긴다 |

- 위반이 없으면 표 대신 "위반 없음"이라고 적는다.
- "확인 필요" 항목은 표 아래에 `- C? 파일:줄번호(알 수 있으면) — 판정하지 못한 이유` 형식으로 따로 적는다.
