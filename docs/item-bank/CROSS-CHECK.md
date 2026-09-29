# item-bank-php 비즈니스 규칙 (코드 기준 교차 검증용)

- 대상: `legacy/item-bank-php/` 의 `search.php`, `register.php`, `units.php` (보조로 `index.php`, `inc/layout.php`, `inc/db.php`)
- 범위: 문항 검색의 조건 조합(단원 · 난이도 · 태그), 등록 시 검증, 목록 기본 정렬 · 제외 조건
- 방법: 위 PHP 코드만 읽었다. `docs/`, `vendor/`, DB 스키마(`db/`)는 읽지 않았다. 주석과 코드가 다르면 코드를 기준으로 썼다.
- 경로는 모두 `legacy/item-bank-php/` 기준이다.

## 1. 검색 — 조건 조합

### CX-01 검색 조건은 모두 AND 로 결합한다
키워드 · 단원 · 난이도 · 태그 조건은 `WHERE 1=1` 뒤에 `AND` 로 이어 붙는다. 각 조건은 값이 있을 때만 추가된다(난이도는 CX-04 참고). 같은 이름 파라미터가 배열로 오면 첫 값만 쓴다.
- 근거: `search.php:44`, `search.php:98`, `search.php:118`, `search.php:55`
```php
$where   = " WHERE 1=1";
$where .= " AND unit_code = ?";
$q = is_array($params['q']) ? (string)reset($params['q']) : (string)$params['q'];
```

### CX-02 키워드는 제목 또는 지문의 부분 일치다
앞뒤 공백을 제거한 뒤, 비어 있지 않으면 `title` 또는 `stem` 에 `LIKE %키워드%` 로 찾는다. `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작한다(경고만 표시).
- 근거: `search.php:85`, `search.php:97-98`
```php
$q = trim($q);
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-03 키워드는 100자까지만 사용한다
100자를 넘으면 앞 100자로 자르고 경고를 남긴다(오류로 거절하지 않는다).
- 근거: `search.php:86-89`
```php
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
    $warnings[] = '키워드가 너무 길어 100자까지만 사용했습니다.';
```

### CX-04 단원은 코드 정확 일치이며, 형식이 틀려도 그대로 조회한다
`unit_code = ?` 로 정확히 비교한다. 형식(`M5-1` 꼴) 위반, 소문자, 미등록 코드는 경고만 하고 입력값 그대로 조회한다(대개 0건). 대소문자 변환은 하지 않는다.
- 근거: `search.php:111-120`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
$where .= " AND unit_code = ?";
```

### CX-05 난이도를 비우면 5 등급 문항은 제외된다 (주석과 코드가 다름)
난이도가 비어 있으면 `level < 5` 조건이 붙어 난이도 1~4 만 나온다. 코드 주석은 "전체 난이도 검색 (1~5 모두 포함)" 이라 하지만 실제 코드는 5 를 뺀다. 난이도 5 문항은 `level=5` 를 명시해야만 나온다.
- 근거: `search.php:213-216`
```php
// 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
if ($level == '') {
    $where .= " AND level < 5";
```

### CX-06 난이도는 1~5 정수 정확 일치이며, 범위 밖 값도 정수로 바꿔 조회한다
`1`~`5` 한 자리면 `level = ?` 로 비교한다. 그 밖의 값(0, 6, 문자열 등)은 경고 후 `(int)` 변환한 값으로 `level = ?` 조회한다(대개 0건, 문자열은 0).
- 근거: `search.php:217-222`, `search.php:223-229`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
$warnings[] = '난이도는 1~5 사이여야 합니다.';  $values[] = (int)$level;
```

### CX-07 태그는 이름 정확 일치이며 문항당 하나의 태그만 필터한다
`item_tag` · `tag` 에 대한 `EXISTS` 서브쿼리로 `t.name = ?` 를 비교한다(부분 일치 없음). 태그는 한 개만 받는다. 미등록 태그는 경고만 하고 조회한다(0건).
- 근거: `search.php:244-245`, `search.php:258-260`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-08 태그 이름은 50자까지만 사용한다
50자를 넘으면 앞 50자로 자르고 경고한다.
- 근거: `search.php:240-243`
```php
if (mb_strlen($tag, 'UTF-8') > 50) {
    $tag = mb_substr($tag, 0, 50, 'UTF-8');
```

### CX-09 잘못된 검색 입력은 거절하지 않고 경고 후 조회한다
검색 화면의 입력 검증은 차단이 아니라 경고다(키워드 길이 · 한 글자, 단원 형식 · 미등록, 난이도 범위, 태그 미등록, 정렬 · 페이지 오류). 쿼리는 항상 실행된다.
- 근거: `search.php:112-113`, `search.php:225`, `search.php:259`
```php
$warnings[] = '단원 코드 형식이 올바르지 않습니다. (예: M5-1)';
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$warnings[] = '등록되지 않은 태그입니다: ' . $tag;
```

## 2. 목록 — 제외 조건 · 기본 정렬 · 페이지

### CX-10 검색은 공개 문항 뷰(`v_item_public`)에서만 조회한다
검색과 건수 조회 모두 `v_item_public` 을 `FROM` 으로 쓴다. 뷰의 정의(어떤 행을 거르는지)는 이 폴더 코드에 없어서 여기서는 확인하지 않았다. 폴더 안 근거로 확인되는 것은, 등록 직후 문항이 `R` 상태이고(CX-17) 단원별 개수는 `status = 'A'` 만 센다는 점이다(CX-11).
- 근거: `search.php:522`, `search.php:526`
```php
$from   = " FROM v_item_public";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

### CX-11 단원 목록의 문항 수는 status = 'A' 문항만 센다
검수중 · 삭제 문항은 세지 않는다.
- 근거: `units.php:15`, `units.php:17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

### CX-12 기본 정렬은 난이도 내림차순, 같으면 id 오름차순이다
`sort` 가 비어 있으면 어려운 문항부터, 같은 난이도면 번호 순이다.
- 근거: `search.php:317-319`
```php
case '':
    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-13 정렬 기준은 5개이고, 각각 동률 처리(2차 키)가 정해져 있다
`id`(id), `title`(title, id ASC), `unit`(unit_code, level DESC, id ASC), `level`(level, id ASC), `created`(created_at, id ASC). 방향을 생략하거나 잘못 주면 `level` · `created` 는 내림차순, 나머지는 오름차순이다. `dir` 이 `asc`/`desc` 가 아니면 경고한다. `unit` 정렬은 방향과 무관하게 2차 키 level 이 항상 DESC 다.
- 근거: `search.php:266-273`, `search.php:293-299`, `search.php:301-307`, `search.php:309-315`
```php
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
if ($dir === 'asc') { $orderBy = " ORDER BY level ASC, id ASC"; } else { ... " ORDER BY level DESC, id ASC"
```

### CX-14 알 수 없는 정렬 기준은 기본 정렬(CX-12)로 대체한다
경고를 남기고 `sort` 를 비운 뒤 `level DESC, id ASC` 를 쓴다.
- 근거: `search.php:322-326`
```php
default:
    $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-15 한 페이지는 20건이고 페이지 번호는 1~999 로 보정한다
숫자가 아니면 1 페이지, 1 미만은 1, 999 초과는 999 로 바꾸고 경고한다. `offset = (page-1)*20`.
- 근거: `search.php:337-349`, `search.php:523`
```php
if ($page > 999) { $page = 999; ...
$offset = ($page - 1) * 20;
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
```

## 3. 등록 — 검증 (`register.php`)

### CX-16 제목은 앞뒤 공백 제거 후 5~200자다
`trim` 한 값의 글자 수로 판단한다(내부 공백은 글자 수에 포함). 5 미만 또는 200 초과면 각각 오류다.
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-17 지문 · 단원 · 난이도는 필수이며, 난이도는 1~5 한 자리 숫자다
지문은 trim 후 빈 문자열이면 오류. 단원은 `unit` 테이블에 존재하는 id 여야 한다. 난이도는 `/^[1-5]$/` 만 허용한다. 오류가 하나라도 있으면 저장하지 않는다(검색과 달리 차단).
- 근거: `register.php:56`, `register.php:61-67`, `register.php:70`
```php
if ($stem === '') {
if ((string)$u['id'] === $unitId) { $unitOk = true; }
if (!preg_match('/^[1-5]$/', $level)) {
```

### CX-18 태그는 선택이며, 목록에 없는 태그 id 는 오류 없이 버린다
태그 목록(`tag` 테이블)에 있는 id 만 저장 대상이 된다. 없는 id 는 오류 없이 무시한다.
- 근거: `register.php:74-81`
```php
foreach ($tags as $t) {
    if ((string)$t['id'] === (string)$tid) {
        $validTagIds[] = (int)$tid;
```

### CX-19 새 문항은 검수중(`R`) 상태로 저장되어 검색에 나오지 않는다
등록 시 `status` 는 항상 `'R'` 이다. 주석은 "검수 완료 전까지 검색 화면에 나오지 않는다" 고 하며, 검색이 조회하는 `v_item_public` 정의는 확인하지 않았다(CX-10).
- 근거: `register.php:84`, `register.php:93-94`
```php
// 등록 직후에는 검수중(R) 상태로 들어간다 → 검수 완료 전까지 검색 화면에 나오지 않는다.
VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
```

### CX-20 새 문항 id 는 `MAX(id)+1` 이며 문항 · 태그 저장은 한 트랜잭션이다
`FOR UPDATE` 로 최댓값을 읽어 1 을 더한다. 문항 또는 태그 INSERT 가 실패하면 롤백하고 "저장 중 오류" 를 표시한다.
- 근거: `register.php:85-87`, `register.php:119-122`
```php
$conn->begin_transaction();
$res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
$conn->rollback();
```

## 4. 확인하지 못한 것

- **수정 검증**: 폴더에서 수정 화면을 찾지 못했다. 메뉴에는 검색 · 등록 · 단원 목록 3개뿐이며(`index.php:9-11`, `inc/layout.php:10-12`), `edit.php` · `update.php` 등 추측한 이름의 파일도 없었다. 그래서 수정 규칙은 쓰지 않았다. 다만 이 확인은 Bash 오류로 파일 목록을 뽑지 못하고 파일명을 추측해 읽은 것이라, 다른 이름의 수정 파일이 있을 가능성은 배제하지 못했다.
- **`v_item_public` 의 제외 조건**: 뷰 정의가 이 폴더 밖(DB 스키마)에 있어 읽지 않았다(CX-10, CX-19).
- 위 파일 외에 `search.php` 가 `require` 하는 `vendor/simplelog/Log.php` 는 외부 라이브러리라 읽지 않았다.
