package com.hs.mail.gateway.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 리스트 변경 대상: 질문에 이메일이 있으면 모델이 줄여 준 값보다 질문의 주소를 쓴다. */
class ListTargetTest {

    @Test
    void 질문에_이메일이_있으면_그것을_쓴다() {
        assertEquals("spam@bad.example", ChatService.listTarget("@bad.example", "spam@bad.example 블랙리스트에서 빼줘"));
    }

    @Test
    void 이메일이_없으면_모델_값을_그대로() {
        assertEquals("@partner.co.kr", ChatService.listTarget("@partner.co.kr", "partner.co.kr 화이트리스트 해제해줘"));
        assertEquals("x", ChatService.listTarget("x", null));
    }
}
