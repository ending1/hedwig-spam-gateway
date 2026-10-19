package com.hs.mail.gateway.chat;

import com.hs.mail.gateway.event.MailEventDao.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 작은 모델이 '도메인 상위'를 발신자로 잘못 고른 경우의 서버 보정. */
class DomainDimensionFixTest {

    @Test
    void 도메인_질문인데_발신자로_분류되면_발신_도메인으로_바로잡는다() {
        assertEquals(Dimension.DOMAIN, ChatService.fixDomainDimension(Dimension.SENDER, "지난 30일 동안 스팸을 가장 많이 보낸 도메인 상위 10개"));
    }

    @Test
    void 수신_표현이_있으면_수신_도메인() {
        assertEquals(Dimension.RECIPIENT_DOMAIN, ChatService.fixDomainDimension(Dimension.DOMAIN, "이번 주 스팸을 가장 많이 받은 수신 도메인은?"));
    }

    @Test
    void IP나_필터_질문이거나_도메인_언급이_없으면_그대로() {
        assertEquals(Dimension.IP, ChatService.fixDomainDimension(Dimension.IP, "도메인별 말고 IP 상위 5개"));
        assertEquals(Dimension.SENDER, ChatService.fixDomainDimension(Dimension.SENDER, "오늘 스팸 보낸 발신자 상위 3명"));
        assertEquals(Dimension.FILTER, ChatService.fixDomainDimension(Dimension.FILTER, "도메인 필터 중 제일 많이 걸린 것"));
    }
}
