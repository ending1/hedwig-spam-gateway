package com.hs.mail.gateway.spamfilter;

/**
 * 스팸 분류기 추상화. 구현체는 로컬 Ollama(Gemma), Gemini, Claude 등으로 교체 가능하다.
 * 호출측(InboundFilterFrontHandler)은 예외/타임아웃을 fail-open(정상 메일 취급)으로 처리한다.
 */
public interface SpamClassifier {

    SpamVerdict classify(SpamCheckRequest request) throws Exception;

    /** 응답 메시지의 X-Spam-Provider 헤더 등에 쓰이는 식별자. */
    String name();
}
