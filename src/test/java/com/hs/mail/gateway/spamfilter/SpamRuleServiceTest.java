package com.hs.mail.gateway.spamfilter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpamRuleServiceTest {

    private SpamRuleDao dao;
    private SpamRuleService service;

    @BeforeEach
    void setUp() {
        dao = mock(SpamRuleDao.class);
        when(dao.findAll()).thenReturn(Collections.emptyList());
        service = new SpamRuleService(dao);
    }

    @Test
    void 키워드_룰이_DB에서_반영된다() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "테스트키워드", 2.5, true, "설명")));
        service.refresh();

        assertEquals(1, service.getKeywordRules().size());
        assertTrue(service.getKeywordRules().values().iterator().next().getWeight() == 2.5);
    }

    @Test
    void 비활성화된_룰은_캐시에서_제외된다() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "꺼진룰", 2.5, false, null)));
        service.refresh();

        assertTrue(service.getKeywordRules().isEmpty());
    }

    @Test
    void 잘못된_정규식은_건너뛰고_나머지는_정상_반영된다() {
        when(dao.findAll()).thenReturn(Arrays.asList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "[unclosed", 1.0, true, null),
                new SpamRuleEntry(2L, SpamRuleEntry.RuleType.KEYWORD, "정상룰", 1.0, true, null)));
        service.refresh();

        assertEquals(1, service.getKeywordRules().size());
    }

    @Test
    void STRUCTURAL_룰이_없으면_기본_가중치를_쓴다() {
        assertEquals(3.0, service.getStructuralWeight("url-raw-ip", 3.0));
        assertTrue(service.isStructuralEnabled("url-raw-ip"));
    }

    @Test
    void STRUCTURAL_룰로_가중치와_활성여부를_재정의할_수_있다() {
        when(dao.findAll()).thenReturn(Arrays.asList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.STRUCTURAL, "url-raw-ip", 9.0, true, null),
                new SpamRuleEntry(2L, SpamRuleEntry.RuleType.STRUCTURAL, "missing-date-header", 0, false, null)));
        service.refresh();

        assertEquals(9.0, service.getStructuralWeight("url-raw-ip", 3.0));
        assertFalse(service.isStructuralEnabled("missing-date-header"));
    }

    @Test
    void add_호출시_DAO_insert_후_캐시가_갱신된다() {
        SpamRuleEntry entry = new SpamRuleEntry(null, SpamRuleEntry.RuleType.BRAND, "테스트브랜드", 4.0, true, null);
        when(dao.insert(entry)).thenReturn(10L);
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new SpamRuleEntry(10L, SpamRuleEntry.RuleType.BRAND, "테스트브랜드", 4.0, true, null)));

        SpamRuleEntry created = service.add(entry);

        verify(dao).insert(entry);
        assertEquals(10L, created.getId());
        assertEquals(1, service.getBrandRules().size());
    }

    @Test
    void DB_조회_실패시_기존_캐시를_유지한채_failopen() {
        when(dao.findAll()).thenReturn(Collections.singletonList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "유지되어야함", 1.0, true, null)));
        service.refresh();
        assertEquals(1, service.getKeywordRules().size());

        when(dao.findAll()).thenThrow(new RuntimeException("db down"));
        service.refresh();
        assertEquals(1, service.getKeywordRules().size());
    }

    @Test
    void defaults_는_DB_없이_기본_룰셋으로_채워진다() {
        SpamRuleService defaults = SpamRuleService.defaults();

        assertFalse(defaults.getKeywordRules().isEmpty());
        assertFalse(defaults.getBrandRules().isEmpty());
        assertFalse(defaults.getFreeMailDomainRules().isEmpty());
        assertFalse(defaults.getUrlShortenerRules().isEmpty());
    }

    @Test
    void 테이블이_비어있으면_기동시_기본_룰셋을_시드한다() {
        SpamRuleDao emptyDao = mock(SpamRuleDao.class);
        when(emptyDao.findAll()).thenReturn(Collections.<SpamRuleEntry>emptyList());

        new SpamRuleService(emptyDao);

        org.mockito.Mockito.verify(emptyDao, org.mockito.Mockito.times(SpamRuleService.seedEntries().size()))
                .insert(org.mockito.ArgumentMatchers.any(SpamRuleEntry.class));
    }

    @Test
    void 이미_룰이_있으면_시드하지_않아_관리자_수정이_재기동으로_되살아나지_않는다() {
        SpamRuleDao filledDao = mock(SpamRuleDao.class);
        when(filledDao.findAll()).thenReturn(Collections.singletonList(
                new SpamRuleEntry(1L, SpamRuleEntry.RuleType.KEYWORD, "관리자가남긴룰", 1.0, false, null)));

        new SpamRuleService(filledDao);

        org.mockito.Mockito.verify(filledDao, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.any(SpamRuleEntry.class));
    }

    @Test
    void 시드_중_DB_오류가_나도_기동은_계속된다() {
        SpamRuleDao brokenDao = mock(SpamRuleDao.class);
        when(brokenDao.findAll()).thenReturn(Collections.<SpamRuleEntry>emptyList());
        when(brokenDao.insert(org.mockito.ArgumentMatchers.any(SpamRuleEntry.class))).thenThrow(new RuntimeException("db down"));

        SpamRuleService svc = new SpamRuleService(brokenDao);

        assertTrue(svc.getKeywordRules().isEmpty());
    }

    @Test
    void 시드에는_구조체크_가중치_행과_사내도메인_사칭_행이_포함된다() {
        boolean spoof = false;
        for (SpamRuleEntry e : SpamRuleService.seedEntries()) {
            if (e.getRuleType() == SpamRuleEntry.RuleType.STRUCTURAL && "internal-domain-spoof".equals(e.getPattern())) {
                spoof = true;
            }
        }
        assertTrue(spoof);
    }
}
