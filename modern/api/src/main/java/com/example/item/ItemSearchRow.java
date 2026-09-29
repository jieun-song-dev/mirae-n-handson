package com.example.item;

import java.util.List;

/**
 * 문항 검색 결과 한 행. 레거시 결과 표의 열({@code id, title, unit, level, tags})만 둔다.
 * {@code unit} 은 단원 코드, {@code tags} 는 태그 이름 목록(태그 id 순, 없으면 빈 목록).
 */
public record ItemSearchRow(Integer id, String title, String unit, Integer level, List<String> tags) {
}
