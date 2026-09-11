package com.hs.mail.gateway.maillist;

import com.hs.mail.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 관리자/사용자 화이트리스트-블랙리스트. 로컬 캐시(전체 목록 - 보통 수백 건 이내라 캐시가 단순함) +
 * 주기적 DB 동기화로 여러 인스턴스가 목록을 공유한다. 화이트리스트가 명시적으로 매치되면 블랙리스트보다
 * 우선한다(관리자가 예외를 허용한 것으로 간주). DB 조회 실패는 fail-open(NEUTRAL)으로 처리한다.
 */
@Service
public class MailListService {

    private static final Logger log = LoggerFactory.getLogger(MailListService.class);

    private final MailListDao dao;
    private final GatewayProperties.MailList config;
    private volatile List<MailListEntry> cache = new ArrayList<>();

    public MailListService(MailListDao dao, GatewayProperties properties) {
        this.dao = dao;
        this.config = properties.getMailList();
    }

    public MailListVerdict check(String sender, String recipient) {
        if (!config.isEnabled() || sender == null) {
            return MailListVerdict.NEUTRAL;
        }
        List<MailListEntry> snapshot = cache;
        boolean blackMatched = false;
        for (MailListEntry entry : snapshot) {
            if (!entry.matches(sender, recipient)) {
                continue;
            }
            if (entry.getListType() == MailListEntry.ListType.WHITE) {
                return MailListVerdict.WHITE;
            }
            blackMatched = true;
        }
        return blackMatched ? MailListVerdict.BLACK : MailListVerdict.NEUTRAL;
    }

    public List<MailListEntry> list() {
        return Collections.unmodifiableList(cache);
    }

    public void add(MailListEntry entry) {
        dao.insert(entry);
        refresh();
    }

    public void remove(MailListEntry.ListType listType, String pattern, String recipient) {
        dao.delete(listType, pattern, recipient);
        refresh();
    }

    @Scheduled(fixedDelayString = "30000")
    public void refresh() {
        if (!config.isEnabled()) {
            return;
        }
        try {
            cache = dao.findAll();
        } catch (Exception e) {
            log.warn("화이트/블랙리스트 DB 조회 실패, 기존 캐시 유지: {}", e.getMessage());
        }
    }
}
