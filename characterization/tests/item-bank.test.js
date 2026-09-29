// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 모듈의 레거시 기본 주소).
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 를 따른다.
// - 레거시와 새 API 양쪽에서 같은 결과가 나와야 하므로 건너뛰기를 두지 않는다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const KEYWORD_MAX = 100; // BR-05
const TAG_MAX = 50; // BR-15

describe('item-bank · 문항 검색(search.php) — 정상', () => {
  it('#1 조건 없는 기본 검색 — 공개 · 난이도 5 제외 · 기본 정렬 · 20건 (BR-01, BR-10, BR-16, BR-22)', async () => {
    const result = await fetchNormalized(MODULE, PATH);
    expect(result).toMatchSnapshot();
  });

  it('#2 키워드 · 단원 · 태그 AND 결합 — q=분수, unit=M5-1, tag=계산 (BR-02, BR-04, BR-08, BR-13)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '분수', unit: 'M5-1', tag: '계산' });
    expect(result).toMatchSnapshot();
  });

  it('#3 생성 시각 정렬 — 전부 동률일 때 기본 방향과 id 오름차순 (BR-11, BR-19, BR-20)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '3', sort: 'created' });
    expect(result).toMatchSnapshot();
  });
});

describe('item-bank · 문항 검색(search.php) — 경계값', () => {
  // '%' 를 채워 길이를 맞춘다. 자르기 전후로 끝의 '#' 이 남느냐에 따라 결과가 달라진다(BR-05, BR-06).
  it('#4 키워드 정확히 100자 — 잘리지 않음 (BR-05)', async () => {
    const q = '%'.repeat(KEYWORD_MAX - 1) + '#';
    const result = await fetchNormalized(MODULE, PATH, { q });
    expect(result).toMatchSnapshot();
  });

  it('#5 키워드 101자 — 앞 100자로 잘림 (BR-05)', async () => {
    const q = '%'.repeat(KEYWORD_MAX) + '#';
    const result = await fetchNormalized(MODULE, PATH, { q });
    expect(result).toMatchSnapshot();
  });

  it('#6 한 글자 키워드 — q=곱 (BR-07, BR-04)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '곱' });
    expect(result).toMatchSnapshot();
  });

  it('#7 난이도 명시 5 — 기본 검색에서 빠지는 난이도 (BR-11, BR-10)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '5' });
    expect(result).toMatchSnapshot();
  });

  it('#8 난이도 범위 밖 6 (BR-12)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '6' });
    expect(result).toMatchSnapshot();
  });

  it('#9 소수 난이도 3.7 — 정수 변환 경로 (BR-12)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '3.7' });
    expect(result).toMatchSnapshot();
  });

  it('#10a 페이지 999 — 상한 그대로 (BR-23)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '5', page: '999' });
    expect(result).toMatchSnapshot();
  });

  it('#10b 페이지 1000 — 상한 보정 (BR-23)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { level: '5', page: '1000' });
    expect(result).toMatchSnapshot();
  });

  it('#11 결과가 딱 20건일 때 2페이지 — 범위 밖 페이지 (BR-22, BR-23, BR-24)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '2' });
    expect(result).toMatchSnapshot();
  });
});

describe('item-bank · 문항 검색(search.php) — 빈 값 · 누락', () => {
  it('#12 모든 파라미터 빈 문자열 (BR-10, BR-16, BR-18, BR-23)', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      q: '',
      unit: '',
      level: '',
      tag: '',
      sort: '',
      dir: '',
      page: '',
    });
    expect(result).toMatchSnapshot();
  });

  it('#13 공백만 있는 키워드 · 단원 · 태그 — trim 뒤 조건 없음 (BR-04, BR-08, BR-13)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '   ', unit: ' ', tag: ' ' });
    expect(result).toMatchSnapshot();
  });
});

describe('item-bank · 문항 검색(search.php) — 이상한 값', () => {
  it('#14a 음수 페이지 -1 (BR-23)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '-1' });
    expect(result).toMatchSnapshot();
  });

  it('#14b 0 페이지 (BR-23)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { page: '0' });
    expect(result).toMatchSnapshot();
  });

  it('#15a 아주 긴 키워드 2000자 (BR-05)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '분수'.repeat(1000) });
    expect(result).toMatchSnapshot();
  });

  it('#15b 아주 긴 태그 300자 — 50자 자르기 · 미등록 태그 (BR-15)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { tag: 'a'.repeat(TAG_MAX * 6) });
    expect(result).toMatchSnapshot();
  });

  it('#16a 없는 단원 코드 — 형식은 맞음 Z9-99 (BR-08, BR-09)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: 'Z9-99' });
    expect(result).toMatchSnapshot();
  });

  it('#16b 형식이 틀린 단원 코드 — 없는단원 (BR-09)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: '없는단원' });
    expect(result).toMatchSnapshot();
  });

  it('#16c 소문자 단원 코드 m5-1 — 대소문자 · 콜레이션 (BR-08, BR-09)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { unit: 'm5-1' });
    expect(result).toMatchSnapshot();
  });

  it('#17a 키워드 밑줄 하나 — 와일드카드로 적용 (BR-06)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { q: '_' });
    expect(result).toMatchSnapshot();
  });

  it('#17b 태그의 퍼센트 — 문자 그대로 비교 (BR-14)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { tag: '계%' });
    expect(result).toMatchSnapshot();
  });

  it('#18a 없는 정렬 기준 · 방향 — sort=price, dir=up (BR-17, BR-18)', async () => {
    const result = await fetchNormalized(MODULE, PATH, { sort: 'price', dir: 'up' });
    expect(result).toMatchSnapshot();
  });

  it('#18b 배열 파라미터 — 첫 값만 사용 (BR-03)', async () => {
    const result = await fetchNormalized(MODULE, PATH, {
      'tag[]': ['도형', '계산'],
      'sort[]': ['title', 'id'],
    });
    expect(result).toMatchSnapshot();
  });
});
