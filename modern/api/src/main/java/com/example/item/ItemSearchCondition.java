package com.example.item;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. 레거시 {@code search.php} 의 {@code buildSearchQuery} 가 파라미터를 해석하던 방식을 그대로 옮겼다.
 * 규칙 ID(BR-xx)는 {@code docs/item-bank/BUSINESS-RULES.md} 를 따른다.
 *
 * @param keyword   키워드. 빈 문자열이면 조건 없음
 * @param unitCode  단원 코드. 빈 문자열이면 조건 없음
 * @param level     난이도. {@code null} 이면 {@code level < 5}(BR-10)
 * @param tag       태그 이름. 빈 문자열이면 조건 없음
 * @param sort      정렬 기준
 * @param direction {@code asc} · {@code desc} · 빈 문자열(기준별 기본 방향)
 * @param page      1~999 로 보정한 페이지 번호
 */
public record ItemSearchCondition(
    String keyword,
    String unitCode,
    Long level,
    String tag,
    ItemSearchSort sort,
    String direction,
    int page) {

    /** 키워드 최대 글자 수(BR-05). */
    static final int KEYWORD_MAX_LENGTH = 100;
    /** 태그 이름 최대 글자 수(BR-15). {@code tag.name VARCHAR(50)} 과 같은 값. */
    static final int TAG_MAX_LENGTH = 50;
    /** 난이도를 비웠을 때 이 값 미만만 찾는다(BR-10). */
    static final int DEFAULT_LEVEL_CEILING = 5;
    /** 최대 페이지 번호(BR-23). */
    static final int MAX_PAGE = 999;

    static final String ASC = "asc";
    static final String DESC = "desc";

    /** 난이도 1~5 한 자리(BR-11). {@code search.php:217} */
    private static final Pattern LEVEL_PATTERN = Pattern.compile("[1-5]");
    /** PCRE {@code /^[0-9]+$/} — {@code $} 가 끝의 개행 하나를 허용한다. {@code search.php:337} */
    private static final Pattern PAGE_PATTERN = Pattern.compile("[0-9]+\n?");
    /** PHP 의 문자열 → 정수 변환이 읽는 앞부분 숫자(선행 공백 · 부호 · 소수 · 지수). */
    private static final Pattern PHP_NUMERIC_PREFIX =
        Pattern.compile("^[ \t\n\r\u000B\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern PHP_INTEGER = Pattern.compile("[+-]?[0-9]+");
    /** PHP {@code trim()} 기본 대상 문자: 공백 · 탭 · 개행 · 복귀 · NUL · 수직 탭. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";

    /** 쿼리 파라미터 → 검색 조건. */
    public static ItemSearchCondition from(MultiValueMap<String, String> params) {
        // 키워드(BR-04 ~ BR-07): trim → 100자 자르기
        String keyword = truncate(phpTrim(param(params, "q", "")), KEYWORD_MAX_LENGTH);

        // 단원(BR-08, BR-09): 형식 · 대소문자 · 존재 여부와 관계없이 입력값 그대로
        String unitCode = phpTrim(param(params, "unit", ""));

        // 난이도(BR-10 ~ BR-12)
        String levelRaw = phpTrim(param(params, "level", ""));
        Long level;
        if (levelRaw.isEmpty()) {
            level = null;
        } else if (LEVEL_PATTERN.matcher(levelRaw).matches()) {
            level = Long.valueOf(levelRaw);
        } else {
            level = phpIntval(levelRaw);
        }

        // 태그(BR-13 ~ BR-15): trim → 50자 자르기. % · _ 는 문자 그대로
        String tag = phpTrim(param(params, "tag", ""));
        if (!tag.isEmpty()) {
            tag = truncate(tag, TAG_MAX_LENGTH);
        }

        // 정렬(BR-16 ~ BR-21): 모르는 기준은 기본 정렬, asc · desc 외의 방향은 미지정
        ItemSearchSort sort = ItemSearchSort.from(asciiLower(phpTrim(param(params, "sort", ""))));
        String direction = asciiLower(phpTrim(param(params, "dir", "")));
        if (!direction.equals(ASC) && !direction.equals(DESC)) {
            direction = "";
        }

        // 페이지(BR-23): 숫자만이면 정수로, 아니면 1. 1 미만은 1, 999 초과는 999
        String pageRaw = param(params, "page", "1");
        long page = 1;
        if (PAGE_PATTERN.matcher(pageRaw).matches()) {
            page = phpIntval(pageRaw);
        }
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            page = MAX_PAGE;
        }

        return new ItemSearchCondition(keyword, unitCode, level, tag, sort, direction, (int) page);
    }

    boolean hasKeyword() {
        return !keyword.isEmpty();
    }

    boolean hasUnitCode() {
        return !unitCode.isEmpty();
    }

    boolean hasTag() {
        return !tag.isEmpty();
    }

    /** {@code ORDER BY} 뒤에 올 정렬 절. */
    String orderBy() {
        return sort.orderBy(direction);
    }

    /**
     * PHP {@code $_GET} 처럼 값을 꺼낸다(BR-03).
     * {@code key[]=a&key[]=b} 는 배열이므로 첫 값, 같은 {@code key} 가 여러 번이면 PHP 처럼 마지막 값.
     */
    static String param(MultiValueMap<String, String> params, String key, String defaultValue) {
        List<String> arrayValues = params.get(key + "[]");
        if (arrayValues != null && !arrayValues.isEmpty()) {
            return arrayValues.get(0);
        }
        List<String> values = params.get(key);
        if (values != null && !values.isEmpty()) {
            return values.get(values.size() - 1);
        }
        return defaultValue;
    }

    /** PHP {@code trim()} — Java {@code trim()} 과 지우는 문자가 다르다. */
    static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** {@code mb_substr($s, 0, max, 'UTF-8')} — 코드포인트 기준으로 자른다. */
    static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    /** PHP 7.4 {@code (int)$s} — 앞부분 숫자만 읽고({@code 3.7 → 3}, {@code 3abc → 3}, {@code abc → 0}) 범위를 넘으면 포화. */
    static long phpIntval(String value) {
        Matcher matcher = PHP_NUMERIC_PREFIX.matcher(value);
        if (!matcher.find()) {
            return 0;
        }
        String number = matcher.group(1);
        if (PHP_INTEGER.matcher(number).matches()) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException overflow) {
                return number.startsWith("-") ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }
        double parsed = Double.parseDouble(number);
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return 0;
        }
        // 범위 밖이면 (long) 캐스트가 Long.MAX_VALUE · Long.MIN_VALUE 로 포화한다
        return (long) parsed;
    }

    /** PHP {@code strtolower()} — ASCII 만 바꾼다. */
    private static String asciiLower(String value) {
        StringBuilder lowered = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            lowered.append(c >= 'A' && c <= 'Z' ? Character.toLowerCase(c) : c);
        }
        return lowered.toString();
    }
}
