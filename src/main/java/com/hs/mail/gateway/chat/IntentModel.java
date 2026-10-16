package com.hs.mail.gateway.chat;

/** 질문을 의도 JSON으로 바꿔 주는 모델. 실제 구현은 Ollama, 테스트에서는 가짜로 대체한다. */
public interface IntentModel {

    /** 모델이 돌려준 원문(JSON이어야 함). 연결 실패 등은 예외로 알린다. */
    String complete(String system, String user) throws Exception;
}
