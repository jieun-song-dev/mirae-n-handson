package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 검색 저장소 슬라이스 — H2(MariaDB 모드)에 실제 SQL 을 날린다.
 * 콜레이션에 달린 동작(대소문자 무시 등)은 H2 와 MariaDB 가 달라 여기서 확인하지 않는다(동작 보존 테스트가 맡음).
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ItemSearchRepository.class)
@ActiveProfiles("test")
class ItemSearchRepositoryTest {

    private static final int PAGE_SIZE = 20;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ItemSearchRepository itemSearchRepository;

    private Integer addition;
    private Integer wordProblem;
    private Integer hardest;
    private Integer decimal;
    private Integer untagged;

    @BeforeEach
    void seed() {
        Unit fraction = entityManager.persist(ItemFixtures.unit(1, "M5-1", "분수의 덧셈과 뺄셈", 5));
        Unit decimalUnit = entityManager.persist(ItemFixtures.unit(3, "M5-3", "소수의 곱셈", 5));
        Tag calc = entityManager.persist(ItemFixtures.tag(1, "계산"));
        Tag word = entityManager.persist(ItemFixtures.tag(2, "문장제"));
        Tag concept = entityManager.persist(ItemFixtures.tag(3, "개념"));

        addition = entityManager.persist(
            ItemFixtures.item(null, fraction, "분모가 같은 분수의 덧셈", 2, ItemStatus.ACTIVE, concept, calc)).getId();
        wordProblem = entityManager.persist(
            ItemFixtures.item(null, fraction, "분수 문장제", 4, ItemStatus.ACTIVE, calc, word)).getId();
        entityManager.persist(ItemFixtures.item(null, fraction, "검수 중 분수", 3, ItemStatus.REVIEWING, calc));
        entityManager.persist(ItemFixtures.item(null, fraction, "삭제된 분수", 2, ItemStatus.DELETED, calc));
        hardest = entityManager.persist(
            ItemFixtures.item(null, fraction, "난이도 5 분수", 5, ItemStatus.ACTIVE, word)).getId();
        decimal = entityManager.persist(
            ItemFixtures.item(null, decimalUnit, "소수 곱셈", 4, ItemStatus.ACTIVE, calc)).getId();
        untagged = entityManager.persist(new Item(decimalUnit, "소수 곱셈 복습", "0.3 × 4 를 계산하시오.", 2,
            ItemStatus.ACTIVE, ItemFixtures.SEED_TIME.plusDays(1))).getId();
        entityManager.flush();
        entityManager.clear();
    }

    private static ItemSearchCondition condition(String... keyValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            params.add(keyValues[i], keyValues[i + 1]);
        }
        return ItemSearchCondition.from(params);
    }

    private List<Integer> ids(ItemSearchCondition condition) {
        return itemSearchRepository.findPage(condition, 0, PAGE_SIZE).stream().map(ItemSearchRow::id).toList();
    }

    @Test
    @DisplayName("조건 없음: 공개 문항만, 난이도 5 제외, level DESC · id ASC")
    void findsPublicItemsBelowLevelFiveByDefault() {
        ItemSearchCondition condition = condition();

        assertThat(itemSearchRepository.count(condition)).isEqualTo(4);
        assertThat(ids(condition)).containsExactly(wordProblem, decimal, addition, untagged);
    }

    @Test
    @DisplayName("난이도 5 를 지정하면 그 문항만 나온다")
    void findsLevelFiveOnlyWhenRequested() {
        assertThat(ids(condition("level", "5"))).containsExactly(hardest);
    }

    @Test
    @DisplayName("범위 밖 난이도 6 은 0건(오류 아님)")
    void returnsNothingForOutOfRangeLevel() {
        assertThat(itemSearchRepository.count(condition("level", "6"))).isZero();
    }

    @Test
    @DisplayName("키워드 · 단원 · 태그는 AND 로 결합한다")
    void combinesConditionsWithAnd() {
        ItemSearchCondition condition = condition("q", "분수", "unit", "M5-1", "tag", "문장제");

        assertThat(itemSearchRepository.count(condition)).isEqualTo(1);
        assertThat(ids(condition)).containsExactly(wordProblem);
    }

    @Test
    @DisplayName("키워드는 지문에서도 찾고, _ 는 와일드카드로 쓴다")
    void matchesKeywordInStemAndTreatsUnderscoreAsWildcard() {
        assertThat(ids(condition("q", "0.3 ×"))).containsExactly(untagged);
        assertThat(itemSearchRepository.count(condition("q", "_"))).isEqualTo(4);
    }

    @Test
    @DisplayName("없는 단원 · 미등록 태그 · 태그의 % 는 0건")
    void returnsNothingForUnknownUnitOrTag() {
        assertThat(itemSearchRepository.count(condition("unit", "Z9-99"))).isZero();
        assertThat(itemSearchRepository.count(condition("tag", "계%"))).isZero();
    }

    @Test
    @DisplayName("태그는 태그 id 순, 태그가 없으면 빈 목록")
    void fillsTagsInTagIdOrder() {
        List<ItemSearchRow> rows = itemSearchRepository.findPage(condition("unit", "M5-3", "sort", "id"), 0, PAGE_SIZE);

        assertThat(rows).extracting(ItemSearchRow::id).containsExactly(decimal, untagged);
        assertThat(rows.get(0).tags()).containsExactly("계산");
        assertThat(rows.get(1).tags()).isEmpty();
        assertThat(rows.get(0).unit()).isEqualTo("M5-3");
        assertThat(itemSearchRepository.findPage(condition("sort", "id"), 0, PAGE_SIZE).get(0).tags())
            .containsExactly("계산", "개념");
    }

    @Test
    @DisplayName("생성 시각 정렬: 기본 방향은 내림차순, 동률은 id 오름차순")
    void sortsByCreatedDescendingWithIdTieBreak() {
        assertThat(ids(condition("sort", "created"))).containsExactly(untagged, addition, wordProblem, decimal);
    }

    @Test
    @DisplayName("offset · limit 으로 페이지를 자르고, 범위 밖이면 빈 목록")
    void pagesWithOffsetAndLimit() {
        ItemSearchCondition condition = condition("sort", "id");

        assertThat(itemSearchRepository.findPage(condition, 1, 2)).extracting(ItemSearchRow::id)
            .containsExactly(wordProblem, decimal);
        assertThat(itemSearchRepository.findPage(condition, PAGE_SIZE, PAGE_SIZE)).isEmpty();
    }
}
