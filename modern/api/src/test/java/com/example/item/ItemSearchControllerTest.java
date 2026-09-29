package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MultiValueMap;

/** 컨트롤러 슬라이스 — 응답 JSON 은 동작 보존 테스트의 정규화 모양 {rows, count, message} 와 같아야 한다. */
@WebMvcTest(ItemSearchController.class)
@ActiveProfiles("test")
class ItemSearchControllerTest {

    /** '분수' 1000번 — 레거시(Apache)가 414 를 돌려준 길이(동작 보존 테스트 #15a). */
    private static final String LONG_KEYWORD = "분수".repeat(1000);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ItemSearchService itemSearchService;

    @Test
    @DisplayName("GET /api/items/search → 200, rows · count · message 와 행의 다섯 필드만")
    void searchReturnsNormalizedShape() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(
            List.of(new ItemSearchRow(12, "대분수의 덧셈", "M5-1", 3, List.of("계산", "오답률높음"))), 4, null));

        mockMvc.perform(get("/api/items/search").queryParam("q", "분수"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(4))
            .andExpect(jsonPath("$.message").value(nullValue()))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andExpect(jsonPath("$.rows[0].id").value(12))
            .andExpect(jsonPath("$.rows[0].title").value("대분수의 덧셈"))
            .andExpect(jsonPath("$.rows[0].unit").value("M5-1"))
            .andExpect(jsonPath("$.rows[0].level").value(3))
            .andExpect(jsonPath("$.rows[0].tags[1]").value("오답률높음"))
            .andExpect(jsonPath("$.rows[0].stem").doesNotExist())
            .andExpect(jsonPath("$.rows[0].status").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/items/search 0건 → 200, 안내 문구(404 아님)")
    void searchWithNoResultReturns200WithMessage() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search").queryParam("unit", "Z9-99"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rows.length()").value(0))
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.message").value("검색 결과가 없습니다"));
    }

    @Test
    @DisplayName("레거시 이름의 파라미터(tag[] 포함)를 그대로 서비스에 넘긴다")
    @SuppressWarnings("unchecked")
    void passesLegacyParamsToService() throws Exception {
        when(itemSearchService.search(any())).thenReturn(new ItemSearchResponse(List.of(), 0, "검색 결과가 없습니다"));

        mockMvc.perform(get("/api/items/search")
                .queryParam("tag[]", "도형", "계산")
                .queryParam("level", ""))
            .andExpect(status().isOk());

        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(itemSearchService).search(captor.capture());
        assertThat(captor.getValue().get("tag[]")).containsExactly("도형", "계산");
        assertThat(captor.getValue().get("level")).containsExactly("");
    }

    @Test
    @DisplayName("요청 줄이 8190바이트를 넘으면 414, message 는 비어 있고 서비스는 부르지 않는다")
    void rejectsTooLongRequestLine() throws Exception {
        mockMvc.perform(get("/api/items/search").queryParam("q", LONG_KEYWORD))
            .andExpect(status().isUriTooLong())
            .andExpect(jsonPath("$.status").value(414))
            .andExpect(jsonPath("$.message").value(nullValue()));
        verify(itemSearchService, never()).search(any());
    }

    @Test
    @DisplayName("레거시 경로 /search.php 도 긴 요청 줄이면 414 (짧으면 기존대로 404)")
    void rejectsTooLongRequestLineOnLegacyPath() throws Exception {
        mockMvc.perform(get("/search.php").queryParam("q", LONG_KEYWORD))
            .andExpect(status().isUriTooLong());
        mockMvc.perform(get("/search.php").queryParam("q", "분수"))
            .andExpect(status().isNotFound());
    }
}
