package com.hs.mail.gateway.spamfilter;

/**
 * 프롬프트 하나를 보내 응답 텍스트를 받는 범용 LLM 호출. 스팸 분류기와 같은 설정(키/모델/타임아웃)을
 * 재사용해 관리자용 분석 기능(룰 가중치 조언 등)에서 쓴다.
 */
public interface TextCompleter {

    String complete(String prompt) throws Exception;
}
