# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이다. 사용자의 허락 없이 그 아래 파일을 수정하지 않는다.

## 1. 빌드 · 테스트 명령

```bash
# modern/api — 테스트는 H2(MariaDB 모드) 인메모리로 돈다. DB 컨테이너 없이 통과해야 한다
cd modern/api && ./gradlew test
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest'              # 클래스 하나
cd modern/api && ./gradlew test --tests 'com.example.item.ItemServiceTest.<메서드명>'    # 메서드 하나
cd modern/api && ./gradlew build                  # 테스트 포함 전체 빌드
docker compose --profile modern up -d             # bootRun 전에 MariaDB(3306)를 띄운다 (저장소 루트에서)
cd modern/api && ./gradlew bootRun                # http://localhost:8080

# modern/web
cd modern/web && npm ci                           # npm install 대신 npm ci (lock 파일을 바꾸지 않는다)
cd modern/web && npm run lint && npm run typecheck && npm test
cd modern/web && npx vitest run src/components/ItemTable.test.tsx                         # 파일 하나
cd modern/web && npm run build
cd modern/web && npm run dev                      # http://localhost:5173, API 는 8080
```

- `modern/api` 를 바꿨으면 `./gradlew test` 를, `modern/web` 을 바꿨으면 `npm run lint && npm run typecheck && npm test` 를 실행하고, 답변에 통과 · 실패 수를 적는다. 실행하지 않았으면 "실행하지 않음"이라고 적는다.

## 2. 코딩 컨벤션

### modern/api — 계층
- 호출 방향은 Controller → Service → Repository → 엔티티다. 역방향 호출은 없다.
- Controller 클래스의 필드 · 생성자 인자에 `*Repository`, `EntityManager`, `JdbcTemplate` 타입이 없다.
- Controller 메서드 본문은 Service 메서드 호출과 그 결과 반환만 한다. SQL 문자열 · 조건 분기에 따른 비즈니스 계산이 없다.
- Service 가 다른 도메인 패키지의 데이터를 쓸 때는 그 도메인의 Service 를 주입한다. 다른 도메인 패키지의 `*Repository` 를 주입하지 않는다.
- Controller 메서드의 반환 타입은 record DTO(`*Response`)이며 `@Entity` 클래스가 아니다.
- 새 조회 API 는 Controller · Service · Repository 와 그 테스트를 같은 도메인 패키지(`com.example.<도메인>`)에 둔다. 경로는 `/api/<도메인 복수형>` 아래다.

### modern/api — 예외
- 모든 `catch` 블록은 로그 호출(`log.warn`/`log.error`) 또는 `throw` 를 하나 이상 포함한다.
- Controller 메서드 안에 `try`/`catch` 가 없다. 예외 → HTTP 상태 변환은 `common/GlobalExceptionHandler` 에서만 한다.
- 매핑: `NotFoundException` · 없는 경로 → 404, 검증 실패 → 400, `IllegalStateException` → 409, 그 밖의 예외 → 500. 새 매핑은 `GlobalExceptionHandler` 에 추가한다.

### modern/api — 로깅
- 로그는 `org.slf4j.Logger` 로만 남긴다. `System.out.println`, `System.err.println`, `e.printStackTrace()` 가 없다.
- 로그 메시지 인자에 학생 식별자(`STU-…`) · 이메일 · 토큰 값을 넣지 않는다.
- 로그 레벨: 정상 흐름은 `INFO` 이하, 요청을 실패시키지 않는 실패는 `WARN`, 요청을 실패시키는 예외는 `ERROR`.

### modern/api — 시각
- Service 계층에서 현재 시각은 주입받은 `java.time.Clock` 으로 얻는다(`LocalDateTime.now(clock)`). 인자 없는 `now()` 를 쓰지 않는다.

### modern/api — 테스트
- 새 public Service 메서드와 새 엔드포인트마다 테스트가 1개 이상 있다. 동작을 바꾼 변경에는 그 동작을 확인하는 테스트의 추가 · 수정이 함께 있다.
- Controller 는 `@WebMvcTest` + `@MockBean` Service, Service 는 Mockito 단위 테스트, Repository 쿼리는 `@DataJpaTest` 로 테스트한다.
- 모든 테스트 클래스에 `@ActiveProfiles("test")` 가 붙어 있다.
- 테스트 메서드 이름은 camelCase 로 확인하는 동작을 적고(예: `rejectsOtherOrigins`), `@DisplayName` 에 한국어 설명을 붙인다.

### modern/api — 이름 · 형식
- 클래스 `PascalCase`, 메서드 · 필드 `camelCase`, 상수 `UPPER_SNAKE_CASE`. 약어도 첫 글자만 대문자(`ItemDto`, `ItemDTO` 아님).
- 0, 1, -1 이외의 숫자 리터럴은 `static final` 상수로 둔다(예: `MAX_LEVEL = 5`).
- 들여쓰기 공백 4칸, 한 줄 120자 이하, 와일드카드 import(`.*`) 없음.

### modern/web
- HTTP 요청은 `src/api/client.ts` 의 `getJson` 으로만 한다. `client.ts` 밖의 소스(테스트 제외)에 `fetch(` 호출이 없다.
- 조회 훅은 `src/hooks/useApiQuery.ts` 의 `useApiQuery` 를 사용해 만든다.
- 테스트의 fetch 목은 `src/test/mockFetch.ts`, 데이터는 `src/test/fixtures.ts` 를 쓴다.

## 3. 금지 사항

- 기존 기능을 수정할 때 요청 범위와 직접 관련 없는 파일 · 메서드 · 줄은 변경하지 않는다. 포맷팅 · import 정리도 요청 범위 안의 줄에서만 한다. 범위 밖을 고쳤다면 답변에 파일명과 이유를 적는다.
- 아래 변경이 필요하면 코드를 고치기 전에 멈추고 사용자에게 이유와 대안을 적어 확인받는다.
  - 새 외부 의존성: `modern/api/build.gradle` 의 `dependencies`, `modern/web/package.json` 의 `dependencies` · `devDependencies` 추가 · 버전 변경
  - DB 스키마: 테이블 · 컬럼 · 인덱스 추가 · 삭제 · 변경, 마이그레이션 파일 추가, `db/**/init/*.sql` 수정(시드 데이터 포함)
- `modern/api/src/main/resources/application.yml` 의 `spring.datasource`(접속 정보 · `hikari` 풀 설정)를 바꾸지 않는다.
- 실제 비밀값(운영 계정 · 토큰 · 키)을 코드 · 설정 파일에 리터럴로 넣지 않는다. 실습용 더미 `app-pass` 만 예외다.
- 운영 DB 호스트(`prod-db` 등)에 접속하는 명령 · 설정을 만들지 않는다.
- `@Transactional` 메서드 안에서 외부 HTTP 호출을 하지 않고, DB 조회가 끝난 뒤의 계산(서명 · 해시 등)을 하지 않는다. 그런 계산은 트랜잭션 밖 메서드로 분리한다.
- 테스트가 MariaDB 에 접속하게 만들지 않는다. 테스트 설정은 `src/test/resources/application-test.yml`(H2) 만 쓴다.
- `characterization/` 의 테스트 · 스냅샷(`__snapshots__/`)을 깨뜨리는 변경은 먼저 사용자에게 알린다. 스냅샷 파일을 손으로 고치지 않는다.

## 4. 아키텍처 안내

도메인 용어: 문항 `item` · 단원 `unit`(예: `M5-1`) · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

```
modern/api/src/main/java/com/example/
├── item/          문항 · 단원 · 태그 (Item*, Unit*, Tag*)
├── assignment/    과제 배포 · 재배포 · 학급 리포트 (Distribution*, Report*)
├── common/        GlobalExceptionHandler, ErrorResponse, NotFoundException, ClockConfig, RootController
└── config/        WebConfig (CORS)
modern/web/src/    api/(client · 타입) → hooks/(useApiQuery 기반) → components/
```

- 엔드포인트: `GET /`, `GET /api/units`, `GET /api/units/item-counts`, `GET /api/units/{code}/items`, `GET /api/items/{id}`, `GET /api/distributions/{id}`, `POST /api/distributions/{id}/redistribute`, `GET /api/classes/{id}/report`.
- 공개 문항만 노출한다: `ItemStatus`, `status = 'A'`. 삭제 플래그가 아니라 상태 코드로 판단한다.
- CORS: `config/WebConfig` 가 `/api/**` 에 `http://localhost:5173` 출처의 GET 만 허용한다.
- 커넥션 풀은 운영과 같게 작다(`maximum-pool-size: 5`, `connection-timeout: 3000`). 트랜잭션을 오래 쥐면 곧바로 풀이 고갈된다(`incident-logs/a-connection-pool`). `ReportService.buildClassReport` 는 트랜잭션 안에서 서명 계산을 하는 알려진 위반 사례이며(TODO 주석), 요청 없이 고치지 않는다.
- DB: `php` · `thymeleaf` · `modern` Compose 프로필이 MariaDB(`itembank`, 3306) 하나를 공유한다. 스키마 · 시드는 `db/mariadb/init/*.sql`, 고정 ID · 고정 시각이다. 조회 전용 계정은 `readonly/readonly-pass`.
- 테스트 프로필(`application-test.yml`)은 H2 MariaDB 모드 + `ddl-auto: create-drop`. 기본 프로필은 `ddl-auto: none`.
- modern/web: `API_BASE` 기본값은 `http://localhost:8080`. `VITE_API_BASE=""` 이면 상대 경로가 되어 Vite 프록시(`/api` → 8080)를 탄다.
- 레거시 이관 대상: `legacy/item-bank-php/`(8081) · `legacy/assignment-thymeleaf/`(8082) · `legacy/grade-mssql/`(8083, 프로시저 `sql/*.sql`). 레거시 규칙을 옮길 때는 근거를 답변에 `파일:줄번호` 로 적는다(예: `legacy/item-bank-php/search.php:214`).
- 이관 전후 동작 비교는 `characterization/` 이 맡는다. 사용법 · 규칙은 `characterization/README.md`.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 그 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 사용자에게 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
