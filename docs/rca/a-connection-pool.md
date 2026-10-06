# 장애 원인 분석: a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (2026-09-16 하루치)

## 요약

- **무슨 일**: 2026-09-16 **14:37:42 ~ 14:51:52 KST**(약 14분) 동안 DB 커넥션 풀(5개)이 바닥났다. 사용자에게는 14:40:52 부터 5xx 186건(500 49 · 502 82 · 504 55)으로 보였다.
- **원인**: 학급 리포트 API(`ReportService.buildClassReport`)가 트랜잭션 안에서 DB 조회 뒤 서명 계산(SHA-256 × 200,000)까지 커넥션을 쥐었다. 리포트 요청이 늘자 커넥션 5개가 모두 리포트에 묶였고, 다른 API 가 3초 대기 후 실패했다.
- **확신 수준: 중간** — 로그 · 코드 · 설정이 같은 사슬을 가리킨다. 다만 14:37 부터 리포트 1건의 점유 시간이 10초 미만에서 15초 이상으로 늘어난 이유는 확인하지 못했다(9절).
- **조치**: 서명 계산을 트랜잭션 밖으로 분리한다(6절). 리포트 동시 실행 제한과 테스트 · 리뷰 규칙으로 재발을 막는다(7절). 풀 대기 · leak 지표에 경보를 건다(8절).

## 1. 수집 범위

> 이 절은 분석할 시간 범위만 정한다. 원인은 다루지 않는다.
> 파일을 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n`(분 단위 집계는 `grep -o … | cut | uniq -c`)으로만 확인했다.

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 기록 기간 (원본 표기) | 기록 기간 (KST) | 시각 형식 | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 5,371 (시각으로 시작하는 줄 1,927, 나머지 3,444 는 스택트레이스 연속 줄) | `2026-09-16T06:43:56.753+09:00` ~ `2026-09-16T15:29:51.760+09:00` | 06:43:56 ~ 15:29:51 | ISO-8601, 밀리초, 오프셋 포함 | `+09:00` (KST, 1,927줄 전부) |
| `nginx-access.log` | 21,086 | `[16/Sep/2026:06:43:56 +0900]` ~ `[16/Sep/2026:15:29:59 +0900]` | 06:43:56 ~ 15:29:59 | nginx combined (`dd/Mon/yyyy:HH:mm:ss +zzzz`) | `+0900` (KST) |
| `nginx-error.log` | 168 | `2026/09/16 09:12:03` ~ `2026/09/16 14:51:47` | 09:12:03 ~ 14:51:47 | `yyyy/MM/dd HH:mm:ss`, 오프셋 없음 | 표기 없음 → **KST 로 판단** (아래 근거) |
| `mariadb-slow.log` | 21 (항목 3건) | `# Time: 260916  0:14:09` ~ `# Time: 260916  5:37:52` | 09:14:09 ~ 14:37:52 | `# Time: yyMMdd H:mm:ss` + `SET timestamp=<epoch>` | **UTC** (아래 근거) |

**타임존 판단 근거와 KST 변환 규칙**

- `app.log`, `nginx-access.log`: 오프셋이 찍혀 있다. 변환 불필요.
- `nginx-error.log`: 오프셋이 없다. `upstream timed out` 건수를 분 단위로 세면 `nginx-access.log` 의 504 건수와 14:41 ~ 14:51 의 모든 분에서 일치한다(3·5·5·6·5·6·3·5·8·7·2, 합계 55 = 55). 따라서 KST 로 기록됐다고 보고 **그대로 KST 로 읽는다**.
- `mariadb-slow.log`: `SET timestamp` 의 epoch 값을 변환하면 UTC 와 `# Time` 이 맞는다.
  - `1789517647` → UTC 00:14:07 / KST 09:14:07 (`# Time: 0:14:09`, Query_time 2.3초)
  - `1789524160` → UTC 02:02:40 / KST 11:02:40 (`# Time: 2:02:42`, Query_time 2.0초)
  - `1789537062` → UTC 05:37:42 / KST 14:37:42 (`# Time: 5:37:52`, Query_time 9.8초)
  - 규칙: **KST = `# Time` + 9시간** (날짜가 넘어가면 하루 더함). `# Time` 은 쿼리 종료 시각, `SET timestamp` 는 시작 시각이므로 둘의 차이가 대략 Query_time 이다. 시작 시각이 필요하면 `SET timestamp` 를 쓴다.

### 1.2 분 단위 이상 건수

세는 기준:

| 파일 | 센 줄 |
|---|---|
| `app.log` | 시각으로 시작하는 줄 중 레벨 `WARN` · `ERROR` (스택트레이스 연속 줄 제외). 그 안에서 `Connection leak detection`(WARN), `Connection is not available, request timed out`(ERROR) 을 따로 셈 |
| `nginx-access.log` | 상태 코드 5xx (500 · 502 · 504) |
| `nginx-error.log` | 레벨 `[error]` · `[warn]` (`[info]` 3건 제외). 그 안에서 `upstream timed out` 을 따로 셈 |
| `mariadb-slow.log` | 항목 수 (하루 3건뿐이라 분 단위 집계 의미 없음) |

**평소 수준 (06:43 ~ 14:36)**

- `app.log` WARN/ERROR: 하루 동안 16건, 분당 최대 1건. 전부 `GlobalExceptionHandler : state conflict`(409).
- `nginx-access.log` 5xx: 0건. (같은 시간 409 는 산발적으로 분당 0 ~ 1건)
- `nginx-error.log` error/warn: 2건(09:12, 13:05 `an upstream response is buffered to a temporary file`).
- 요청량: 14:20 ~ 15:05 동안 분당 32 ~ 58건. 이상 구간에도 이 범위를 벗어나지 않는다.

**이상 구간 분 단위 건수 (KST)**

| 분 | app WARN+ERROR | └ leak 감지 | └ 풀 대기 타임아웃 | access 500 | access 502 | access 504 | access 5xx 계 | nginx-error error+warn | └ upstream timed out | 전체 요청 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 14:33 | 1 | | | | | | 0 | | | 52 |
| 14:37 | **1** | **1** | | | | | 0 | | | 50 |
| 14:38 | 2 | 2 | | | | | 0 | | | 41 |
| 14:39 | 3 | 3 | | | | | 0 | | | 42 |
| 14:40 | 17 | 14 | 1 | 1 | | | **1** | | | 42 |
| 14:41 | 31 | 13 | 6 | 6 | | 3 | 9 | **3** | 3 | 42 |
| 14:42 | 17 | 10 | 2 | 2 | 13 | 5 | 20 | 20 | 5 | 41 |
| 14:43 | 44 | 14 | 10 | 10 | 2 | 5 | 17 | 8 | 5 | 58 |
| 14:44 | 17 | 11 | 2 | 2 | 5 | 6 | 13 | 15 | 6 | 34 |
| 14:45 | 33 | 9 | 8 | 8 | 14 | 5 | 27 | 22 | 5 | 39 |
| 14:46 | 24 | 12 | 4 | 4 | 16 | 6 | 26 | 25 | 6 | 51 |
| 14:47 | 24 | 9 | 5 | 5 | 6 | 3 | 14 | 10 | 3 | 38 |
| 14:48 | 22 | 10 | 4 | 4 | 6 | 5 | 15 | 15 | 5 | 42 |
| 14:49 | 33 | 12 | 7 | 7 | 8 | 8 | 23 | 20 | 8 | 42 |
| 14:50 | 12 | 11 | | | 11 | 7 | 18 | 22 | 7 | 34 |
| 14:51 | 4 | **3** | | | 1 | 2 | **3** | **3** | 2 | 46 |
| 14:52 | 1 | | | | | | 0 | | | 47 |
| 합계(14:37~14:51) | 284 | 134 | 49 | 49 | 82 | 55 | 186 | 163 | 55 | |

- 빈칸은 0건. 14:33 · 14:52 의 app 1건은 평소 수준인 `state conflict`(409) 다.
- `app.log` 의 ERROR 98건은 풀 대기 타임아웃 49건과 그에 이어지는 `unhandled exception on …` 49건이다.
- `nginx-access.log` 의 5xx 186건은 **전부** 14:40 ~ 14:51 에 있다. 하루 중 다른 시각에는 5xx 가 없다.
- `nginx-error.log` 의 `[error]` 137건은 전부 14:41:06 ~ 14:51:47 에 있다.
- 14:52 ~ 15:29 의 `app.log` 는 INFO 102건 · WARN 1건(409)으로 평소 수준이다.
- `mariadb-slow.log` 의 3건 중 3번째(KST 14:37:42 시작, 9.8초)만 이상 구간 근처에 있다.

**이상 구간 제안**

| 구분 | 시각 (KST) | 근거 |
|---|---|---|
| 시작 | **14:37:52** (`app.log:1243`, 첫 `Connection leak detection`) | 그 전까지 WARN 은 409 뿐. leak 감지 임계값이 10초(`modern/api/src/main/resources/application.yml:18`, `leak-detection-threshold: 10000`)이므로 해당 커넥션은 **14:37:42 이전**에 빌려졌다. 사용자에게 보이는 첫 실패(5xx)는 14:40:52(`app.log:1522`) |
| 끝 | **14:51:52** 경 | 마지막 nginx `[error]` 14:51:47(`nginx-error.log:168`), 마지막 leak 감지 14:51:37(`app.log:5251`). 14:52 부터 3개 로그 모두 평소 수준 |

→ 이상 구간: **2026-09-16 14:37 ~ 14:52 KST** (UTC 05:37 ~ 05:52)

### 1.3 분석 범위 (여유 포함)

| 구분 | 여유 | 시각 (KST) | 시각 (UTC, slow log 대조용) |
|---|---|---|---|
| 앞쪽 | **30분** | 14:07 | 05:07 |
| 이상 구간 | — | 14:37 ~ 14:52 | 05:37 ~ 05:52 |
| 뒤쪽 | **10분** | 15:02 | 06:02 |

→ 분석 범위: **2026-09-16 14:07 ~ 15:02 KST**

앞쪽을 더 넉넉히 두는 이유:

- 첫 leak 감지 시각은 커넥션을 빌린 시각보다 최소 10초 늦다. 실제 시작은 로그에 보이는 첫 줄보다 앞이다.
- 이상 직전에 바뀐 요청 패턴 · 특정 엔드포인트 호출 · 느린 쿼리 같은 선행 신호가 있었는지 보려면 평소 구간을 비교 기준으로 충분히 확보해야 한다. 30분이면 409 같은 평소 WARN 과 분당 요청량(32 ~ 58건)의 기준선을 잡을 수 있다.
- `mariadb-slow.log` 의 KST 14:37:42 항목이 이 앞쪽 경계 근처에 있으므로 시작 경계를 좁게 잡으면 빠질 수 있다.

뒤쪽 10분은 14:52 이후 정상으로 돌아온 상태가 유지되는지(재발 · 잔여 오류 없음) 확인하는 용도다. 로그가 15:29 까지 있으므로 필요하면 늘릴 수 있다.

예외: `mariadb-slow.log` 는 21줄(3건)뿐이므로 범위로 자르지 않고 전체를 참고한다. 단, 다른 로그와 맞출 때는 반드시 +9시간 변환 후 비교한다.

## 2. 타임라인

> 대상: 1.3 의 분석 범위 **2026-09-16 14:07 ~ 15:02 KST**. 모든 시각은 KST 다(`mariadb-slow.log` 는 +9시간 변환).
> 로그에 있는 사건만 적었고 원인 해석은 하지 않는다. 학생 식별자는 등장 순서대로 `학생A`, `학생B` … 별칭으로 바꿨다.
> 시각 정밀도: `app.log` 는 밀리초, `nginx-*` · `mariadb-slow.log` 는 초 단위다. 파일이 다르고 차이가 2초 이내인 사건은 **(선후 불확실)** 로 표시했다.
> 반복 메시지는 "첫 발생 시각 · 이후 N건/분" 으로 묶었다. 분당 건수는 `14:40(14) 14:41(13)` 처럼 `분(건수)` 로 적는다.

출처 약어: `app` = `app.log`, `acc` = `nginx-access.log`, `err` = `nginx-error.log`, `slow` = `mariadb-slow.log`

### 2.1 이상 구간 앞 (14:07 ~ 14:37:51) — 평소 수준 사건

이 구간에서 `app` 의 WARN · ERROR, `acc` 의 5xx, `err` 의 error · warn 은 아래의 409 외에 없다. 요청량은 14:20 ~ 14:37 에 분당 32 ~ 56건이다(14:07 ~ 14:19 는 분 단위로 세지 않음). 설정 재적재 · 재시작 · 시그널 흔적은 이 구간을 포함해 하루 전체에서 찾지 못했다(`reload|refresh|signal|configur|HikariPool-|Started|deploy` 검색 결과 0건).

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07:12.267 | app:1135 | 분석 범위 내 `app` 첫 줄 (INFO) | `2026-09-16T14:07:12.267+09:00  INFO …` |
| 14:09:10.102 | app:1143 | 400 bad request (범위 안 4건: 14:09, 14:31, 14:37:15, 14:44) | `bad request: Method parameter 'id': Failed to convert value of type 'java.lang.Str` |
| 14:10:05 ~ 14:18:13 | app:1148 · 1161 · 1167 · 1174 · 1175 / acc | 학급 리포트 조회 5건 (분당 0 ~ 1건) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | acc:17605 · app:1154 | 재배포 요청 409 (distributionId=3). 14:14:03(acc:17722 · app:1165), 14:33:45(acc:18610 · app:1229, distributionId=2)에도 같은 409 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` |
| 14:19:38 | acc:17977 · app:1182 | 재배포 성공 (distribution 7, class 3) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석으로 재배포` |
| 14:36:57 | acc:18750 | 교사 화면에서 distribution 5 조회, 200 | `"GET /api/distributions/5 HTTP/1.1" 200 189 "https://lms.example.com/teacher/das` |

### 2.2 이상 구간 (14:37:42 ~ 14:51:52)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| **14:37:42** (시작) ~ 14:37:52 (종료) | slow:15 · 18 · 21 | **느린 쿼리** — submission 조회 9.8초, 1,284,310행 검사. 이날 slow log 3건 중 가장 길고, 분석 범위 안에서 유일하다 (다른 2건은 09:14 2.3초, 11:02 2.0초) | `# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310` / `… from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id;` |
| 14:37:52 | acc:18796 | 학생 앱에서 distribution 5 조회, 200 **(선후 불확실: slow 종료 · 아래 app 첫 leak 과 같은 초)** | `"GET /api/distributions/5?student=학생B HTTP/1.1" 200 198` |
| **14:37:52.199** | app:1243 | **첫 커넥션 leak 감지** (WARN). 커넥션 `@6e8f14d3`, 스레드 exec-6, 스택에 `ReportController.classReport` → `ReportService.buildClassReport` **(선후 불확실: 위 두 줄과 같은 초)**. 이후 14:37(1) 14:38(2) 14:39(3) 14:40(14) 14:41(13) 14:42(10) 14:43(14) 14:44(11) 14:45(9) 14:46(12) 14:47(9) 14:48(10) 14:49(12) 14:50(11) 14:51(3), 합계 134건. 134건 모두 스택의 Controller 프레임이 `ReportController.classReport`. 커넥션 5개(`@6e8f14d3` 29 · `@2ac19f07` 29 · `@3f6c2a91` 26 · `@5b0e9e0c` 26 · `@71d4b8e2` 24)가 번갈아 나온다 | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3 on thr` |
| **14:38:01** | acc:18801 | **트래픽 변화 시작** — 학급 리포트 요청이 늘어난다. `/report` 분당 요청: 14:38(3) 14:39(3) 14:40(10) 14:41(16) 14:42(15) 14:43(19) 14:44(14) 14:45(15) 14:46(19) 14:47(12) 14:48(14) 14:49(16) 14:50(10) 14:51(5). 직전 14:07 ~ 14:37 에는 5건(분당 0 ~ 1건). 범위 안 요청은 교사 IP 4개(교사단말A · 교사단말B · 교사단말C · 교사단말D)에서 나왔다. 전체 요청량은 분당 32 ~ 58건으로 변화가 없다 | `교사단말B - - [16/Sep/2026:14:38:01 +0900] "GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01.900 | app:1258 | 리포트 생성 완료 (class 3). 이후 분당 건수: 14:38(3) 14:39(3) 14:40(10) 14:41(15) 14:42(10) 14:43(15) 14:44(11) 14:45(8) 14:46(13) 14:47(9) 14:48(10) 14:49(13) 14:50(9) 14:51(5) **(선후 불확실: 위 acc:18801 과 같은 초)** | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:38:01.902 | app:1259 | leak 감지된 커넥션 반환 (INFO, `@6e8f14d3`, exec-6). 이후 반환 줄이 leak 감지와 같은 수(134건)로 이어진다. 분당 건수는 위 "리포트 생성 완료"와 같다 | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 on thread` |
| 14:38:29 / 14:38:29.715 | acc:18817 · app:1278 | 재배포 성공 (distribution 5, class 3, reason=null) | `"POST /api/distributions/5/redistribute HTTP/1.1" 200 170` / `redistributed distribution 5 (assignment 3, class 3) reason=null` |
| **14:40:52.319** | app:1521 · 1522 · 1523 | **첫 커넥션 풀 대기 타임아웃** (WARN `SQL Error` → ERROR → ERROR `unhandled exception`). 이후 14:40(1) 14:41(6) 14:42(2) 14:43(10) 14:44(2) 14:45(8) 14:46(4) 14:47(5) 14:48(4) 14:49(7), 합계 49건. 메시지의 대기 수: waiting=1 27건 · 2 36건 · 3 45건 · 4 21건 · 5 9건 · 6 6건 · 7 3건 (스택트레이스 연속 줄까지 포함해 센 값이라 합계 147 은 49건과 다르다. 비율만 참고) | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` |
| 14:40:52 | acc:18918 | **첫 500** (`/api/units/M6-2/items`). 분당 500 건수는 위 풀 타임아웃과 모든 분에서 같다(합계 49). 500 경로: units/{code}/items 19 · units 14 · items/{id} 7 · classes/{id}/report 5 · distributions/{id} 4 **(선후 불확실: app:1522 와 같은 초)** | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| **14:41:06** | err:6 · acc:18933 | **첫 upstream 타임아웃 / 첫 504** (`/api/classes/3/report`). 이후 14:41(3) 14:42(5) 14:43(5) 14:44(6) 14:45(5) 14:46(6) 14:47(3) 14:48(5) 14:49(8) 14:50(7) 14:51(2), 합계 55건. `err` 와 `acc` 의 분당 건수가 같고, 504 는 55건 전부 `/api/classes/{id}/report` **(선후 불확실: err · acc 같은 초)** | `upstream timed out (110: Connection timed out) while reading response header from upstream` |
| **14:42:01** | err:10 · err:11 · acc:18970 | **upstream 일시 제외 · 연결 거부 · 첫 502** (`/api/classes/2/report`). 같은 초에 warn `temporarily disabled`, error `connect() failed (111: Connection refused)`, 502 가 함께 나온다 **(선후 불확실)**. `temporarily disabled` 이후: 14:42(2) 14:43(1) 14:44(4) 14:45(3) 14:46(3) 14:47(1) 14:48(4) 14:49(4) 14:50(4), 합계 26건. `connect() failed` 이후: 14:42(2) 14:43(1) 14:44(1) 14:45(2) 14:46(2) 14:47(1) 14:48(1) 14:49(2) 14:50(2), 합계 14건 | `upstream server temporarily disabled while reading response header from upstream` / `connect() failed (111: Connection refused) while connecting to upstream` |
| **14:42:04** | err:12 | **첫 `no live upstreams`** (`/api/units/M6-2/items`). 이후 14:42(11) 14:43(1) 14:44(4) 14:45(12) 14:46(14) 14:47(5) 14:48(5) 14:49(6) 14:50(9) 14:51(1), 합계 68건. 502 는 분당 14:42(13) 14:43(2) 14:44(5) 14:45(14) 14:46(16) 14:47(6) 14:48(6) 14:49(8) 14:50(11) 14:51(1), 합계 82건. 502 경로: classes/{id}/report 32 · items/{id} 13 · units 12 · units/{code}/items 12 · distributions/{id} 9 · 기타 4 | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:42:42 · 14:42:42.681 | acc:18998 · app:2174 | 재배포 409 (distributionId=1). 평소와 같은 형태 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=1` |
| 14:44:19 · 14:44:19.827 | acc:19078 · app:2929 | 재배포 성공 (distribution 8, class 2) | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:49:51.552 | app:4980 · 4981 / acc:19305 | **마지막 풀 대기 타임아웃 · 마지막 500** (`/api/units/M5-3/items`) **(선후 불확실: app · acc 같은 초)** | `"GET /api/units/M5-3/items HTTP/1.1" 500 162` |
| 14:50:22 | acc:19321 · app:5107 | 재배포 409 (distributionId=3) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` |
| 14:50:52 | err:158 · err:159 | 마지막 `temporarily disabled` · 마지막 `connect() failed` | `connect() failed (111: Connection refused) while connecting to upstream` |
| 14:51:02 | err:166 · acc:19347 | 마지막 `no live upstreams` · 마지막 502 **(선후 불확실: 같은 초)** | `no live upstreams while connecting to upstream, client: 10.20.x.x` |
| 14:51:37.674 | app:5251 | 마지막 leak 감지 (`@6e8f14d3`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| **14:51:47** | err:168 · acc:19381 | **마지막 upstream 타임아웃 · 마지막 504 · 마지막 5xx** (`/api/classes/3/report`) **(선후 불확실: 같은 초)** | `upstream timed out (110: Connection timed out) while reading response header from upstream` |
| 14:51:49.148 | app:5267 | 마지막 leak 커넥션 반환 (`@6e8f14d3`, exec-10) | `Previously reported leaked connection org.mariadb.jdbc.Connection@6e8f14d3 on thread` |
| 14:51:52 · 14:51:52.531 | acc:19386 · app:5268 | 재배포 409 (distributionId=2). 이후 `app` 의 WARN 은 409 뿐 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=2` |

### 2.3 이상 구간 뒤 (14:52 ~ 15:02)

| 시각 | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:52:09 · 14:52:09.185 | acc:19401 · app:5269 | 재배포 409 (distributionId=4). 범위 안 `app` 의 마지막 WARN | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=4` |
| 14:52 ~ 15:02 | acc | 5xx 0건, 분당 요청 35 ~ 47건 | — |
| 14:52 ~ 15:02 | err | 기록 없음 (`nginx-error.log` 의 마지막 줄이 14:51:47) | — |
| 14:59:15 · 14:59:52 · 15:01:20 | app:5283 · 5284 · 5287 | 학급 리포트 생성 3건 (분당 0 ~ 2건). leak 감지 없음 | `built class report for class 3: 3 distributions, 10 submissions` |
| 15:02:51.222 | app:5288 | 분석 범위 내 `app` 마지막 줄 (INFO) | `2026-09-16T15:02:51.222+09:00  INFO …` |

## 3. 가설과 검증

> 이 절은 가설과 그 검증 방법만 적는다. **어느 가설이 맞는지는 아직 판정하지 않는다.**
> 지지 근거의 줄번호는 이 문서(`docs/rca/a-connection-pool.md`)의 타임라인 줄이다. 필요하면 원본 로그 줄(`app:…` 등, 2절의 출처 약어)을 함께 적었다.
> 아래 명령의 `L` 은 `incident-logs/a-connection-pool` 이다(`L=incident-logs/a-connection-pool`).

### 3.0 증상으로 분류한 것 (원인 후보가 아님)

아래는 가장 많이 나온 메시지들이지만 "무엇이 일어났는가"를 보여 줄 뿐 "왜"를 설명하지 않는다. 가설은 이 증상들이 **왜** 생겼는지를 설명해야 한다.

| 증상 | 건수 | 타임라인 |
|---|---:|---|
| `Connection is not available, request timed out after 3000ms` (풀 대기 타임아웃) → 500 | 49 | a-connection-pool.md:146 · 147 |
| `Connection leak detection triggered` (커넥션 10초 이상 점유) | 134 | a-connection-pool.md:141 |
| `upstream timed out` → 504 | 55 | a-connection-pool.md:148 |
| `no live upstreams` · `connect() failed` → 502 | 82 | a-connection-pool.md:149 · 150 |

### 3.1 가설 H1 — 애플리케이션 코드: 리포트 트랜잭션이 DB 작업 뒤 서명 계산 동안 커넥션을 쥔다

| 항목 | 내용 | 로그 실행 결과 · 판정 (명령 · 원 출력은 3.6) |
|---|---|---|
| 가설 | `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회를 끝낸 뒤 SHA-256 20만 회 서명(`sign`)까지 커넥션을 쥐고 있어, 리포트 요청이 몇 건만 겹쳐도 풀 5개가 모두 리포트에 묶이고 다른 API 가 커넥션을 못 받는다. | **유지** — 풀 대기 타임아웃 49건 **모두**, 그 시각에 리포트 커넥션 5개(= 풀 전체)가 점유 중이었다. 반증 조건 ① 이 성립하지 않았다. |
| 지지 근거 | a-connection-pool.md:141 — leak 감지 134건 **전부** 스택의 Controller 프레임이 `ReportController.classReport`, 커넥션 5개(= `maximum-pool-size`)가 번갈아 나온다. 원본 스택에서 커넥션 획득 지점이 `JpaTransactionManager.doBegin`(app:1243 아래 연속 줄)이라 점유 시간은 트랜잭션 전체다.<br>a-connection-pool.md:143 · 144 — leak 커넥션 반환 시각이 "리포트 생성 완료" 로그 직후(14:38:01.900 → .902)이고 분당 건수가 같다. 커넥션이 리포트 처리 끝까지 반환되지 않았다.<br>a-connection-pool.md:147 — 500 은 리포트가 아닌 경로(units · items · distributions)가 대부분(49건 중 44건).<br>a-connection-pool.md:130 · 169 — 리포트 요청이 분당 0 ~ 2건일 때는 leak 감지가 없다. | 재확인(3.6 R1): leak 감지 134건 · 반환 134건이 (커넥션, 스레드) 단위로 전부 짝지어졌다(짝 없음 0). 점유 시간(감지 − 10초 → 반환) 최소 15.5초 · 중앙 18.9초 · 최대 21.5초. |
| 반증 조건 | ① 풀 대기 타임아웃 49건 각각의 시각에, 점유 중인(leak 감지 ~ 반환 사이) 리포트 커넥션 수가 5 미만이다 → 풀을 막은 것이 리포트가 아니다.<br>② 같은 코드로 로컬에서 리포트를 5건 이상 동시에 보내도 트랜잭션 점유가 10초를 넘지 않고(leak 감지 없음) 풀 대기 타임아웃도 재현되지 않는다.<br>③ 리포트 1건의 점유 시간 대부분이 서명이 아니라 SQL 실행 시간이다(→ H2 쪽). | ① **불성립**: 타임아웃 49건 전부 그 시각 점유 리포트 커넥션 수 = 5 (`{5: 49}`). 3초 대기 내내 5개가 점유된 경우 48건, 대기 시작 시점에 4개였던 경우 1건.<br>② 로그로 확인할 수 없음(로컬 재현 필요). **실행하지 않음**.<br>③ **판단 불가(로그 부족)**: 로그에 SQL 구간과 서명 구간이 따로 찍히지 않는다. slow log(임계 2.04초 이하)에 걸린 리포트 쿼리는 134건 중 첫 1건뿐이다. → 필요한 로그: 리포트 단계별 시각 로그(조회 종료 · `sign` 시작 · 종료), 또는 Hibernate 통계 · APM 트레이스. |
| 확인 방법 | ① 커넥션별 점유 구간 복원: `grep -nE 'leak detection triggered\|Previously reported leaked\|Connection is not available' $L/app.log \| cut -c1-220` 결과(시각 · 종류 · `Connection@…`)를 시각순으로 놓고, 각 타임아웃 시각에 "감지됐고 아직 반환 안 된" 커넥션 수를 센다.<br>② `modern/api/src/main/java/com/example/assignment/ReportService.java:41-71` 을 열어 트랜잭션 경계와 `sign` 호출 위치 확인. 로컬 재현: `docker compose --profile modern up -d` → `cd modern/api && ./gradlew bootRun` → `seq 8 \| xargs -P8 -I{} curl -s -o /dev/null -w '%{http_code} %{time_total}\n' http://localhost:8080/api/classes/3/report` 를 돌리며 같은 시각에 `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/units` 가 500 이 되는지, bootRun 로그에 `leak detection` · `not available` 이 나오는지 본다.<br>③ `sign` 단독 소요 시간 측정(위 재현에서 `built class report` 직전까지의 시간) vs `mariadb-slow.log` 의 리포트 쿼리 `Query_time` 비교(H2 확인 방법과 같이 본다). | ① 실행함(3.6 R1). ② · ③ 은 로그 밖 작업이라 실행하지 않음. |

### 3.2 가설 H2 — DB · 쿼리: submission 조회가 인덱스를 타지 않아 리포트 쿼리 자체가 느리다

| 항목 | 내용 | 로그 실행 결과 · 판정 (명령 · 원 출력은 3.6) |
|---|---|---|
| 가설 | `submission` 의 `distribution_id = ?` 조회가 인덱스(`idx_submission_distribution`)를 쓰지 않고 대량 행을 훑어(Rows_examined 1,284,310, Rows_sent 4) 수 초씩 걸리고, 리포트는 배포 수만큼 이 쿼리를 반복하므로 리포트 트랜잭션이 커넥션을 오래 쥔다. | **기각** (leak 134건 중 133건에 대해) — 이상 구간 slow log 항목은 14:37:42 1건뿐이고, 기록 임계값은 2.04초 이하다(2.04초 쿼리가 기록됨). 첫 leak 1건(app:1243, 9.8초 쿼리와 시각 일치)의 계기로는 남는다. 전제: slow log 샘플링(`log_slow_rate_limit`)이 꺼져 있다. 이 전제는 DBA 확인이 필요하다. |
| 지지 근거 | a-connection-pool.md:139 — 14:37:42 시작 9.8초 쿼리 `… from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id`, 1,284,310행 검사(slow:15 · 18 · 21). 이 SQL 모양은 `SubmissionRepository.findByDistributionIdOrderByStudentIdAsc` 이고, 이를 부르는 곳은 `ReportService.java:54` 하나뿐이다.<br>a-connection-pool.md:141 — 그 쿼리 종료(14:37:52)와 같은 초에 첫 leak 감지가 나온다. leak 임계값 10초를 거꾸로 세면 커넥션 획득은 14:37:42 이전으로, 느린 쿼리 시작 시각과 맞는다(a-connection-pool.md:88). | 재확인(3.6 R2): 9.8초 쿼리 시작(SET timestamp 14:37:42)과 첫 leak 커넥션 대여 추정 시각(14:37:42.199)이 같은 초다. |
| 반증 조건 | ① 이상 구간(14:38 ~ 14:51) `mariadb-slow.log` 에 리포트 쿼리가 더 없고, 운영 `long_query_time` 이 leak 임계값(10초)보다 충분히 작다 → 이후 133건의 leak 은 SQL 이 느려서 생긴 것이 아니다.<br>② 운영 테이블에서 해당 쿼리의 `EXPLAIN` 이 `idx_submission_distribution` 을 쓰고 rows 추정이 작다 → 14:37:42 건은 일회성(잠금 · 버퍼 상태 등)이다.<br>③ 앱에서 리포트 1건의 SQL 구간이 짧고(수십 ms) 점유 시간 대부분이 SQL 이후다. | ① **성립**: slow log 는 하루 3건이고, 이상 구간에는 14:37:42 1건뿐이다. 나머지 2건 Query_time 2.318 · 2.041초 → `long_query_time` ≤ 2.04초. class 3 리포트는 매번 distribution 5 를 조회하는데(`ReportService.java:54`), 이후 133회는 2.04초를 넘지 않았다. 리포트 1건의 쿼리는 최대 5개(학급 1 · 배포 1 · 제출 3)이므로 모두 2.04초 직전이어도 SQL 합은 약 10.2초다. 최소 점유 15.5초 가운데 5초 이상은 SQL 밖에서 쓰였다.<br>② 로그로 확인할 수 없음(`EXPLAIN` 필요). **실행하지 않음**.<br>③ H1 ③ 과 같다. **판단 불가**. |
| 확인 방법 | ① `grep -nE '^# Time\|Query_time\|^select' $L/mariadb-slow.log` 로 항목을 전부 확인(현재 3건). 운영 `long_query_time` · `log_slow_rate_limit` · `log_queries_not_using_indexes` 값은 DBA 에게 요청한다(**운영 DB 에 직접 접속하지 않는다**). 다른 2건의 Query_time 이 2.0초 · 2.3초이므로 임계값은 2초 이하로 추정되지만 확인이 필요하다.<br>② 스키마 정의 `db/mariadb/init/01-schema.sql:105-115` 확인. 운영 `EXPLAIN SELECT … FROM submission WHERE distribution_id=5 ORDER BY student_id` · `SHOW INDEX FROM submission` · 행 수는 DBA 에게 요청. 로컬은 시드 행 수가 적어 대조용으로만: `docker compose exec mariadb mariadb -ureadonly -p[SECRET] itembank -e "EXPLAIN SELECT * FROM submission WHERE distribution_id=5 ORDER BY student_id; SHOW INDEX FROM submission;"` (서비스 이름은 `docker compose ps` 로 확인).<br>③ H1 ② 의 로컬 재현에서 `spring.jpa.show-sql`/Hibernate 통계를 켜(로컬 실행 인자로만, `application.yml` 은 고치지 않음) 쿼리 시간과 리포트 전체 시간을 비교. | ① 실행함(3.6 R2). ② · ③ 은 실행하지 않음. |

### 3.3 가설 H3 — 트래픽: 교사들의 리포트 요청 급증(재시도 포함)이 방아쇠다

| 항목 | 내용 | 로그 실행 결과 · 판정 (명령 · 원 출력은 3.6) |
|---|---|---|
| 가설 | 전체 요청량은 그대로지만, 교사 단말 몇 대에서 `/api/classes/{id}/report` 요청이 분당 0 ~ 1건에서 10 ~ 19건으로 늘었고(504 를 본 화면의 새로고침 · 재시도가 이를 더 키움), 동시에 처리되는 리포트 수가 풀 크기를 넘었다. | **기각** (방아쇠로서) — 10초 넘는 점유는 리포트 요청이 동시에 1건뿐일 때(14:37:42 대여) 이미 시작됐다. 급증 요청 중 첫 대여는 14:38:10 이다. 반증 조건 ① 이 성립했다. 다만 풀 고갈(동시 5건)에는 동시 요청 증가가 필요했으므로 "증폭 요인"으로 3.5 에 남긴다. |
| 지지 근거 | a-connection-pool.md:142 — 14:38:01 부터 `/report` 분당 요청 3 → 10 → 16 → 19건, 직전 30분 5건. 요청은 교사 IP 4개(교사단말A · 교사단말B · 교사단말C · 교사단말D)에서만. 전체 요청량은 분당 32 ~ 58건으로 그대로.<br>a-connection-pool.md:148 — 504 55건 전부 `/report` 경로(교사가 실패를 보고 다시 요청할 동기).<br>a-connection-pool.md:169 — 리포트 요청이 분당 0 ~ 2건으로 돌아간 뒤에는 leak 감지가 없다. | **보정 필요**: nginx 는 응답이 끝난 시각에 access log 를 쓴다. a-connection-pool.md:142 의 "14:38:01 트래픽 변화 시작"(acc:18801)은 14:37:42 쯤 시작한 첫 leak 요청의 **완료** 시각이다. 그 밖의 숫자는 재확인 결과와 같다: 교사 IP 하루 `/report` 건수 교사단말A 87 · 교사단말B 57 · 교사단말C 56 · 교사단말D 29. |
| 반증 조건 | ① 급증이 첫 이상 신호보다 **뒤**에 시작한다 → 방아쇠가 아니라 결과다. (현재 타임라인상 첫 leak 14:37:52 의 커넥션 획득은 14:37:42 이전이고 급증은 14:38:01 부터다 — 이 순서가 같은 교사 IP 의 동일 경로 반복으로 이어지는지 확인 필요.)<br>② IP 별로 보면 같은 IP 의 `/report` 요청 간격이 응답 완료(200/504/502) 이후가 아니라 일정한 주기(예: 대시보드 자동 새로고침)다 → "재시도" 부분이 틀렸다.<br>③ 이상 구간과 같은 수준(분당 10건 이상)의 리포트 요청이 다른 날 · 다른 시각에도 있었는데 장애가 없었다. | ① **성립**: 대여 추정 시각과 그때의 동시 리포트 수는 14:37:42(1) · 14:38:10(1) · 14:38:41(1) · 14:39:08(1) · 14:39:38(1) · 14:39:40(2) · 14:39:54(2) · 14:40:02(2)이다. 첫 타임아웃(14:40:52) 전에 최대 5에 이른다.<br>② **판단 불가(로그 부족)**: 초기 반복 요청(교사단말B)은 응답이 전부 200 이었다. 다음 요청은 이전 응답 완료 8 ~ 14초 뒤에 시작됐다(예: 완료 14:38:01.9 → 대여 14:38:10.0). 실패 뒤 재시도는 아니지만(첫 504 는 14:41:06), 사람이 새로 고친 것인지 화면의 자동 새로고침인지는 구분할 수 없다. → 필요한 로그: 프런트엔드(교사 화면) 요청 출처 로그, 또는 access log 의 `$request_time`.<br>③ **불성립**(이 하루 안에서): 14:38 ~ 14:51 밖의 분당 `/report` 최대는 2건이고(12:20 · 14:59 · 15:20), 구간 안 최대는 19건이다. 다른 날 로그는 없다. |
| 확인 방법 | ① · ② `grep -n '/report' $L/nginx-access.log \| awk '$4 >= "[16/Sep/2026:14:30" && $4 < "[16/Sep/2026:14:53"' \| awk '{print $1, $4, $7, $9}'` 로 IP · 시각 · 경로 · 상태를 뽑아 IP 별로 정렬(`sort -k1,1 -k2,2`)하고 간격과 직전 응답 상태를 본다. 리퍼러(`/teacher/classes/3/report` 등)로 화면도 구분한다.<br>③ 같은 파일의 하루 전체 분당 `/report` 건수: `grep '/report' $L/nginx-access.log \| grep -o '16/Sep/2026:[0-9:]\{5\}' \| uniq -c \| sort -rn \| head`. 다른 날 로그는 운영팀에 요청. | ①②③ 실행함(3.6 R3). |

### 3.4 가설 H4 — 인프라: upstream 2대 중 한 대가 연결을 거부해 부하가 한 대에 몰렸다

| 항목 | 내용 | 로그 실행 결과 · 판정 (명령 · 원 출력은 3.6) |
|---|---|---|
| 가설 | nginx upstream(`item_bank_api`) 의 두 서버 중 `API-2:8080` 이 연결을 거부(프로세스 중지 · 포트 미기동 등)하는 상태여서 트래픽이 `API-1` 한 대의 풀(5개)에 몰렸고, 그 한 대마저 응답 지연으로 nginx 에서 일시 제외되며 `no live upstreams` 502 가 났다. | **판단 불가(로그 부족)** — `API-2` 는 14:42:01 ~ 14:50:52 의 `connect() failed` 14건에만 나온다. 그 전에 `API-2` 가 요청을 처리했는지 보여 줄 필드가 없다(access log 의 upstream 주소 0건, app.log 의 호스트 단서 0건). → 필요한 로그: `$upstream_addr` · `$upstream_response_time` 을 넣은 nginx access log, 인스턴스(호스트)가 찍힌 app log, `API-2` 의 같은 날 app log · 재시작 이력, nginx `upstream` 설정. |
| 지지 근거 | a-connection-pool.md:149 — 14:42:01 `connect() failed (111: Connection refused)`(err:11). 원본 err:11 의 upstream 은 `API-2:8080` 이고, `connect() failed` 14건은 전부 이 주소다. 같은 초에 `API-1` 이 `temporarily disabled`(err:10).<br>a-connection-pool.md:150 — 그 직후부터 `no live upstreams` 68건, 502 82건이 리포트 외 경로까지 퍼진다.<br>a-connection-pool.md:139 — slow log 의 클라이언트 호스트가 `API-1` 이다(slow:16, 원본). | 재확인(3.6 R4): `API-2` 줄 14건은 전부 `[error] connect() failed`, `API-1` 줄은 `upstream timed out` 55 · `temporarily disabled` 26. 14:41 이전 nginx-error 에는 upstream 관련 줄이 없다(buffered 경고 2 · info 3). slow log 3건의 접속 호스트는 모두 `API-1`. |
| 반증 조건 | ① 14:42 이전(평소 구간)에도 `API-2` 가 요청을 정상 처리하지 않았다(설계상 대기 서버 · `backup` 이거나 원래 꺼져 있던 서버) → 부하 집중이 "변화"가 아니다.<br>② `API-2` 의 앱 로그에서 14:37 ~ 14:52 에도 요청 처리 · 정상 기동 상태가 이어진다 → `Connection refused` 는 다른 원인(일시적 accept 큐 초과 등)이다.<br>③ 우리가 가진 `app.log` 가 `API-2` 의 로그다(호스트 대응이 반대). | ① **판단 불가, 다만 간접 신호가 있다**: app.log 와 nginx 의 건수가 맞는다. 리포트 생성은 14:37 이전 50 = acc `/report` 200 50, 14:52 이후 6 = 6. 재배포(성공 + 409)는 49 = 49. 이상 구간의 app 리포트 생성 134 = acc 200 79 + 504 55. app.log 가 인스턴스 한 대의 로그라면 `API-2` 는 평소에도 리포트 · 재배포를 받지 않았다는 뜻이고, 그러면 반증 조건 ① 이 성립한다. 그러나 app.log 가 두 대를 합친 로그인지 알 수 없다. 이상 구간의 leak · 타임아웃이 커넥션 5개 · `total=5` 하나의 풀에서만 나온 점은 한 대 쪽을 가리킨다.<br>② 로그 없음(`API-2` app log 미보유). **판단 불가**.<br>③ app.log 에 호스트 단서 0건(`Started` · `hostname` · `10.20.x.x` 검색), PID 는 1,927줄 모두 `1`. **판단 불가**. |
| 확인 방법 | ① nginx 설정의 `upstream item_bank_api` 블록(`server … max_fails= fail_timeout= backup`)을 운영팀에 요청(저장소에 없음: `find . -name '*.conf' -path '*nginx*'` 결과 0건). 평소 구간에 `API-2` 가 쓰였는지: `grep -c 'API-2' $L/nginx-error.log`(현재 14건, 전부 14:42 이후인지 `grep -n` 으로 시각 확인). access log 에는 upstream 주소가 없으므로 `$upstream_addr` 포함 로그 형식 여부도 함께 확인.<br>② · ③ `API-2` 의 같은 날 `app.log` 와 컨테이너/프로세스 재시작 기록(`docker ps`/`systemctl` 이력, 오케스트레이터 이벤트)을 운영팀에 요청. 지금 `app.log` 의 호스트는 `grep -m3 -n 'Started\|hostname\|10.20.x.x' $L/app.log` 로 단서를 찾고, 없으면 수집 경로를 운영팀에 확인. | ①③ 실행함(3.6 R4). ② 는 로그가 없어 실행하지 못함. |

### 3.5 가설 간 관계와 다음 단계

- H1 · H2 는 "리포트 요청 하나가 커넥션을 오래 쥐는 이유"를, H3 · H4 는 "동시에 몰린 리포트 수 · 받아 줄 커넥션 수"를 설명한다. 서로 배타적이지 않으므로 각각 따로 반증을 시도한다.
- 2절 a-connection-pool.md:124 에서 설정 재적재 · 재시작 · 배포 흔적이 0건이었으므로 "배포 · 설정 변경" 계층 가설은 이번 로그만으로는 세울 근거가 없다. H4 확인 중 `API-2` 의 재시작 · 배포 이력이 나오면 그 계층의 가설을 추가한다.
- 로그 확인 결과(2026-10-06 실행): H1 **유지**, H2 **기각**(첫 leak 1건의 계기로는 남음), H3 **기각**(방아쇠로서. 증폭 요인으로는 남음), H4 **판단 불가**. 이 판정은 반증 시도의 결과일 뿐이고, 근본 원인 확정은 4절에서 한다.
- **새로 생긴 질문** (어느 가설로도 아직 설명되지 않음): 동시 리포트가 1건뿐이던 14:37:42 ~ 14:39:38 의 점유 시간도 15.6 ~ 19.7초였다(3.6 R1). 같은 class 3 리포트(3 distributions, 10 submissions)가 14:10 ~ 14:18 에는 leak 없이(10초 미만) 끝났다(a-connection-pool.md:130). slow log 에 걸리지 않았으므로 SQL 만으로는 설명되지 않는다. 14:37 전후로 리포트 1건의 처리 시간이 왜 바뀌었는지 확인해야 한다. 필요한 로그: 앱 호스트 CPU · GC 지표, 리포트 단계별 소요 시간.
- 2절 보정 메모: `nginx-access.log` 의 시각은 **응답 완료** 시각이다. a-connection-pool.md:142 의 "트래픽 변화 시작 14:38:01" 은 요청 시작이 아니라 첫 leak 요청의 완료 시각이다. 2절 본문은 고치지 않았다.

### 3.6 실행 기록

> 2026-10-06, 저장소 루트에서 실행. `L=incident-logs/a-connection-pool`. 출력은 그대로 옮겼다(토큰이 포함된 줄은 앞부분만 남기고 생략).

**R1 (H1) — 커넥션 점유 구간 복원.** leak 감지 시각 − 10초를 대여 시각으로 보고(Hikari 는 대여 후 `leak-detection-threshold` 가 지나면 감지한다), `(커넥션, 스레드)` 로 반환 줄과 짝지었다.

```bash
grep -cE 'leak detection triggered' $L/app.log            # 134
grep -cE 'Previously reported leaked' $L/app.log          # 134
grep -E '^2026.*Connection is not available' $L/app.log | wc -l   # 49
python3 -I h1.py $L/app.log
```

```python
# h1.py
import re,sys
from datetime import datetime,timedelta
P=re.compile(r'^(2026-\S+)\s.*?(leak detection triggered|Previously reported leaked|Connection is not available).*?(?:Connection@([0-9a-f]+) on thread (\S+))?')
open_={};iv=[];to=[]
for n,l in enumerate(open(sys.argv[1]),1):
    m=P.match(l)
    if not m: continue
    t=datetime.fromisoformat(m.group(1));mm=re.search(r'Connection@([0-9a-f]+) on thread ([^ ,]+)',l);k=mm.groups() if mm else None
    if m.group(2).startswith('leak'): open_[k]=(t-timedelta(seconds=10),t,n)
    elif m.group(2).startswith('Prev'):
        b,d,dn=open_.pop(k); iv.append((b,t,k[0],dn,n))
    else: to.append((t,n))
print('pairs',len(iv),'unpaired',len(open_))
holds=sorted((e-b).total_seconds() for b,e,*_ in iv)
print('hold_s(min/median/max) %.1f %.1f %.1f'%(holds[0],holds[len(holds)//2],holds[-1]))
from collections import Counter
c=Counter()
for t,n in to:
    k=sum(1 for b,e,*_ in iv if b<=t<=e); c[k]+=1
print('timeouts by #report-conns-held:',dict(sorted(c.items())))
# also at timeout start (t-3s)
c2=Counter()
for t,n in to:
    s=t-timedelta(seconds=3); c2[sum(1 for b,e,*_ in iv if b<=s and e>=t)]+=1
print('held through whole 3s wait:',dict(sorted(c2.items())))
print('first 8 intervals (borrow=detect-10s, return, hold, concurrent at borrow):')
for b,e,cn,dn,rn in sorted(iv)[:8]:
    print(b.time(),e.time(),'%.1f'%(e-b).total_seconds(),cn,'app:%d-%d'%(dn,rn),sum(1 for b2,e2,*_ in iv if b2<=b<=e2))
first_to=to[0][0]
print('max concurrent before first timeout:',max(sum(1 for b2,e2,*_ in iv if b2<=b<=e2) for b,*_ in iv if b<first_to))
```

```
pairs 134 unpaired 0
hold_s(min/median/max) 15.5 18.9 21.5
timeouts by #report-conns-held: {5: 49}
held through whole 3s wait: {4: 1, 5: 48}
first 8 intervals (borrow=detect-10s, return, hold, concurrent at borrow):
14:37:42.199000 14:38:01.902000 19.7 6e8f14d3 app:1243-1259 1
14:38:10.016000 14:38:27.074000 17.1 6e8f14d3 app:1261-1277 1
14:38:41.641000 14:38:57.714000 16.1 2ac19f07 app:1280-1296 1
14:39:08.720000 14:39:24.424000 15.7 6e8f14d3 app:1299-1315 1
14:39:38.603000 14:39:54.238000 15.6 2ac19f07 app:1316-1347 1
14:39:40.132000 14:39:55.670000 15.5 71d4b8e2 app:1331-1349 2
14:39:54.730000 14:40:11.546000 16.8 3f6c2a91 app:1351-1368 2
14:40:02.901000 14:40:21.100000 18.2 5b0e9e0c app:1370-1403 2
max concurrent before first timeout: 5
```

**R2 (H2) — slow log 전체 항목.**

```bash
grep -nE '^# Time|Query_time|^SET timestamp' $L/mariadb-slow.log
```

```
1:# Time: 260916  0:14:09
4:# Query_time: 2.318446  Lock_time: 0.000071  Rows_sent: 7  Rows_examined: 41822
6:SET timestamp=1789517647;
8:# Time: 260916  2:02:42
11:# Query_time: 2.041903  Lock_time: 0.912004  Rows_sent: 5  Rows_examined: 5
13:SET timestamp=1789524160;
15:# Time: 260916  5:37:52
18:# Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310
20:SET timestamp=1789537062;
```

**R3 (H3) — 리포트 요청 분포.**

```bash
# ③ 하루 중 분당 /report 상위
grep '/report' $L/nginx-access.log | grep -o '16/Sep/2026:[0-9:]\{5\}' | uniq -c | sort -rn | head -5
#   19 14:46 · 19 14:43 · 16 14:49 · 16 14:41 · 15 14:45
# ③ 14:38 ~ 14:51 을 뺀 분당 /report 상위
grep '/report' $L/nginx-access.log | grep -o '16/Sep/2026:[0-9:]\{5\}' | grep -vE '14:(3[89]|4[0-9]|5[01])$' | uniq -c | sort -rn | head -3
#   2 15:20 · 2 14:59 · 2 12:20
# IP 별 하루 /report 건수
grep '/report' $L/nginx-access.log | awk '{print $1}' | sort | uniq -c
#   87 교사단말A · 57 교사단말B · 56 교사단말C · 29 교사단말D
# ① · ② 14:30 ~ 14:41 /report (IP, 완료 시각, 경로, 상태)
grep '/report' $L/nginx-access.log | awk '$4>="[16/Sep/2026:14:30" && $4<"[16/Sep/2026:14:41"{print $1,substr($4,14),$7,$9}'
```

```
교사단말B 14:38:01 /api/classes/3/report 200
교사단말B 14:38:27 /api/classes/3/report 200
교사단말B 14:38:57 /api/classes/3/report 200
교사단말B 14:39:24 /api/classes/3/report 200
교사단말A 14:39:54 /api/classes/1/report 200
교사단말B 14:39:55 /api/classes/3/report 200
교사단말A 14:40:11 /api/classes/1/report 200
교사단말B 14:40:19 /api/classes/3/report 200
교사단말A 14:40:21 /api/classes/2/report 200
교사단말C 14:40:27 /api/classes/3/report 200
교사단말A 14:40:32 /api/classes/1/report 200
교사단말A 14:40:36 /api/classes/2/report 200
교사단말D 14:40:43 /api/classes/1/report 200
교사단말B 14:40:44 /api/classes/3/report 200
교사단말A 14:40:47 /api/classes/1/report 200
교사단말C 14:40:52 /api/classes/3/report 200
```

**R4 (H4) — upstream 주소 · 호스트 · 건수 대조.**

```bash
grep -n 'API-2' $L/nginx-error.log | awk '{print $1,$2,$3}' | sed -n '1p;$p'
#   11:2026/09/16 14:42:01 [error] / 159:2026/09/16 14:50:52 [error]
grep 'API-2' $L/nginx-error.log | grep -oE '\[(error|warn)\] [0-9#]+: \*[0-9]+ [a-z() 0-9:]+' | sed -E 's/\*[0-9]+ //' | sort | uniq -c
#   14 [error] 29#29: connect() failed (111:
grep 'API-1' $L/nginx-error.log | grep -oE '\] [0-9#]+: \*[0-9]+ [a-zA-Z() 0-9]+' | sed -E 's/.*\*[0-9]+ //' | sort | uniq -c
#   26 upstream server temporarily disabled while reading response header from upstream
#   55 upstream timed out (110
awk '$2<"14:41"' $L/nginx-error.log | cut -c1-60
#   09:12:03 [warn] buffered · 10:42:17 [info] too long header · 11:47:29 [info] keepalive · 13:05:51 [warn] buffered · 13:18:05 [info] too long header
grep -cE '10\.20\.x\.x' $L/nginx-access.log                 # 0  (access log 에 upstream 주소 없음)
grep -cE 'Started|hostname|10\.20\.x\.x' $L/app.log              # 0
grep -oE '^[^ ]+ +[A-Z]+ [0-9]+ ---' $L/app.log | awk '{print $3}' | sort | uniq -c   # 1927 1  (PID 전부 1)
grep 'User@Host' $L/mariadb-slow.log                              # 3건 모두 [API-1]
# app.log 와 nginx 건수 대조
grep -c 'built class report' $L/app.log                                                        # 190
grep 'built class report' $L/app.log | awk '$1<"2026-09-16T14:37"' | wc -l                      # 50
grep 'built class report' $L/app.log | awk '$1>="2026-09-16T14:52"' | wc -l                     # 6
grep '/report' $L/nginx-access.log | awk '$9==200' | wc -l                                      # 135
grep '/report' $L/nginx-access.log | awk '$9==200 && $4<"[16/Sep/2026:14:37"' | wc -l           # 50
grep '/report' $L/nginx-access.log | awk '$9==200 && $4>="[16/Sep/2026:14:52"' | wc -l          # 6
grep -cE 'redistributed distribution|state conflict' $L/app.log                                # 49
grep 'redistribute' $L/nginx-access.log | awk '$9==200||$9==409' | wc -l                        # 49
```

- 이상 구간의 리포트: app 생성 190 − 50 − 6 = 134, acc 200 135 − 50 − 6 = 79, acc 504 55. 79 + 55 = 134 이다.

### 3.7 판정

> 3.1 ~ 3.6 에서 유지된 가설(H1)을 `modern/api` 의 설정 파일과 로그에 찍힌 클래스의 코드와 대조했다. 설정값은 파일에서 읽은 값만 썼다. 벤치마크 · 테스트는 실행하지 않았다.

#### 3.7.1 로그에 찍힌 단서 → 코드 위치

`app.log` 의 `com.example` 스택 프레임과 로거 이름을 모아 대응하는 파일을 열었다.
명령: `grep -ohE 'com\.example\.[A-Za-z.$]+\([A-Za-z]+\.java:[0-9]+\)' $L/app.log | sort | uniq -c`

| 로그 단서 (건수) | 코드 위치 | 저장소 코드와 줄 일치 |
|---|---|---|
| `ReportController.classReport(ReportController.java:22)` (139 = leak 134 + 타임아웃 5) | `modern/api/src/main/java/com/example/assignment/ReportController.java:22` `return reportService.buildClassReport(id);` | 일치 |
| `ItemController.listItemsByUnit(ItemController.java:31)` (19) | `…/item/ItemController.java:31` `return itemService.listActiveItemsByUnit(code);` | 일치 |
| `UnitController.listUnits(UnitController.java:22)` (14) | `…/item/UnitController.java:22` `return unitService.listUnits();` | 일치 |
| `ItemController.getItem(ItemController.java:25)` (7) | `…/item/ItemController.java:25` `return itemService.getItem(id);` | 일치 |
| `DistributionController.getDistribution(DistributionController.java:26)` (4) | `…/assignment/DistributionController.java:26` `return distributionService.getDistribution(id);` | 일치 |
| `ReportService$$SpringCGLIB$$0.buildClassReport` ← `JpaTransactionManager.doBegin` ← `HikariDataSource.getConnection` (app:1243 아래 연속 줄) | `…/assignment/ReportService.java:41-42` `@Transactional public ClassReport buildClassReport(…)` | — |
| 로거 `GlobalExceptionHandler` `unhandled exception on …` (49) | `…/common/GlobalExceptionHandler.java:64-67` `@ExceptionHandler(Exception.class)` → `log.error(…)` → 500 | — |
| 풀 이름 `itembank-pool`, `total=5`, `after 3000ms` | `modern/api/src/main/resources/application.yml:10` `pool-name: itembank-pool` · `:14` `maximum-pool-size: 5` · `:15` `connection-timeout: 3000` | 일치 |
| `Connection leak detection triggered` (`ProxyLeakTask`) | `application.yml:18` `leak-detection-threshold: 10000` | — |

- 타임아웃 프레임 5개 경로의 합은 19 + 14 + 7 + 4 + 5 = 49 다. 3.6 R1 의 타임아웃 49건, 2절의 500 경로 분포(a-connection-pool.md:147)와 같다.
- 스택 프레임 줄번호가 저장소 코드와 모두 일치한다. 장애 당시 배포본과 지금 저장소 코드가 이 부분에서 같다고 보는 근거다. 배포 버전 기록으로 확인한 것은 아니다.

#### 3.7.2 H1 대조 결과: **코드 · 설정이 가설을 지지한다**

| H1 의 주장 | 코드 · 설정 근거 (파일:줄번호, 인용) | 대조 결과 |
|---|---|---|
| 커넥션은 트랜잭션 시작 시 잡혀 트랜잭션 끝까지 유지된다 | `ReportService.java:41` `@Transactional`. 로그 스택의 획득 지점이 `JpaTransactionManager.doBegin`(app:1243 연속 줄). `application.yml:22` `open-in-view: false` 이므로 트랜잭션 밖(Controller · 뷰 렌더링)에서는 커넥션을 쥐지 않는다 | 지지 |
| DB 조회가 끝난 뒤에도 커넥션을 쥔 채 서명을 계산한다 | `ReportService.java:43-63` 에서 조회 5회(`classRoomRepository.findById` 1 · `findByClassRoomIdOrderByDistributedAtAsc` 1 · 배포마다 `findByDistributionIdOrderByStudentIdAsc`, class 3 은 배포 3개). 그 뒤 `:71` `String signature = sign(payload.toString());` 가 같은 `@Transactional` 메서드 안에 있다. `:69-70` 주석: "TODO: 트랜잭션 밖으로 … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다." | 지지 |
| 서명은 CPU 를 오래 쓰는 계산이다 | `ReportService.java:23` `static final int SIGNATURE_ROUNDS = 200_000;`, `:88-91` 에서 SHA-256 을 그 횟수만큼 반복 | 지지 (소요 시간 자체는 코드로 알 수 없음, 아래 한계 참고) |
| 풀이 5개뿐이라 리포트 5건이 겹치면 다른 API 가 막힌다 | `application.yml:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000`. 다른 API 도 모두 트랜잭션으로 커넥션을 빌린다: `ItemService.java:14` · `UnitService.java:10` `@Transactional(readOnly = true)`, `DistributionService.java:30` `@Transactional(readOnly = true)`. 이 경로들이 3초 안에 커넥션을 못 받으면 `GlobalExceptionHandler.java:64-67` 이 500 을 준다 | 지지 |
| 리포트 요청끼리 막을 장치가 없다 | `ReportController.java:20-23` 과 `ReportService` 에 동시 실행 제한 · 캐시 · 비동기 처리가 없다(`grep -rn "Async\|Executor\|@Scheduled\|parallel" modern/api/src/main/java` 0건) | 지지 |

**코드 · 설정으로 설명되지 않는 부분 (한계)**

- `sign` 의 반복 횟수는 상수이고(`:23`), 첫 digest 이후 입력은 항상 32바이트다(`:87-91`). 그래서 서명 시간은 데이터 크기와 상관없이 일정해야 한다. 그런데 같은 class 3 리포트가 14:10 ~ 14:18 에는 10초 안에 끝났고(a-connection-pool.md:130), 14:37 이후에는 동시 1건일 때도 15.6 ~ 19.7초가 걸렸다(3.6 R1). 코드에 이 차이를 만들 분기가 없다. 앱 호스트 쪽 요인(CPU · GC 등)이 있었을 수 있지만, 그 지표가 없어 확인하지 못했다.
- 커넥션을 쥔 시간 가운데 서명이 차지한 몫은 코드로도 로그로도 측정되지 않는다. 벤치마크는 이번 범위에서 실행하지 않았다.
- 이 위반은 `CLAUDE.md` 4절에도 "알려진 위반 사례"로 적혀 있다. 이 판정은 그 문서가 아니라 위 코드 · 로그 대조를 근거로 한다.

#### 3.7.3 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 `@Transactional` 안에서 DB 조회 뒤 서명 계산(SHA-256 × 200,000)까지 커넥션을 쥐어, 동시 리포트 요청 5건이 풀(5개)을 모두 점유하고 다른 API 가 3초 대기 후 500 을 받았다.**

| 가설 | 결론 | 사유 |
|---|---|---|
| H1 애플리케이션 코드 | **채택** | 로그: 풀 대기 타임아웃 49건 모두 그 시각에 리포트 커넥션 5개가 점유 중(3.6 R1). 코드: 커넥션을 트랜잭션 전체 동안 쥐고(`ReportService.java:41`, `application.yml:22`), DB 조회 뒤 서명을 같은 트랜잭션 안에서 돈다(`ReportService.java:71`). 설정: 풀 5 · 대기 3000ms · leak 10초가 로그 값과 일치(`application.yml:14 · 15 · 18`) |
| H2 DB · 쿼리 | **기각** | 이상 구간 slow log 는 14:37:42 1건뿐이고 기록 임계는 2.04초 이하다. leak 134건 중 133건은 느린 쿼리 없이 일어났다(3.6 R2). 코드상 리포트 1건의 쿼리는 5회다(`ReportService.java:43-54`). 모두 임계 직전이어도 SQL 은 약 10초여서 최소 점유 15.5초를 다 설명하지 못한다. 첫 leak 1건의 계기(9.8초 쿼리)로만 남는다 |
| H3 트래픽 | **기각** (방아쇠로서) | 10초 넘는 점유가 동시 1건일 때(14:37:42) 이미 시작됐다(3.6 R1). 동시 리포트 증가는 H1 의 구조 위에서 풀을 바닥낸 증폭 요인이며, 원인이 아니다 |
| H4 인프라 | **미채택** (판단 불가, 기각 아님) | `API-2` 가 평소에 요청을 처리했는지 보여 주는 로그가 없다(3.6 R4). `modern/api` 의 설정 · 코드에는 nginx upstream 정보가 없어 대조할 대상도 없다. 건수 대조로 보면 한 대가 하루 내내 리포트 · 재배포를 처리했을 가능성이 높아, H1 을 바꿀 요인으로 보지 않았다 |

**확신 수준: 중간**

- 높일 근거: 로그(타임아웃 49/49 시점에 리포트 커넥션 5/5 점유), 코드(트랜잭션 안 서명, `ReportService.java:69-71`), 설정(풀 · 타임아웃 · leak 값이 로그와 일치)이 서로 다른 출처에서 같은 사슬을 가리킨다. 스택 프레임 줄번호도 저장소 코드와 일치한다.
- 낮출 근거:
  1. 같은 리포트의 점유 시간이 14:37 전후로 10초 미만에서 15초 이상으로 늘어난 이유를 코드가 설명하지 못한다(3.7.2 한계). H1 은 "왜 풀이 막혔는가"는 설명하지만 "왜 14:37 부터인가"는 설명하지 못한다.
  2. 서명이 점유 시간에서 차지한 몫을 측정하지 않았다.
  3. H4 를 반증하지 못했다.
- "높음" 으로 올리려면: 14:37 전후 앱 호스트의 CPU · GC 지표, 리포트 단계별 소요 시간(조회 종료 · `sign` 시작 · 종료), nginx `$upstream_addr` 가 든 access log 가 있어야 한다.

## 4. 원인

> 3.7 에서 채택한 H1 을 인과 순서로 적는다. 문장 번호(C1 …)는 5절의 근거 줄과 대응한다.

### 4.1 배경 원인 — 장애가 일어날 수 있게 만든 구조

- **B1** 학급 리포트 API 는 DB 조회를 마친 뒤에도 같은 `@Transactional` 메서드 안에서 SHA-256 을 200,000회 반복하는 서명을 계산한다. 그동안 DB 커넥션을 반환하지 않는다.
- **B2** 커넥션 풀은 5개, 대기 한도는 3초이며 모든 API 가 이 풀 하나를 같이 쓴다. 리포트 한 종류가 풀을 다 차지해도 다른 API 몫이 남지 않는다.
- **B3** 리포트 요청에는 동시 실행 제한 · 캐시가 없다. 요청이 오는 만큼 커넥션을 오래 쥐는 작업이 늘어난다.
- **B4** nginx 는 한 경로(리포트)의 응답 지연만으로 upstream 서버 전체를 일시 제외한다. 그래서 리포트와 무관한 경로도 502 를 받는다.

### 4.2 직접 원인 — 사건의 사슬

- **C1** 14:37:42 class 3 리포트 요청이 커넥션을 빌렸다. 그 안의 submission 조회 하나가 9.8초 걸렸고(1,284,310행 검사), 커넥션은 19.7초 뒤인 14:38:01 에 반환됐다.
- **C2** 그 뒤의 리포트 요청은 동시 1건뿐일 때도 커넥션을 15.5 ~ 21.5초씩 쥐었다(B1). 14:10 ~ 14:18 의 같은 리포트는 10초 안에 끝났다. 무엇이 이 차이를 만들었는지는 확인하지 못했다(9절).
- **C3** 14:39:40 부터 다른 교사의 리포트 요청이 겹치기 시작했다(동시 2건, 요청 IP 는 교사 4명). 동시에 처리 중인 리포트가 14:40:11 에 4건, 14:40:25 에 5건이 되어 풀 전체를 차지했다(B2 · B3).
- **C4** 14:40:52 부터 다른 API(문항 · 단원 · 배포 조회)가 3초 동안 커넥션을 받지 못하고 500 을 받았다. 14:49:51 까지 49건이며, 이때마다 리포트 커넥션 5개가 점유 중이었다.
- **C5** 14:41:06 부터 리포트 응답이 nginx 대기 한도를 넘어 504 가 났다(55건). 14:42:01 nginx 가 upstream 을 일시 제외해 `no live upstreams` 502 가 다른 경로까지 퍼졌다(82건, B4).
- **C6** 리포트 요청이 분당 5건 이하로 줄자 14:51:47 의 마지막 504 를 끝으로 정상으로 돌아왔다. 이후 리포트는 leak 감지 없이 처리됐다.

**직접 원인 한 줄**: 리포트 요청들이 서명 계산 동안까지 커넥션을 쥔 채 동시에 5건 쌓여 풀(5개)을 독점했다(C2 · C3).

## 5. 근거 로그 줄

| 문장 | 근거 (파일:줄번호) | 발췌 |
|---|---|---|
| B1 | `modern/api/src/main/java/com/example/assignment/ReportService.java:41` · `:71` · `:23` | `@Transactional` / `String signature = sign(payload.toString());` / `SIGNATURE_ROUNDS = 200_000` |
| B1 | `app.log:1243` 아래 스택 | `JpaTransactionManager.doBegin` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `ReportController.classReport(ReportController.java:22)` |
| B2 | `modern/api/src/main/resources/application.yml:14` · `:15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B3 | `ReportController.java:20-23` | `@GetMapping("/{id}/report")` → `return reportService.buildClassReport(id);` (제한 · 캐시 없음) |
| B4 | `nginx-error.log:10` · `:12` | `upstream server temporarily disabled while reading response header` / `no live upstreams while connecting to upstream` |
| C1 | `mariadb-slow.log:18` · `:20` · `:21` | `Query_time: 9.812417 … Rows_examined: 1284310` / `SET timestamp=1789537062;` / `… where s1_0.distribution_id=5 order by s1_0.student_id;` |
| C1 | `app.log:1243` · `:1259` | `14:37:52.199 … Connection leak detection triggered for …Connection@6e8f14d3` / `14:38:01.902 … Previously reported leaked connection …@6e8f14d3 … was returned` |
| C2 | `app.log:1261` · `:1277` (예시, 전체는 3.6 R1) | `14:38:20.016 … leak detection triggered …@6e8f14d3` / `14:38:27.074 … was returned` (점유 17.1초, 동시 1건) |
| C2 | `app.log:1148` | `14:10:05.155 … built class report for class 3` (전후 leak 감지 없음) |
| C3 | `app.log:1331` · `nginx-access.log:18881` | `14:39:50.132 … leak detection triggered …@71d4b8e2` (빌린 시각 14:39:40.1, 동시 2건) / `교사단말A … 14:39:54 … "GET /api/classes/1/report" 200` (그 요청의 완료) |
| C3 | `app.log:1468` | `14:40:35.820 … Connection leak detection triggered …@2ac19f07` (빌린 시각 14:40:25.8 → 동시 5건) |
| C4 | `app.log:1522` · `:1523` | `itembank-pool - Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` / `unhandled exception on /api/units/M6-2/items` |
| C4 | `nginx-access.log:18918` | `"GET /api/units/M6-2/items HTTP/1.1" 500 162` |
| C4 | `app.log:4980` · `:4981` | 마지막 풀 대기 타임아웃 14:49:51.552 |
| C5 | `nginx-error.log:6` · `nginx-access.log:18933` | `upstream timed out (110: Connection timed out)` / `"GET /api/classes/3/report HTTP/1.1" 504` |
| C5 | `nginx-error.log:10` · `:12` · `nginx-access.log:18970` | `temporarily disabled` / `no live upstreams` / `"GET /api/classes/2/report HTTP/1.1" 502` |
| C6 | `nginx-error.log:168` · `nginx-access.log:19381` | `14:51:47 … upstream timed out` / `"GET /api/classes/3/report HTTP/1.1" 504` |
| C6 | `app.log:5283` · `:5284` · `:5287` | `built class report for class 3` (14:59 ~ 15:01, leak 감지 없음) |

## 6. 수정안 (지금 당장)

> 제안만 적는다. 코드는 고치지 않았다.

1. **서명 계산을 트랜잭션 밖으로 분리** — `modern/api/src/main/java/com/example/assignment/ReportService.java`
   - `buildClassReport`(`:41-82`)에서 `@Transactional` 을 뗀다.
   - DB 조회와 집계(`:43-67`)는 별도 빈의 `@Transactional(readOnly = true)` 메서드로 옮긴다(예: `ReportReader.read(classId)` 가 학급 · 배포 수 · 제출 수 · 평균 · payload 를 담은 record 를 반환). 같은 클래스 안의 호출은 프록시를 거치지 않아 트랜잭션이 걸리지 않으므로 반드시 다른 빈으로 둔다.
   - `buildClassReport` 는 그 결과를 받은 **뒤** `sign(payload)`(`:71`)를 호출한다. 이렇게 하면 커넥션 점유가 "조회 5회" 로 줄어든다.
   - `:69-70` TODO 주석은 제거한다. `CLAUDE.md` 4절의 "알려진 위반 사례" 문구도 함께 정리한다.
2. **동작 보존 테스트** — `ReportServiceTest`(Mockito)를 추가한다. 응답 필드(`assignmentCount` · `submissionCount` · `averageScore` · `signature`)가 수정 전과 같음을 확인한다. `@ActiveProfiles("test")`, 한국어 `@DisplayName` 을 붙인다.
3. **하지 않는 것** — 풀 크기 · 대기 시간(`application.yml` 의 `spring.datasource.hikari`)은 바꾸지 않는다. 프로젝트 규칙상 변경 금지이고, 키워도 점유 시간이 그대로면 고갈 시점만 늦춰진다.

## 7. 재발 방지 (구조)

| 장치 | 내용 | 막는 것 |
|---|---|---|
| 트랜잭션 경계 테스트 | 서명 · 해시 · 외부 호출 메서드 안에서 `TransactionSynchronizationManager.isActualTransactionActive()` 가 `false` 임을 확인하는 테스트를 리포트에 두고, 같은 유형의 새 기능에도 의무화한다 | B1 의 재발 |
| 리뷰 규칙 자동 점검 | `convention-check` 항목과 `templates/approval-checklist.md` 에 "`@Transactional` 메서드 안 CPU 계산 · 외부 HTTP 호출 금지" 를 판정 항목으로 넣는다. 정적 검사 도구(ArchUnit 등) 도입은 새 의존성이므로 별도 승인이 필요하다 | B1 유형이 리뷰를 통과하는 것 |
| 무거운 API 격벽 | 리포트처럼 오래 걸리는 API 에 동시 실행 상한(풀 크기보다 작게, 예: 2)을 둔다. 상한을 넘으면 즉시 429 · 503 을 돌려준다 | B2 · B3 — 한 API 가 풀을 독점하는 것 |
| 리포트 결과 캐시 | 같은 학급 리포트는 제출 데이터가 바뀌기 전까지 재계산하지 않는다(교사 화면 새로 고침이 반복됐음) | B3 |
| nginx 경로 분리 | `/api/classes/*/report` 를 별도 `location` 으로 두고 upstream 실패 판정(`max_fails`)에서 빼거나 따로 설정해, 한 경로의 지연이 서버 전체 제외로 번지지 않게 한다(인프라팀 협의) | B4 |

## 8. 모니터링 항목

> "이번에 울렸을 시각" 은 앞 절의 로그 · 복원값 기준이다. 풀 지표 시각은 leak 구간으로 복원한 값이라 실제 값과 같거나 더 이르다(10초 미만 점유는 복원되지 않음).

| 지표 | 임계값 (제안) | 이번에 울렸을 시각 | 근거 |
|---|---|---|---|
| Hikari leak 감지 로그 건수 | 5분에 1건 이상 → 경고 | **14:37:52** | app:1243 |
| MariaDB slow query | `Query_time` ≥ 5초 1건 → 경고 | **14:37:52** (쿼리 종료 시각) | slow:15 · 18 |
| 리포트 API 응답 시간 | p95 ≥ 5초 (1분) → 경고 | **14:38:01** (첫 리포트 19.7초) | acc:18801, 3.6 R1 |
| 풀 사용률 `hikaricp_connections_active / max` | ≥ 0.8 (15초 수집 1회) → 경고 | **14:40:11** (4/5), 14:40:25 (5/5) | 3.6 R1 복원 |
| 풀 대기 스레드 `hikaricp_connections_pending` | > 0 이 30초 중 2회 이상 → 긴급 | **14:40:49 이전** (14:40:52 타임아웃에 `waiting=4`) | app:1522 |
| 풀 대기 타임아웃 `hikaricp_connections_timeout_total` | 1건 이상 증가 → 긴급 | **14:40:52** | app:1522 |
| nginx 5xx 비율 | 1분 5xx / 전체 ≥ 1% → 긴급 | **14:41:00** 평가 (14:40 분 1/42 = 2.4%) | acc:18918, 1.2 표 |
| nginx `upstream timed out` · `no live upstreams` | 1건 이상 → 긴급 | **14:41:06** | err:6 |

- 사용자 첫 실패(14:40:52)보다 **3분 앞서** leak · slow query · 리포트 지연 경보가 울렸을 것이다.
- "풀 사용률 100% 가 30초 지속" 같은 규칙은 이번에 **울리지 않았을 것**이다. 5/5 포화는 60번 끊겨 나타났고(합계 219초) 30초 넘게 이어진 구간이 없었다. 지속 시간보다 대기 스레드 · 타임아웃 건수에 경보를 거는 것이 맞다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남길 로그 |
|---|---|---|
| 14:37 부터 리포트 1건의 점유 시간이 10초 미만 → 15초 이상으로 늘어난 이유 | 서명 시간은 코드상 데이터와 무관하게 일정하다. 호스트 자원 지표가 없다 | 앱 호스트 CPU 사용률 · steal, JVM GC 로그(일시 정지 시간) |
| 점유 시간 중 SQL 과 서명이 차지한 비율 | 단계별 시각이 로그에 없다 | 리포트 단계별 소요 시간 로그(조회 종료 · `sign` 시작 · 종료) 또는 APM 트레이스 |
| 9.8초 쿼리가 1,284,310행을 검사한 이유(인덱스 미사용 여부) | 운영 `EXPLAIN` · 인덱스 상태를 볼 수 없다 | DBA 의 `EXPLAIN` · `SHOW INDEX FROM submission` · 테이블 통계 결과 |
| slow log 가 모든 느린 쿼리를 남겼는지 | `long_query_time` · `log_slow_rate_limit` 설정을 모른다(임계 ≤ 2.04초로만 추정) | 운영 MariaDB slow log 설정값 기록 |
| `API-2` 가 평소에 요청을 처리했는지, 언제부터 연결을 거부했는지 | access log 에 upstream 주소가 없고, app.log 에 인스턴스 표기가 없다 | nginx access log 에 `$upstream_addr` · `$request_time` · `$upstream_response_time`, app log 에 호스트 · 인스턴스 ID, 인스턴스 재시작 · 배포 이벤트 |
| 504 기준(nginx 대기 한도)과 upstream 제외 조건 | nginx 설정이 저장소에 없다 | nginx 설정(`proxy_read_timeout`, `upstream` 의 `max_fails` · `fail_timeout`)의 버전 관리 |
| 교사 화면의 반복 요청이 수동 새로 고침인지 자동 갱신인지 | 클라이언트 쪽 로그가 없다 | 프런트엔드 요청 출처(화면 · 트리거) 로그 |
| 장애 당시 배포본이 지금 저장소 코드와 같은지 | 스택 줄번호 일치로만 추정했다 | 앱 시작 로그에 빌드 버전 · 커밋 해시 |

---

마스킹 적용: IP 67건(서버 역할 별칭 API-1 · API-2, 교사단말A ~ D, 그 밖은 10.20.x.x) · 이메일 0건 · 학생 식별자 2건(학생A · 학생B) · 토큰 · 세션 ID · API 키 · Authorization 0건 · 비밀번호 · 접속 문자열 1건
