package com.hs.mail.gateway.quarantine;

import com.hs.mail.gateway.audit.AuditService;
import com.hs.mail.gateway.config.GatewayProperties;
import com.hs.mail.gateway.event.MailEvent;
import com.hs.mail.gateway.event.MailEventDao;
import com.hs.mail.gateway.event.MailEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.mail.internet.MimeUtility;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 보류 메일 일일 요약. 보류(미전달) 메일은 관리자가 확인하지 않으면 보관 기간 뒤 영구 삭제되므로, 매일 정해진 시각에
 * 보류 중인 메일, 곧 삭제될 메일, 전날 처리 현황을 관리자에게 메일로 알린다. Hedwig 백엔드로 직접 전달한다
 * (게이트웨이의 스팸 검사를 거치지 않는다). 발송 실패는 로그만 남기고 다음 날 다시 시도한다.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final QuarantineService quarantine;
    private final MailEventService events;
    private final AuditService audit;
    private final GatewayProperties properties;
    private final GatewayProperties.Digest config;

    public DigestService(QuarantineService quarantine, MailEventService events, AuditService audit, GatewayProperties properties) {
        this.quarantine = quarantine;
        this.events = events;
        this.audit = audit;
        this.properties = properties;
        this.config = properties.getDigest();
    }

    @Scheduled(cron = "${gateway.digest.cron:0 0 9 * * *}")
    public void scheduled() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            send("scheduler", false);
        } catch (Exception e) {
            log.warn("보류 요약 메일 발송 실패: {}", e.getMessage());
        }
    }

    /** 요약 본문(발송하지 않고 내용만). 보낼 내용이 없으면(보류 없음 + sendWhenEmpty=false) null. */
    public String preview() {
        Content c = build();
        return c.shouldSend() ? c.text : null;
    }

    /**
     * 요약 메일을 보낸다.
     *
     * @param force true면 보류 메일이 없어도 보낸다(관리자가 직접 "지금 보내기"를 누른 경우)
     * @return 사용자에게 보여 줄 결과 문장
     */
    public String send(String actor, boolean force) throws QuarantineService.QuarantineException {
        List<String> rcpts = recipients();
        if (rcpts.isEmpty()) {
            throw new QuarantineService.QuarantineException("요약 메일 수신자(gateway.digest.recipients)가 설정되지 않았습니다");
        }
        Content c = build();
        if (!force && !c.shouldSend()) {
            return "보류 중인 메일이 없어 요약 메일을 보내지 않았습니다.";
        }
        String from = fromAddress(rcpts);
        String message = compose(from, rcpts, c);
        int accepted = quarantine.deliver(from, rcpts, message);
        audit.record(actor, "QUARANTINE", "DIGEST_SEND", rcpts.toString(), null, "보류 " + c.held + "건, 수신 " + accepted + "명");
        log.info("보류 요약 메일 발송: 보류 {}건, 수신 {}명", c.held, accepted);
        return "요약 메일을 " + accepted + "명에게 보냈습니다(보류 " + c.held + "건).";
    }

    // ---- 내용 구성 ----

    static final class Content {
        long held;
        long expiring;
        String text;
        boolean sendWhenEmpty;

        boolean shouldSend() {
            return held > 0 || sendWhenEmpty;
        }
    }

    Content build() {
        Content c = new Content();
        c.sendWhenEmpty = config.isSendWhenEmpty();
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String yesterday = today.minusDays(1).format(DAY);
        StringBuilder sb = new StringBuilder();

        Map<String, Long> counts = quarantine.countByStatus();
        c.held = counts.containsKey(QuarantineService.HELD) ? counts.get(QuarantineService.HELD) : 0L;
        int holdDays = properties.getQuarantine().getHoldRetentionDays();
        if (holdDays > 0 && config.getExpiryWarnDays() > 0 && c.held > 0) {
            // 받은 날이 (오늘 - (보관일수 - 경고일수)) 이전이면 경고일수 안에 삭제된다.
            String cutoff = today.minusDays(Math.max(0, holdDays - config.getExpiryWarnDays())).format(DAY);
            c.expiring = quarantine.countHeldBefore(cutoff);
        }

        sb.append("[스팸 게이트웨이] 보류 메일 일일 요약 (").append(today.format(DAY)).append(")\n\n");
        sb.append("보류 중(받는 사람에게 전달되지 않은) 메일: ").append(c.held).append("건\n");
        if (c.expiring > 0) {
            sb.append("※ ").append(config.getExpiryWarnDays()).append("일 안에 자동 삭제되는 보류 메일: ").append(c.expiring)
                    .append("건 - 삭제되면 복구할 수 없으니 먼저 확인해 주세요.\n");
        }
        sb.append('\n');

        if (events != null && events.isEnabled()) {
            Map<String, Long> s = events.summary(yesterday, yesterday);
            long ham = val(s, MailEvent.HAM), spam = val(s, MailEvent.SPAM), rej = val(s, MailEvent.REJECTED), def = val(s, MailEvent.DEFERRED);
            long total = ham + spam + rej + def;
            sb.append("어제(").append(yesterday).append(") 처리 현황: ");
            if (total == 0) {
                sb.append("기록된 메일 없음\n");
            } else {
                sb.append("전체 ").append(total).append("건 - 정상 ").append(ham).append(", 스팸 ").append(spam)
                        .append(", 거부 ").append(rej).append(", 지연 ").append(def).append('\n');
                Map<String, Long> top = events.top(MailEventDao.Dimension.DOMAIN, yesterday, yesterday, MailEvent.SPAM, 3);
                if (!top.isEmpty()) {
                    sb.append("스팸 상위 발신 도메인: ");
                    boolean first = true;
                    for (Map.Entry<String, Long> e : top.entrySet()) {
                        sb.append(first ? "" : ", ").append(e.getKey()).append('(').append(e.getValue()).append(')');
                        first = false;
                    }
                    sb.append('\n');
                }
            }
            sb.append('\n');
        }

        if (c.held > 0) {
            List<QuarantineService.Entry> rows = quarantine.search("2000-01-01", "2999-12-31", QuarantineService.HELD, "ALL",
                    Math.max(1, config.getMaxItems()));
            sb.append("보류 중인 메일 (최신순, 최대 ").append(config.getMaxItems()).append("건)\n");
            for (QuarantineService.Entry e : rows) {
                sb.append(String.format(Locale.ROOT, " #%d  %s  %s -> %s%n     제목: %s  (점수 %.1f, %s)%n",
                        e.id, TS.format(Instant.ofEpochMilli(e.receivedAtMillis)), oneLine(e.mailFrom), oneLine(e.rcptList),
                        oneLine(e.subject), e.score, e.filter));
            }
            if (c.held > rows.size()) {
                sb.append(" ... 외 ").append(c.held - rows.size()).append("건\n");
            }
            sb.append('\n');
        }

        sb.append("처리 방법: 관리자 대시보드의 [격리함] 탭에서 보류 메일을 보고 오탐이면 [전달] 또는 [전달+허용], 스팸이 맞으면 [삭제]하세요.\n");
        if (config.getDashboardUrl() != null && !config.getDashboardUrl().trim().isEmpty()) {
            sb.append("대시보드: ").append(oneLine(config.getDashboardUrl())).append('\n');
        }
        if (holdDays > 0) {
            sb.append("보류 메일은 ").append(holdDays).append("일이 지나면 자동으로 영구 삭제됩니다.\n");
        }
        c.text = sb.toString();
        return c;
    }

    String compose(String from, List<String> rcpts, Content c) {
        String subject = "[스팸 게이트웨이] 보류 메일 " + c.held + "건"
                + (c.expiring > 0 ? " (곧 삭제 " + c.expiring + "건)" : "") + " - 일일 요약";
        StringBuilder m = new StringBuilder();
        try {
            m.append("From: ").append(from).append("\r\n");
            m.append("To: ").append(String.join(", ", rcpts)).append("\r\n");
            m.append("Subject: ").append(MimeUtility.encodeText(subject, "UTF-8", "B")).append("\r\n");
            m.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
            m.append("Message-ID: <digest-").append(UUID.randomUUID()).append('@')
                    .append(from.substring(from.indexOf('@') + 1)).append(">\r\n");
            m.append("MIME-Version: 1.0\r\n");
            m.append("Content-Type: text/plain; charset=UTF-8\r\n");
            m.append("Content-Transfer-Encoding: base64\r\n");
            m.append("X-Auto-Response-Suppress: All\r\n");
            m.append("\r\n");
            m.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(c.text.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        return m.toString();
    }

    private List<String> recipients() {
        List<String> out = new ArrayList<>();
        for (String r : config.getRecipients()) {
            if (r != null && r.trim().contains("@") && !r.trim().contains(" ")) {
                out.add(r.trim());
            }
        }
        return out;
    }

    private String fromAddress(List<String> rcpts) {
        String f = config.getFrom() == null ? "" : config.getFrom().trim();
        if (f.contains("@")) {
            return f;
        }
        String first = rcpts.get(0);
        return "spam-gateway@" + first.substring(first.indexOf('@') + 1);
    }

    private static long val(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    /** 헤더/본문 한 줄에 안전하게 넣도록 줄바꿈 제거 및 길이 제한. */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }
}
