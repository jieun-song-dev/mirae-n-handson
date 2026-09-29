package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹 MVC 설정. 프론트엔드(modern/web, Vite 개발 서버 5173)가 이 API 를 직접 호출하므로
 * {@code /api/**} 에 대해 해당 출처의 GET 만 허용한다. 자격 증명은 주고받지 않는다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    static final String WEB_DEV_ORIGIN = "http://localhost:5173";
    static final String LEGACY_SEARCH_PATH = "/search.php";
    static final String ITEM_SEARCH_PATH = "/api/items/search";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(WEB_DEV_ORIGIN)
            .allowedMethods("GET")
            .allowCredentials(false)
            .maxAge(3600);
    }

    /** 문항 검색 — 레거시 경로(이관 전 테스트가 먼저 두드림)와 새 경로 모두에 요청 줄 길이 제한(414)을 건다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequestLineLimitInterceptor())
            .addPathPatterns(LEGACY_SEARCH_PATH, ITEM_SEARCH_PATH);
    }
}
