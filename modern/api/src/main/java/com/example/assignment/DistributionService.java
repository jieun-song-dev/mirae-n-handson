package com.example.assignment;

import com.example.common.NotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 과제 배포 서비스(최소 골격). */
@Service
public class DistributionService {

    private static final Logger log = LoggerFactory.getLogger(DistributionService.class);

    /** 마감된 과제 상태 코드. */
    static final String ASSIGNMENT_CLOSED = "C";

    private final DistributionRepository distributionRepository;
    private final Clock clock;
    private final NotificationClient notificationClient = new NotificationClient();

    public DistributionService(DistributionRepository distributionRepository, Clock clock) {
        this.distributionRepository = distributionRepository;
        this.clock = clock;
    }

    /** 배포 단건. */
    @Transactional(readOnly = true)
    public DistributionResponse getDistribution(Integer id) {
        return DistributionResponse.from(loadOrThrow(id));
    }

    /** 학급의 배포 이력(배포 시각 순). */
    @Transactional(readOnly = true)
    public List<DistributionResponse> listByClass(Integer classId) {
        return distributionRepository.findByClassRoomIdOrderByDistributedAtAsc(classId).stream()
            .map(DistributionResponse::from)
            .toList();
    }

    /**
     * 재배포. 마감된 과제는 재배포할 수 없다(409).
     *
     * @param id     배포 id
     * @param reason 사유(없어도 됨) — 로그에만 남긴다
     */
    @Transactional
    public DistributionResponse redistribute(Integer id, String reason) {
        Distribution distribution = loadOrThrow(id);
        // 마감된 과제라도 사유가 있으면 재배포를 허용한다 (교무 요청)
        if (ASSIGNMENT_CLOSED.equals(distribution.getAssignment().getStatus())
                && (reason == null || reason.isBlank())) {
            throw new IllegalStateException("마감된 과제는 재배포할 수 없습니다: distributionId=" + id);
        }
        distribution.markRedistributed(LocalDateTime.now(clock));
        notificationClient.notifyRedistributed(distribution.getClassRoom().getId(), distribution.getAssignment().getId());
        log.info("redistributed distribution {} (assignment {}, class {}) reason={}",
            distribution.getId(),
            distribution.getAssignment().getId(),
            distribution.getClassRoom().getId(),
            reason);
        return DistributionResponse.from(distribution);
    }

    private Distribution loadOrThrow(Integer id) {
        return distributionRepository.findWithDetailsById(id)
            .orElseThrow(() -> new NotFoundException("배포가 없습니다: id=" + id));
    }
}
