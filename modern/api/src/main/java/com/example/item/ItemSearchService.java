package com.example.item;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 서비스 — 레거시 {@code search.php} 이관. 파라미터 해석은 {@link ItemSearchCondition} 이 맡는다.
 * 없는 단원 · 범위 밖 난이도도 오류가 아니라 0건으로 응답한다(BR-09, BR-12).
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    /** 한 페이지 문항 수(BR-22). */
    static final int PAGE_SIZE = 20;
    /** 0건일 때의 안내 문구(BR-24). {@code search.php:658} */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private final ItemSearchRepository itemSearchRepository;

    public ItemSearchService(ItemSearchRepository itemSearchRepository) {
        this.itemSearchRepository = itemSearchRepository;
    }

    /** 레거시와 같은 이름의 쿼리 파라미터(q, unit, level, tag, sort, dir, page 와 그 배열형 key[])로 검색한다. */
    public ItemSearchResponse search(MultiValueMap<String, String> params) {
        ItemSearchCondition condition = ItemSearchCondition.from(params);

        long count = itemSearchRepository.count(condition);
        if (count == 0) {
            // BR-24: 0건이면 표 대신 안내 문구만
            return new ItemSearchResponse(List.of(), 0, NO_RESULT_MESSAGE);
        }

        // 총 페이지를 넘는 페이지는 보정하지 않는다 — 빈 행 + 전체 건수, 문구 없음(BR-23)
        int offset = (condition.page() - 1) * PAGE_SIZE;
        List<ItemSearchRow> rows = itemSearchRepository.findPage(condition, offset, PAGE_SIZE);
        log.debug("item search: page {}, {} of {} rows", condition.page(), rows.size(), count);
        return new ItemSearchResponse(rows, count, null);
    }
}
