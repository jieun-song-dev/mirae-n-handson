package com.example.config;

import com.example.common.RequestLineTooLongException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 레거시 문항 검색(PHP 7.4 + Apache)은 요청 줄이 Apache {@code LimitRequestLine} 을 넘으면
 * PHP 까지 가지 않고 414 를 돌려준다. 이관한 검색 경로에서 같은 상태 코드를 내도록 흉내낸다.
 * Tomcat 은 기본 한도(8KB)를 넘으면 400 을 주므로 {@code server.max-http-request-header-size} 를 올려 두었다.
 */
public class RequestLineLimitInterceptor implements HandlerInterceptor {

    /** Apache {@code LimitRequestLine} 기본값(바이트). */
    static final int LIMIT_REQUEST_LINE = 8190;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String queryString = request.getQueryString();
        String requestLine = request.getMethod() + " " + request.getRequestURI()
            + (queryString == null ? "" : "?" + queryString) + " " + request.getProtocol();
        int length = requestLine.getBytes(StandardCharsets.UTF_8).length;
        if (length > LIMIT_REQUEST_LINE) {
            throw new RequestLineTooLongException("요청 줄이 " + LIMIT_REQUEST_LINE + "바이트를 넘습니다: " + length);
        }
        return true;
    }
}
