package com.example.item;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 문항 검색 저장소. 레거시 {@code search.php} 의 SQL 을 그대로 옮기기 위해 네이티브 SQL 을 쓴다.
 * 공개 문항 뷰 {@code v_item_public} 은 테스트 DB(H2)에 없으므로 뷰 정의({@code 01-schema.sql:52-70})를
 * 기본 테이블 JOIN 으로 풀어 쓴다. 비교 · LIKE 는 DB 콜레이션을 그대로 따른다(ESCAPE 절 없음 — BR-06).
 */
@Repository
public class ItemSearchRepository {

    /** 공개 문항만(BR-01). 조건은 모두 AND 로 붙인다(BR-02). */
    private static final String FROM_PUBLIC_ITEMS =
        " FROM item i JOIN unit u ON u.id = i.unit_id WHERE i.status = :status";

    @PersistenceContext
    private EntityManager entityManager;

    /** 조건에 맞는 전체 건수. */
    public long count(ItemSearchCondition condition) {
        Query query = entityManager.createNativeQuery("SELECT COUNT(*)" + FROM_PUBLIC_ITEMS + where(condition));
        bind(query, condition);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** 현재 페이지 행. 태그는 태그 id 순으로 채운다(BR-34). */
    public List<ItemSearchRow> findPage(ItemSearchCondition condition, int offset, int limit) {
        Query query = entityManager.createNativeQuery(
            "SELECT i.id, i.title, u.code, i.level" + FROM_PUBLIC_ITEMS + where(condition)
                + " ORDER BY " + condition.orderBy());
        bind(query, condition);
        query.setFirstResult(offset);
        query.setMaxResults(limit);

        List<?> results = query.getResultList();
        List<Integer> ids = new ArrayList<>(results.size());
        for (Object result : results) {
            ids.add(((Number) ((Object[]) result)[0]).intValue());
        }
        Map<Integer, List<String>> tagNames = findTagNames(ids);

        List<ItemSearchRow> rows = new ArrayList<>(results.size());
        for (Object result : results) {
            Object[] columns = (Object[]) result;
            Integer id = ((Number) columns[0]).intValue();
            rows.add(new ItemSearchRow(
                id,
                (String) columns[1],
                (String) columns[2],
                ((Number) columns[3]).intValue(),
                tagNames.getOrDefault(id, List.of())));
        }
        return rows;
    }

    /** 문항 id → 태그 이름 목록(태그 id 순). */
    private Map<Integer, List<String>> findTagNames(List<Integer> itemIds) {
        Map<Integer, List<String>> tagNames = new LinkedHashMap<>();
        if (itemIds.isEmpty()) {
            return tagNames;
        }
        List<?> results = entityManager.createNativeQuery(
                "SELECT it.item_id, t.name FROM item_tag it JOIN tag t ON t.id = it.tag_id"
                    + " WHERE it.item_id IN (:itemIds) ORDER BY it.item_id ASC, t.id ASC")
            .setParameter("itemIds", itemIds)
            .getResultList();
        for (Object result : results) {
            Object[] columns = (Object[]) result;
            tagNames.computeIfAbsent(((Number) columns[0]).intValue(), id -> new ArrayList<>())
                .add((String) columns[1]);
        }
        return tagNames;
    }

    private static String where(ItemSearchCondition condition) {
        StringBuilder where = new StringBuilder();
        if (condition.hasKeyword()) {
            // BR-04: 제목 또는 지문 부분 일치. % · _ 는 와일드카드로 그대로 둔다(BR-06)
            where.append(" AND (i.title LIKE :keyword OR i.stem LIKE :keyword)");
        }
        if (condition.hasUnitCode()) {
            // BR-08: 단원 코드 정확 일치(대소문자 구분은 DB 콜레이션)
            where.append(" AND u.code = :unitCode");
        }
        if (condition.level() == null) {
            // BR-10: 난이도를 비우면 5 를 뺀다
            where.append(" AND i.level < :levelCeiling");
        } else {
            // BR-11, BR-12: 1~5 든 정수 변환 값이든 같은 값만
            where.append(" AND i.level = :level");
        }
        if (condition.hasTag()) {
            // BR-13, BR-14: 태그 이름 정확 일치
            where.append(" AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id")
                .append(" WHERE it.item_id = i.id AND t.name = :tag)");
        }
        return where.toString();
    }

    private static void bind(Query query, ItemSearchCondition condition) {
        query.setParameter("status", ItemStatus.ACTIVE);
        if (condition.hasKeyword()) {
            query.setParameter("keyword", "%" + condition.keyword() + "%");
        }
        if (condition.hasUnitCode()) {
            query.setParameter("unitCode", condition.unitCode());
        }
        if (condition.level() == null) {
            query.setParameter("levelCeiling", ItemSearchCondition.DEFAULT_LEVEL_CEILING);
        } else {
            query.setParameter("level", condition.level());
        }
        if (condition.hasTag()) {
            query.setParameter("tag", condition.tag());
        }
    }
}
