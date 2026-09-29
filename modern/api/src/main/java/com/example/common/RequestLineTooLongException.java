package com.example.common;

/** 요청 줄이 너무 길 때 던지는 예외. {@link GlobalExceptionHandler}가 414로 변환한다. */
public class RequestLineTooLongException extends RuntimeException {

    public RequestLineTooLongException(String message) {
        super(message);
    }
}
