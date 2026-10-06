# 외주사 PR 판정 기록

기준 문서: `templates/approval-checklist.md` (v0), `CLAUDE.md`. 검증은 코드를 수정하지 않고 판정만 남긴다.

| PR | 판정 | 미충족 기준 | 근거(파일:줄번호) | 외주사에 돌려보낼 수정 요청 |
|---|---|---|---|---|
| pr-1 (`a7f70f8`, 단원별 문항 수 통계 API `GET /api/units/item-counts`) | 반려 | 테스트 통과(체크리스트 §1 "테스트가 충분하다"), 컨벤션 준수(`CLAUDE.md` §2 테스트) | `modern/api/src/main/java/com/example/item/UnitService.java:36` (새 public 메서드 `countActiveItemsByUnit` 에 테스트 없음)<br>`modern/api/src/main/java/com/example/item/UnitController.java:26-28` (새 엔드포인트 `itemCounts` 에 테스트 없음)<br>`modern/api/src/test/java/com/example/item/UnitServiceTest.java:15-45` (변경 없음, `listUnits` 테스트 2건뿐. `@Mock` 에 `ItemRepository` 도 없어 새 생성자 인자가 null 로 주입됨)<br>diff 에 `src/test` 파일 0개. `./gradlew test` 는 54건 · 실패 0 · 오류 0 이지만 모두 이 PR 이전 테스트 | 1) `UnitServiceTest` 에 `@Mock ItemRepository` 를 추가하고 `countActiveItemsByUnit` 테스트를 넣어 주세요: 공개(`ItemStatus.ACTIVE`) 문항만 세는지, 문항 없는 단원이 0 으로 포함되는지, 학년 · 코드 순서가 유지되는지.<br>2) `GET /api/units/item-counts` 의 `@WebMvcTest` + `@MockBean UnitService` 테스트를 추가해 주세요(상태 200, JSON 필드 `code` · `name` · `grade` · `activeItemCount`). 메서드 이름은 camelCase, `@DisplayName` 은 한국어, 클래스에 `@ActiveProfiles("test")`.<br>3) (제안, 반려 사유 아님) `UnitService.java:37-40` 은 단원마다 count 쿼리를 날리는 N+1 입니다. `group by` 집계 쿼리 1회로 바꿀지 검토해 주세요. 바꾸면 `@DataJpaTest` 도 필요합니다 |

## pr-1 상세

- 요청 범위: 단원별 문항 수 통계 API 추가. 변경 파일 3개(`UnitController.java`, `UnitItemCountResponse.java`, `UnitService.java`, 모두 `modern/api` 의 `com.example.item`)는 모두 요청으로 설명된다.
- 테스트 통과: 충족하지 못함. 실행 결과는 통과(`cd modern/api && ./gradlew test` → 54건, 실패 0, 오류 0)지만 새 코드를 검증하는 테스트가 없다.
- 치명 이슈 0건: 충족. 비밀값 · 문자열 SQL · 쓰기 코드 없음(`@Transactional(readOnly = true)`, 파생 쿼리 사용).
- 요청 범위 이탈 없음: 충족. 의존성 · 스키마 · 설정 변경 없음.
- 컨벤션 준수: 테스트 항목만 미충족. 계층(Controller → Service → Repository), 같은 도메인 패키지의 `ItemRepository` 주입, record DTO 반환, 로깅(`log.debug`), 이름 · 형식은 위반 없음.
- 확인 필요: `characterization/` 은 실행하지 않았다. 이 변경은 레거시 이관이 아니라 신규 API 이고, 8080 서버 · MariaDB 기동 상태를 확인하지 못했다.
