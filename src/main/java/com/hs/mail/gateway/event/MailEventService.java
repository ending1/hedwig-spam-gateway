package com.hs.mail.gateway.event;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 메일 처리 이벤트를 메모리 큐에 모았다가 주기적으로 DB에 일괄 저장한다. 메일 처리 경로에서는 큐에 넣기만 하므로
 * DB 지연/장애가 메일 흐름에 영향을 주지 않는다(큐가 가득 차면 그 이벤트만 버리고 계속 진행).
 * 보관 기간(gateway.event-log.retention-days, 환경변수 GATEWAY_EVENTLOG_RETENTIONDAYS)을 넘긴 행은 매일 삭제한다.
 */
@Service
public class MailEventService {

    private static final Logger log = LoggerFactory.getLogger(MailEventService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int BATCH = 500;

    private final MailEventDao dao;
    private final GatewayProperties.EventLog config;
    private final BlockingQueue<MailEvent> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final ZoneId zone = ZoneId.systemDefault();

    @Autowired
    public MailEventService(MailEventDao dao, GatewayProperties properties) {
        this.dao = dao;
        this.config = properties.getEventLog();
        this.queue = new ArrayBlockingQueue<>(Math.max(100, config.getMaxQueue()));
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public void record(MailEvent event) {
        if (!config.isEnabled() || event == null) {
            return;
        }
        if (!queue.offer(event)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("이벤트 로그 큐가 가득 차 이벤트를 버렸습니다(누적 {}건). DB 상태를 확인하세요", n);
            }
        }
    }

    public String dayOf(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DAY);
    }

    public String today() {
        return LocalDate.now(zone).format(DAY);
    }

    @Scheduled(fixedDelayString = "${gateway.event-log.flush-seconds:5}000")
    public void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<MailEvent> batch = new ArrayList<>(BATCH);
        while (queue.drainTo(batch, BATCH) > 0) {
            try {
                dao.insertBatch(batch, this::dayOf);
            } catch (Exception e) {
                log.warn("이벤트 로그 저장 실패({}건 버림): {}", batch.size(), e.getMessage());
            }
            batch = new ArrayList<>(BATCH);
        }
    }

    /** 보관 기간이 지난 이벤트 삭제. retention-days가 0 이하이면 삭제하지 않는다. */
    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        int days = config.getRetentionDays();
        if (days <= 0) {
            return;
        }
        try {
            String cutoff = LocalDate.now(zone).minusDays(days).format(DAY);
            int n = dao.deleteBefore(cutoff);
            if (n > 0) {
                log.info("이벤트 로그 {}일 초과분 {}건 삭제(기준일 {})", days, n, cutoff);
            }
        } catch (Exception e) {
            log.warn("이벤트 로그 정리 실패: {}", e.getMessage());
        }
    }

    // 조회: 모두 큐를 먼저 비워 최신 상태를 보여 준다.

    public Map<String, Long> summary(String fromDay, String toDay) {
        flush();
        return dao.summary(fromDay, toDay);
    }

    public Map<String, Map<String, Long>> daily(String fromDay, String toDay) {
        flush();
        return dao.daily(fromDay, toDay);
    }

    public Map<String, Long> top(MailEventDao.Dimension dim, String fromDay, String toDay, String action, int limit) {
        flush();
        return dao.top(dim, fromDay, toDay, action, limit);
    }

    public List<MailEvent> search(String fromDay, String toDay, String action, String filter, String ip,
                                  String senderLike, int limit) {
        flush();
        return dao.search(fromDay, toDay, action, filter, ip, senderLike, limit);
    }

    /** 인증 방식(spf|dkim|dmarc)별 결과 건수. */
    public Map<String, Long> authSummary(String mechanism, String fromDay, String toDay) {
        flush();
        return dao.authSummary(mechanism, fromDay, toDay);
    }

    public long droppedCount() {
        return dropped.get();
    }
}
