package com.hs.mail.gateway.outbound;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 스풀 항목 한 건에 대한 발송 시도 -> 성공/재시도예약/dead-letter 판정을 담당한다.
 * {@link OutboundDeliveryWorker}(queue)와 {@link OutboundDelayWorker}(delay)가 공유한다.
 * 재시도 백오프는 Hedwig {@code RemoteDelivery}/스풀 설정의 {@code 4^retries * retryDelayTime} 패턴을 따른다.
 */
@Component
public class OutboundDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboundDeliveryProcessor.class);

    private final MailSender mailSender;
    private final OutboundSpoolService spoolService;
    private final GatewayProperties properties;
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public OutboundDeliveryProcessor(MailSender mailSender, OutboundSpoolService spoolService,
                                      GatewayProperties properties) {
        this.mailSender = mailSender;
        this.spoolService = spoolService;
        this.properties = properties;
    }

    public void process(OutboundMailItem item, String currentDir) {
        Map<String, List<String>> byDomain = groupByDomain(item.getRecipients());
        if (byDomain.isEmpty()) {
            spoolService.moveToDeadLetter(item, currentDir, "수신자 없음/도메인 파싱 실패");
            failureCount.incrementAndGet();
            return;
        }

        // 대부분의 경우 Hedwig이 이미 도메인 단위로 묶어서 제출하므로 단일 도메인이지만,
        // 방어적으로 여러 도메인이 섞여 들어와도 처리한다 (하나라도 영구실패면 전체 dead-letter,
        // 하나라도 일시실패면 전체 재시도 - 항목을 도메인별로 재분할하지는 않는다).
        boolean anyPermanentFailure = false;
        boolean anyTransientFailure = false;
        String lastMessage = null;

        for (Map.Entry<String, List<String>> entry : byDomain.entrySet()) {
            DeliveryResult result = mailSender.deliver(entry.getKey(), item.getMailFrom(), entry.getValue(), item.getDataFile());
            switch (result.getStatus()) {
                case SUCCESS:
                    break;
                case PERMANENT_FAILURE:
                    anyPermanentFailure = true;
                    lastMessage = result.getMessage();
                    break;
                case TRANSIENT_FAILURE:
                default:
                    anyTransientFailure = true;
                    lastMessage = result.getMessage();
                    break;
            }
        }

        if (!anyPermanentFailure && !anyTransientFailure) {
            spoolService.markSuccess(item, currentDir);
            successCount.incrementAndGet();
            return;
        }

        if (anyPermanentFailure) {
            spoolService.moveToDeadLetter(item, currentDir, lastMessage);
            failureCount.incrementAndGet();
            return;
        }

        // 일시적 실패 -> 재시도 예약
        int newAttempts = item.getAttempts() + 1;
        GatewayProperties.Outbound cfg = properties.getOutbound();
        if (newAttempts > cfg.getMaxRetries()) {
            spoolService.moveToDeadLetter(item, currentDir, "재시도 소진: " + lastMessage);
            failureCount.incrementAndGet();
            return;
        }

        long backoffSeconds = (long) (cfg.getRetryDelaySeconds() * Math.pow(4, Math.min(newAttempts, 6)));
        long nextAttemptAt = System.currentTimeMillis() + backoffSeconds * 1000L;
        try {
            spoolService.reschedule(item, currentDir, newAttempts, nextAttemptAt, lastMessage, cfg.getDelayAfterRetries());
        } catch (Exception e) {
            log.error("재시도 예약 실패: id={}", item.getId(), e);
        }
    }

    private Map<String, List<String>> groupByDomain(List<String> recipients) {
        Map<String, List<String>> byDomain = new LinkedHashMap<>();
        for (String recipient : recipients) {
            int at = recipient.indexOf('@');
            if (at < 0 || at == recipient.length() - 1) {
                continue;
            }
            String domain = recipient.substring(at + 1).toLowerCase(Locale.US);
            byDomain.computeIfAbsent(domain, d -> new java.util.ArrayList<>()).add(recipient);
        }
        return byDomain;
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getFailureCount() {
        return failureCount.get();
    }
}
