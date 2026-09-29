package com.example.item;

import java.util.List;

/**
 * {@code GET /api/items/search} 응답.
 * {@code count} 는 전체 건수(현재 페이지 행 수가 아님), {@code message} 는 0건일 때만 안내 문구이고 그 밖에는 {@code null}.
 */
public record ItemSearchResponse(List<ItemSearchRow> rows, long count, String message) {
}
