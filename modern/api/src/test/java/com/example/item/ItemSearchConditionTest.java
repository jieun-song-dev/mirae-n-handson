package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** 검색 파라미터 해석 — 레거시 search.php 의 buildSearchQuery 와 같은 결과를 내는지. */
@ActiveProfiles("test")
class ItemSearchConditionTest {

    private static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    @Test
    @DisplayName("파라미터가 없으면 조건 없음 · 난이도 5 제외 · 기본 정렬 · 1페이지")
    void defaultsWhenNoParams() {
        ItemSearchCondition condition = ItemSearchCondition.from(params());

        assertThat(condition.hasKeyword()).isFalse();
        assertThat(condition.hasUnitCode()).isFalse();
        assertThat(condition.hasTag()).isFalse();
        assertThat(condition.level()).isNull();
        assertThat(condition.sort()).isEqualTo(ItemSearchSort.DEFAULT);
        assertThat(condition.orderBy()).isEqualTo("i.level DESC, i.id ASC");
        assertThat(condition.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("키워드는 trim 뒤 100자까지 쓰고 101자째부터 자른다(코드포인트 기준)")
    void truncatesKeywordToHundredCodePoints() {
        String exact = "%".repeat(99) + "#";
        String over = "%".repeat(100) + "#";
        String emoji = "😀".repeat(101);

        assertThat(ItemSearchCondition.from(params("q", exact)).keyword()).isEqualTo(exact);
        assertThat(ItemSearchCondition.from(params("q", over)).keyword()).isEqualTo("%".repeat(100));
        assertThat(ItemSearchCondition.from(params("q", emoji)).keyword()).isEqualTo("😀".repeat(100));
        assertThat(ItemSearchCondition.from(params("q", "  분수 ")).keyword()).isEqualTo("분수");
    }

    @Test
    @DisplayName("공백만 있는 키워드 · 단원 · 태그는 조건이 없다")
    void blankValuesMeanNoCondition() {
        ItemSearchCondition condition = ItemSearchCondition.from(params("q", "   ", "unit", " ", "tag", "\t"));

        assertThat(condition.hasKeyword()).isFalse();
        assertThat(condition.hasUnitCode()).isFalse();
        assertThat(condition.hasTag()).isFalse();
    }

    @Test
    @DisplayName("PHP trim 은 전각 공백을 지우지 않는다")
    void phpTrimKeepsIdeographicSpace() {
        assertThat(ItemSearchCondition.phpTrim("　분수 \n")).isEqualTo("　분수");
    }

    @Test
    @DisplayName("단원 코드는 형식 · 대소문자와 관계없이 입력값 그대로 쓴다")
    void keepsUnitCodeAsIs() {
        assertThat(ItemSearchCondition.from(params("unit", " m5-1 ")).unitCode()).isEqualTo("m5-1");
        assertThat(ItemSearchCondition.from(params("unit", "없는단원")).unitCode()).isEqualTo("없는단원");
    }

    @Test
    @DisplayName("난이도: 1~5 는 그 값, 그 밖은 PHP (int) 변환 값")
    void parsesLevelLikePhp() {
        assertThat(ItemSearchCondition.from(params("level", "5")).level()).isEqualTo(5L);
        assertThat(ItemSearchCondition.from(params("level", "6")).level()).isEqualTo(6L);
        assertThat(ItemSearchCondition.from(params("level", "3.7")).level()).isEqualTo(3L);
        assertThat(ItemSearchCondition.from(params("level", "03")).level()).isEqualTo(3L);
        assertThat(ItemSearchCondition.from(params("level", "3abc")).level()).isEqualTo(3L);
        assertThat(ItemSearchCondition.from(params("level", "abc")).level()).isEqualTo(0L);
        assertThat(ItemSearchCondition.from(params("level", "-2")).level()).isEqualTo(-2L);
        assertThat(ItemSearchCondition.from(params("level", "")).level()).isNull();
    }

    @Test
    @DisplayName("PHP (int) 변환: 지수 표기 · 범위 초과 포화 · 무한대는 0")
    void phpIntvalHandlesEdgeCases() {
        assertThat(ItemSearchCondition.phpIntval("1e2")).isEqualTo(100L);
        assertThat(ItemSearchCondition.phpIntval("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntval("1e999")).isEqualTo(0L);
        assertThat(ItemSearchCondition.phpIntval("0x1A")).isEqualTo(0L);
        assertThat(ItemSearchCondition.phpIntval(".9")).isEqualTo(0L);
    }

    @Test
    @DisplayName("태그는 trim 뒤 50자까지 쓰고 % 는 문자 그대로 둔다")
    void truncatesTagToFiftyCharacters() {
        assertThat(ItemSearchCondition.from(params("tag", "a".repeat(300))).tag()).isEqualTo("a".repeat(50));
        assertThat(ItemSearchCondition.from(params("tag", "계%")).tag()).isEqualTo("계%");
    }

    @Test
    @DisplayName("정렬: 방향이 없으면 level · created 만 내림차순, 동률은 id 오름차순")
    void resolvesSortDirection() {
        assertThat(ItemSearchCondition.from(params("sort", "id")).orderBy()).isEqualTo("i.id ASC");
        assertThat(ItemSearchCondition.from(params("sort", "id", "dir", "desc")).orderBy()).isEqualTo("i.id DESC");
        assertThat(ItemSearchCondition.from(params("sort", "title", "dir", "DESC")).orderBy())
            .isEqualTo("i.title DESC, i.id ASC");
        assertThat(ItemSearchCondition.from(params("sort", "unit", "dir", "desc")).orderBy())
            .isEqualTo("u.code DESC, i.level DESC, i.id ASC");
        assertThat(ItemSearchCondition.from(params("sort", "level")).orderBy()).isEqualTo("i.level DESC, i.id ASC");
        assertThat(ItemSearchCondition.from(params("sort", " Created ")).orderBy())
            .isEqualTo("i.created_at DESC, i.id ASC");
        assertThat(ItemSearchCondition.from(params("sort", "created", "dir", "asc")).orderBy())
            .isEqualTo("i.created_at ASC, i.id ASC");
    }

    @Test
    @DisplayName("모르는 정렬 기준 · 방향은 기본 정렬과 기본 방향")
    void fallsBackToDefaultSortForUnknownValues() {
        ItemSearchCondition condition = ItemSearchCondition.from(params("sort", "price", "dir", "up"));

        assertThat(condition.sort()).isEqualTo(ItemSearchSort.DEFAULT);
        assertThat(condition.direction()).isEmpty();
        assertThat(condition.orderBy()).isEqualTo("i.level DESC, i.id ASC");
    }

    @Test
    @DisplayName("페이지: 숫자 아닌 값 · 음수 · 0 은 1, 999 초과는 999, 끝의 개행 하나는 허용")
    void clampsPageLikeLegacy() {
        assertThat(ItemSearchCondition.from(params("page", "-1")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "0")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "abc")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", " 2")).page()).isEqualTo(1);
        assertThat(ItemSearchCondition.from(params("page", "2\n")).page()).isEqualTo(2);
        assertThat(ItemSearchCondition.from(params("page", "999")).page()).isEqualTo(999);
        assertThat(ItemSearchCondition.from(params("page", "1000")).page()).isEqualTo(999);
        assertThat(ItemSearchCondition.from(params("page", "99999999999999999999")).page()).isEqualTo(999);
    }

    @Test
    @DisplayName("배열 파라미터(key[])는 첫 값, 같은 키가 반복되면 마지막 값")
    void readsPhpStyleArrayAndRepeatedParams() {
        ItemSearchCondition condition = ItemSearchCondition.from(
            params("tag[]", "도형", "tag[]", "계산", "sort[]", "title", "sort[]", "id", "unit", "M5-1", "unit", "M5-3"));

        assertThat(condition.tag()).isEqualTo("도형");
        assertThat(condition.sort()).isEqualTo(ItemSearchSort.TITLE);
        assertThat(condition.unitCode()).isEqualTo("M5-3");
    }
}
