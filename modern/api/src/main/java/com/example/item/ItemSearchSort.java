package com.example.item;

/**
 * 문항 검색 정렬 기준. 레거시 {@code search.php:276-327} 의 분기를 그대로 옮겼다.
 * 동률은 방향과 관계없이 항상 {@code id ASC}(BR-20), 단원 정렬 안은 {@code level DESC, id ASC}(BR-21).
 * 방향을 지정하지 않으면 {@code level} · {@code created} 만 내림차순이다(BR-19).
 */
enum ItemSearchSort {

    ID("id", "i.id ASC", "i.id DESC", false),
    TITLE("title", "i.title ASC, i.id ASC", "i.title DESC, i.id ASC", false),
    UNIT("unit", "u.code ASC, i.level DESC, i.id ASC", "u.code DESC, i.level DESC, i.id ASC", false),
    LEVEL("level", "i.level ASC, i.id ASC", "i.level DESC, i.id ASC", true),
    CREATED("created", "i.created_at ASC, i.id ASC", "i.created_at DESC, i.id ASC", true),
    /** 정렬 기준이 없거나 모르는 값일 때: 어려운 문항부터, 같으면 번호 순(BR-16, BR-17). */
    DEFAULT("", "i.level DESC, i.id ASC", "i.level DESC, i.id ASC", true);

    private final String key;
    private final String ascending;
    private final String descending;
    private final boolean descendingByDefault;

    ItemSearchSort(String key, String ascending, String descending, boolean descendingByDefault) {
        this.key = key;
        this.ascending = ascending;
        this.descending = descending;
        this.descendingByDefault = descendingByDefault;
    }

    /** 소문자 · trim 된 정렬 기준 → 값. 모르는 기준은 {@link #DEFAULT}. */
    static ItemSearchSort from(String key) {
        for (ItemSearchSort sort : values()) {
            if (sort.key.equals(key)) {
                return sort;
            }
        }
        return DEFAULT;
    }

    /** 방향({@code asc} · {@code desc} · 빈 문자열)에 맞는 정렬 절. */
    String orderBy(String direction) {
        if (ItemSearchCondition.ASC.equals(direction)) {
            return ascending;
        }
        if (ItemSearchCondition.DESC.equals(direction)) {
            return descending;
        }
        return descendingByDefault ? descending : ascending;
    }
}
