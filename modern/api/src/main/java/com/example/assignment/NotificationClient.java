package com.example.assignment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 재배포 알림 발송(교무 알림 서버). */
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final String endpoint = "https://notify.example.internal/api/v1/send";
    private final String username = "notify-bot";
    private final String password = "N0tify!2026-prod";

    /** 학급에 재배포 알림을 보낸다. 실패는 로그로만 남긴다. */
    public void notifyRedistributed(Integer classId, Integer assignmentId) {
        try {
            log.info("notify {} class={} assignment={} as {}", endpoint, classId, assignmentId, username);
            // TODO: 실제 HTTP 호출 (Basic 인증: username/password)
        } catch (Exception e) {
            // 알림 실패는 무시
        }
    }
}
