package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ItemSearchServiceTest {

    @Mock
    private ItemSearchRepository itemSearchRepository;

    @InjectMocks
    private ItemSearchService itemSearchService;

    private final ItemSearchRow row = new ItemSearchRow(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"));

    @Test
    @DisplayName("search: 0건이면 빈 행 · 건수 0 · 안내 문구, 페이지 조회는 하지 않는다")
    void returnsNoResultMessageWhenEmpty() {
        when(itemSearchRepository.count(any())).thenReturn(0L);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("unit", "Z9-99");

        ItemSearchResponse response = itemSearchService.search(params);

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isZero();
        assertThat(response.message()).isEqualTo("검색 결과가 없습니다");
        verify(itemSearchRepository, never()).findPage(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("search: 결과가 있으면 문구는 null, 페이지 번호로 offset 을 계산한다")
    void computesOffsetFromPage() {
        when(itemSearchRepository.count(any())).thenReturn(45L);
        when(itemSearchRepository.findPage(any(), eq(40), eq(20))).thenReturn(List.of(row));
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "3");

        ItemSearchResponse response = itemSearchService.search(params);

        assertThat(response.rows()).containsExactly(row);
        assertThat(response.count()).isEqualTo(45L);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 총 페이지를 넘는 페이지는 빈 행 + 전체 건수, 문구 없음")
    void keepsCountForPageBeyondLast() {
        when(itemSearchRepository.count(any())).thenReturn(20L);
        when(itemSearchRepository.findPage(any(), eq(20), eq(20))).thenReturn(List.of());
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "2");

        ItemSearchResponse response = itemSearchService.search(params);

        assertThat(response.rows()).isEmpty();
        assertThat(response.count()).isEqualTo(20L);
        assertThat(response.message()).isNull();
    }

    @Test
    @DisplayName("search: 해석한 조건(배열 파라미터는 첫 값)으로 저장소에 묻는다")
    void passesParsedConditionToRepository() {
        when(itemSearchRepository.count(any())).thenReturn(1L);
        when(itemSearchRepository.findPage(any(), eq(0), eq(20))).thenReturn(List.of(row));
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("tag[]", "도형");
        params.add("tag[]", "계산");
        params.add("level", "3.7");

        itemSearchService.search(params);

        ArgumentCaptor<ItemSearchCondition> captor = ArgumentCaptor.forClass(ItemSearchCondition.class);
        verify(itemSearchRepository).count(captor.capture());
        assertThat(captor.getValue().tag()).isEqualTo("도형");
        assertThat(captor.getValue().level()).isEqualTo(3L);
    }
}
